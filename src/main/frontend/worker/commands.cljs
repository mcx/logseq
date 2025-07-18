(ns frontend.worker.commands
  "Invoke commands based on user settings"
  (:require [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [datascript.core :as d]
            [frontend.worker.handler.page.db-based.page :as worker-db-page]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.db :as ldb]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.property.build :as db-property-build]
            [logseq.db.frontend.property.type :as db-property-type]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.outliner.pipeline :as outliner-pipeline]))

;; TODO: allow users to add command or configure it through #Command (which parent should be #Code)
(def *commands
  (atom
   [[:repeated-task
     {:title "Repeated task"
      :entity-conditions [{:property :logseq.property.repeat/repeated?
                           :value true}]
      :tx-conditions [{:property :status
                       :value :done}]
      :actions [[:reschedule]
                [:set-property :status :todo]]}]
    [:property-history
     {:title "Record property history"
      :tx-conditions [{:kind :datom-attribute-check?
                       :property :logseq.property/enable-history?
                       :value true}]
      :actions [[:record-property-history]]}]]))

(defn- get-property
  [entity property]
  (if (= property :status)
    (or
     (:db/ident (:logseq.property.repeat/checked-property entity))
     :logseq.property/status)
    property))

(defn- get-value
  [entity property value]
  (cond
    (and (= property :status) (= value :done))
    (or
     (let [p (:logseq.property.repeat/checked-property entity)
           choices (:property/closed-values p)
           checkbox? (= :checkbox (:logseq.property/type p))]
       (if checkbox?
         true
         (some (fn [choice]
                 (when (:logseq.property/choice-checkbox-state choice)
                   (:db/id choice))) choices)))
     :logseq.property/status.done)
    (and (= property :status) (= value :todo))
    (or
     (let [p (:logseq.property.repeat/checked-property entity)
           choices (:property/closed-values p)
           checkbox? (= :checkbox (:logseq.property/type p))]
       (if checkbox?
         false
         (some (fn [choice]
                 (when (false? (:logseq.property/choice-checkbox-state choice))
                   (:db/id choice))) choices)))
     :logseq.property/status.todo)
    :else
    value))

