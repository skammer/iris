(ns agent.cron.schedule
  "Canonical schedule validation and next-fire calculation."
  (:require [clojure.string :as str])
  (:import
   (com.cronutils.model CronType)
   (com.cronutils.model.definition CronDefinitionBuilder)
   (com.cronutils.model.time ExecutionTime)
   (com.cronutils.parser CronParser)
   (java.time Duration Instant ZoneId ZonedDateTime)
   (java.time.zone ZoneRulesException)))

(def minimum-interval-seconds 60)

(def ^:private unix-definition
  (CronDefinitionBuilder/instanceDefinitionFor CronType/UNIX))
(def ^:private parser (CronParser. unix-definition))

(defn- validation-error [message data]
  (throw (ex-info message (assoc data :type :validation-failed))))

(defn normalize-kind [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    :else value))

(defn timezone! [value]
  (let [value* (some-> value str str/trim)]
    (when (str/blank? value*)
      (validation-error "timezone is required" {:field :timezone}))
    (try
      (ZoneId/of value*)
      (catch ZoneRulesException e
        (throw (ex-info "timezone must be a valid IANA timezone"
                        {:type :validation-failed :field :timezone :value value*}
                        e))))))

(defn- instant! [value field]
  (try
    (Instant/parse (str value))
    (catch Exception e
      (throw (ex-info (str (name field) " must be an ISO-8601 UTC instant")
                      {:type :validation-failed :field field :value value}
                      e)))))

(defn- cron! [expression]
  (let [expression* (some-> expression str str/trim)]
    (when (str/blank? expression*)
      (validation-error "cron schedule requires schedule.expression"
                        {:field :expression :value expression}))
    (when-not (= 5 (count (remove str/blank? (str/split (or expression* "") #"\s+"))))
      (validation-error "cron expression must contain exactly five fields"
                        {:field :expression :value expression}))
    (try
      (doto (.parse parser expression*) .validate)
      (catch Exception e
        (throw (ex-info "invalid five-field cron expression"
                        {:type :validation-failed :field :expression :value expression*}
                        e))))))

(defn normalize
  [schedule]
  (let [kind (normalize-kind (:kind schedule))]
    (case kind
      :cron
      (let [expression (some-> (:expression schedule) str str/trim)]
        (cron! expression)
        {:kind :cron :expression expression})

      :at
      {:kind :at :at (str (instant! (:at schedule) :at))}

      :interval
      (let [seconds (:every-seconds schedule)
            seconds* (cond
                       (integer? seconds) (long seconds)
                       (string? seconds) (try (Long/parseLong seconds)
                                              (catch Exception _ nil))
                       :else nil)]
        (when-not (and seconds* (>= seconds* minimum-interval-seconds))
          (validation-error "every-seconds must be an integer >= 60"
                            {:field :every-seconds :value seconds}))
        {:kind :interval
         :every-seconds seconds*
         :anchor-at (str (instant! (:anchor-at schedule) :anchor-at))})

      (validation-error "schedule.kind must be cron, at, or interval"
                        {:field :kind :value (:kind schedule)}))))

(defn- next-cron [schedule zone after]
  (let [execution (ExecutionTime/forCron (cron! (:expression schedule)))
        previous-local (.toLocalDateTime (ZonedDateTime/ofInstant after zone))]
    (loop [cursor (ZonedDateTime/ofInstant after zone)]
      (let [candidate (.nextExecution execution cursor)]
        (when (.isPresent candidate)
          (let [next-zdt (.get candidate)]
            (if (= previous-local (.toLocalDateTime next-zdt))
              (recur next-zdt)
              (.toInstant next-zdt))))))))

(defn- next-interval [{:keys [every-seconds anchor-at]} after]
  (let [anchor (Instant/parse anchor-at)]
    (if (.isAfter anchor after)
      anchor
      (let [elapsed (.getSeconds (Duration/between anchor after))
            steps (inc (quot elapsed every-seconds))]
        (.plusSeconds anchor (* steps every-seconds))))))

(defn next-fire
  "First fire strictly after `after`; nil for exhausted one-shot schedules."
  [schedule timezone after]
  (let [schedule* (normalize schedule)
        zone (timezone! timezone)
        after* (if (instance? Instant after) after (instant! after :after))]
    (case (:kind schedule*)
      :cron (next-cron schedule* zone after*)
      :at (let [at (Instant/parse (:at schedule*))]
            (when (.isAfter at after*) at))
      :interval (next-interval schedule* after*))))

(defn next-fires
  ([schedule timezone after] (next-fires schedule timezone after 5))
  ([schedule timezone after n]
   (loop [cursor (if (instance? Instant after) after (instant! after :after))
          result []]
     (if (>= (count result) n)
       result
       (if-let [fire (next-fire schedule timezone cursor)]
         (recur fire (conj result (str fire)))
         result)))))

(defn preview [schedule timezone after]
  (let [schedule* (normalize schedule)]
    {:schedule schedule*
     :timezone (str (timezone! timezone))
     :next-runs (next-fires schedule* timezone after 5)}))