(defn satisfy-condition?
  "Whether entity or updated datoms satisfy the `condition`"
  [db entity {:keys [kind property value]} datoms]
  (let [property' (get-property entity property)
        value' (get-value entity property value)]
    (when-let [property-entity (d/entity db property')]
      (let [value-matches? (fn [datom-value]
                             (let [ref? (contains? db-property-type/all-ref-property-types (:logseq.property/type property-entity))
                                   db-value (cond
                                              ;; entity-conditions
                                              (nil? datom-value)
                                              (get entity property')
                                              ;; tx-conditions
                                              ref?
                                              (d/entity db datom-value)
                                              :else
                                              datom-value)]
                               (cond
                                 (qualified-keyword? value')
                                 (and (map? db-value) (= value' (:db/ident db-value)))

                                 ref?
                                 (or
                                  (and (uuid? value') (= (:block/uuid db-value) value'))
                                  (= value' (db-property/property-value-content db-value))
                                  (= value' (:db/id db-value)))

                                 :else
                                 (= db-value value'))))]
        (if (seq datoms)
          (case kind
            :datom-attribute-check?
            (some (fn [d]
                    (= value' (get (d/entity db (:a d)) property)))
                  datoms)

            (some (fn [d] (and (value-matches? (:v d)) (:added d)))
                  (filter (fn [d] (= property' (:a d))) datoms)))
          (value-matches? nil))))))

(defmulti handle-command (fn [action-id & _others] action-id))

(defn- repeat-until-future-timestamp
  [datetime recur-unit frequency period-f keep-week?]
  (let [now (t/now)
        v (max
           1
           (if (t/after? datetime now)
             1
             (period-f (t/interval datetime now))))
        delta (->> (Math/ceil (/ v frequency))
                   (* frequency)
                   recur-unit)
        result* (t/plus datetime delta)
        result (if (t/after? result* now)
                 result*
                 (t/plus result* (recur-unit frequency)))
        w1 (t/day-of-week datetime)
        w2 (t/day-of-week result)]
    (if (and keep-week? (not= w1 w2))
      ;; next week
      (if (> w2 w1)
        (t/plus result (t/days (- 7 (- w2 w1))))
        (t/plus result (t/days (- w1 w2))))
      result)))

(defn- get-next-time
  [current-value unit frequency]
  (let [current-date-time (tc/to-date-time current-value)
        [recur-unit period-f] (case (:db/ident unit)
                                :logseq.property.repeat/recur-unit.minute [t/minutes t/in-minutes]
                                :logseq.property.repeat/recur-unit.hour [t/hours t/in-hours]
                                :logseq.property.repeat/recur-unit.day [t/days t/in-days]
                                :logseq.property.repeat/recur-unit.week [t/weeks t/in-weeks]
                                :logseq.property.repeat/recur-unit.month [t/months t/in-months]
                                :logseq.property.repeat/recur-unit.year [t/years t/in-years]
                                nil)]
    (when recur-unit
      (let [week? (= (:db/ident unit) :logseq.property.repeat/recur-unit.week)
            next-time (repeat-until-future-timestamp current-date-time recur-unit frequency period-f week?)]
        (tc/to-long next-time)))))

(defn- compute-reschedule-property-tx
  [conn db entity property-ident]
  (let [frequency (or (db-property/property-value-content (:logseq.property.repeat/recur-frequency entity))
                      (let [property (d/entity db :logseq.property.repeat/recur-frequency)
                            default-value-block (db-property-build/build-property-value-block property property 1)
                            default-value-tx-data [default-value-block
                                                   {:db/id (:db/id property)
                                                    :logseq.property/default-value [:block/uuid (:block/uuid default-value-block)]}]]
                        (d/transact! conn default-value-tx-data)
                        1))
        unit (:logseq.property.repeat/recur-unit entity)
        property (d/entity db property-ident)
        date? (= :date (:logseq.property/type property))
        current-value (cond->
                       (get entity property-ident)
                        date?
                        (#(date-time-util/journal-day->ms (:block/journal-day %))))]
    (when (and frequency unit)
      (when-let [next-time-long (get-next-time current-value unit frequency)]
        (let [journal-day (outliner-pipeline/get-journal-day-from-long db next-time-long)
              {:keys [tx-data page-uuid]} (if journal-day
                                            {:page-uuid (:block/uuid (d/entity db journal-day))}
                                            (let [formatter (:logseq.property.journal/title-format (d/entity db :logseq.class/Journal))
                                                  title (date-time-util/format (t/to-default-time-zone (tc/to-date-time next-time-long)) formatter)]
                                              (worker-db-page/create db title {})))
              value (if date? [:block/uuid page-uuid] next-time-long)]
          (concat
           tx-data
           (when value
             [[:db/add (:db/id entity) property-ident value]])))))))

(defmethod handle-command :reschedule [_ conn db entity _datoms]
  (let [property-ident (or (:db/ident (:logseq.property.repeat/temporal-property entity))
                           :logseq.property/scheduled)
        other-property-idents (cond
                                (and (= property-ident :logseq.property/scheduled)
                                     (:logseq.property/deadline entity))
                                [:logseq.property/deadline]

                                (and (= property-ident :logseq.property/deadline)
                                     (:logseq.property/scheduled entity))
                                [:logseq.property/scheduled]

                                :else
                                (filter (fn [p] (get entity p)) [:logseq.property/deadline :logseq.property/scheduled]))]
    (mapcat #(compute-reschedule-property-tx conn db entity %) (distinct (cons property-ident other-property-idents)))))

(defmethod handle-command :set-property [_ _db _conn entity _datoms property value]
  (let [property' (get-property entity property)
        value' (get-value entity property value)]
    [[:db/add (:db/id entity) property' value']]))

(defmethod handle-command :record-property-history [_ _conn db entity datoms]
  (let [changes (keep (fn [d]
                        (let [property (d/entity db (:a d))]
                          (when (and (true? (get property :logseq.property/enable-history?))
                                     (:added d))
                            {:property property
                             :value (:v d)}))) datoms)]
    (map
     (fn [{:keys [property value]}]
       (let [ref? (= :db.type/ref (:db/valueType property))
             value-key (if ref? :logseq.property.history/ref-value :logseq.property.history/scalar-value)]
         (sqlite-util/block-with-timestamps
          {:block/uuid (ldb/new-block-id)
           value-key value
           :logseq.property.history/block (:db/id entity)
           :logseq.property.history/property (:db/id property)})))
     changes)))

(defmethod handle-command :default [command _conn _db entity datoms]
  (throw (ex-info "Unhandled command"
                  {:command command
                   :entity entity
                   :datoms datoms})))

(defn execute-command
  "Build tx-data"
  [conn db entity datoms [_command {:keys [actions]}]]
  (mapcat (fn [action]
            (apply handle-command (first action) conn db entity datoms (rest action))) actions))

(defn run-commands
  [conn {:keys [tx-data db-after]}]
  (let [db db-after]
    (mapcat (fn [[e datoms]]
              (let [entity (d/entity db e)
                    commands (filter (fn [[_command {:keys [entity-conditions tx-conditions]}]]
                                       (and
                                        (if (seq entity-conditions)
                                          (every? #(satisfy-condition? db entity % nil) entity-conditions)
                                          true)
                                        (every? #(satisfy-condition? db entity % datoms) tx-conditions))) @*commands)]
                (mapcat
                 (fn [command]
                   (execute-command conn db entity datoms command))
                 commands)))
            (group-by :e tx-data))))
