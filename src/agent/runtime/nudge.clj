(ns agent.runtime.nudge
  "Small-model retry governor for invalid planner turns."
  (:require
   [agent.runtime.calls :as calls]
   [clojure.string :as str]))

(def default-budgets
  {:bare-text 0
   :unknown-tool 0
   :malformed-args 0
   :repeated-tool-call 0
   :missing-prerequisite 0
   :repeated-same-error 0
   :premature-final 0
   :max-token-truncation 0
   :edit-failure 0})

(def default-profile
  {:small-model? false
   :respond-tool? false
   :force-tool-choice? false
   :tool-routing? false
   :max-nudges 0
   :nudge-budgets default-budgets})

(defn normalize-profile [profile]
  (let [profile* (merge default-profile (or profile {}))]
    (assoc profile* :nudge-budgets (merge default-budgets (:nudge-budgets profile*)))))

(defn enabled? [profile]
  (true? (:small-model? (normalize-profile profile))))

(defn new-state []
  {:counts {}
   :total 0
   :last-tool-call nil
   :last-error nil
   :fs-read-paths #{}
   :fs-listed-paths #{}})

(defn- call-input [call]
  (calls/call-input call ::malformed))

(defn tool-fingerprint [call]
  {:tool-name (calls/tool-name-of call)
   :input (call-input call)})

(defn error-fingerprint [receipt]
  {:tool-name (keyword (:tool-name receipt))
   :input (:input receipt)
   :error-type (keyword (:error-type receipt))
   :reason (or (:reason receipt) (:error receipt))})

(defn- blank-content? [llm-response]
  (str/blank? (or (:content llm-response) "")))

(defn- internal-stop-content? [content]
  (and (string? content)
       (let [content* (str/trim content)]
         (or (str/starts-with? content* "Stopped:")
             (str/starts-with? content* "I couldn't complete this after guardrail retries.")))))

(defn- directive-type [directive]
  (keyword (:type directive)))

(defn- complete-directive? [directive]
  (= :complete (directive-type directive)))

(defn- respond-call? [directive]
  (and (= :tool-call (directive-type directive))
       (= :respond (keyword (get-in directive [:payload :tool-name])))))

(defn- tool-call-directive? [directive]
  (= :tool-call (directive-type directive)))

(defn- parent-path [path]
  (when (string? path)
    (some-> (java.io.File. path) .getParent)))

(defn- fs-mutation? [directive]
  (and (tool-call-directive? directive)
       (contains? calls/fs-mutation-tools
                  (keyword (get-in directive [:payload :tool-name])))))

(defn- fs-prereq-ok? [state path]
  (let [path* (str path)
        parent (parent-path path*)]
    (or (contains? (:fs-read-paths state) path*)
        (contains? (:fs-listed-paths state) path*)
        (and parent (contains? (:fs-listed-paths state) parent)))))

(defn- unknown-tool? [allowed directive]
  (and (tool-call-directive? directive)
       (not (contains? allowed (keyword (get-in directive [:payload :tool-name]))))))

(defn- malformed-args? [llm-response]
  (some #(= ::malformed (call-input %)) (:tool-calls llm-response)))

(defn- missing-prereq-receipt? [receipt]
  (= :missing-prerequisite (keyword (or (:error-type receipt) (:status receipt)))))

(defn- same-error-receipt [state receipts]
  (some (fn [receipt]
          (let [fp (error-fingerprint receipt)]
            (when (= fp (:last-error state))
              fp)))
        (filter #(contains? #{:error :denied :blocked} (keyword (:status %))) receipts)))

(defn- classify-before-exec
  [{:keys [profile state step llm-response allowed-tools max-token?]}]
  (let [profile* (normalize-profile profile)
        directives (vec (:directives step))
        allowed (set (map keyword allowed-tools))
        tool-calls (filter tool-call-directive? directives)
        first-tool-call (some-> tool-calls first :payload)
        fp (when first-tool-call
             {:tool-name (keyword (:tool-name first-tool-call))
              :input (:input first-tool-call)})]
    (cond
      ;; finish_reason="length" routinely accompanies a complete tool_calls
      ;; array (the model emitted the call, then hit the output cap). Only
      ;; treat truncation as a guardrail event when there are no tool calls
      ;; to fall through to; otherwise let the tools run.
      (and max-token?
           (empty? (:tool-calls llm-response)))
      {:reason :max-token-truncation
       :fingerprint {:stop-reason (some-> (:stop-reason llm-response) name)}}

      (and (some complete-directive? directives)
           (internal-stop-content? (:content llm-response)))
      {:reason :premature-final
       :fingerprint {:internal-stop true}}

      (and (:respond-tool? profile*)
           (not (:synthetic-respond? llm-response))
           (seq directives)
           (every? complete-directive? directives))
      {:reason :bare-text
       :fingerprint {:chars (count (or (:content llm-response) ""))}}

      (some #(and (fs-mutation? %) (not (fs-prereq-ok? state (get-in % [:payload :input :path])))) directives)
      (let [blocked (some #(when (and (fs-mutation? %)
                                      (not (fs-prereq-ok? state (get-in % [:payload :input :path]))))
                             %)
                          directives)]
        {:reason :missing-prerequisite
         :fingerprint {:tool-name (keyword (get-in blocked [:payload :tool-name]))
                       :path (get-in blocked [:payload :input :path])}})

      (and (:respond-tool? profile*) (seq tool-calls) (some respond-call? directives) (> (count tool-calls) 1))
      {:reason :premature-final
       :fingerprint {:tool-count (count tool-calls)}}

      (some #(unknown-tool? allowed %) directives)
      (let [bad (some #(when (unknown-tool? allowed %) %) directives)]
        {:reason :unknown-tool
         :fingerprint {:tool-name (keyword (get-in bad [:payload :tool-name]))}})

      (malformed-args? llm-response)
      {:reason :malformed-args
       :fingerprint {:tool-calls (mapv tool-fingerprint (:tool-calls llm-response))}}

      (and fp (= fp (:last-tool-call state)))
      {:reason :repeated-tool-call
       :fingerprint fp}

      (and (complete-directive? (first directives))
           (not (:respond-tool? profile*))
           (blank-content? llm-response))
      {:reason :premature-final
       :fingerprint {:blank true}}

      :else nil)))

(defn- classify-after-exec [{:keys [state receipts]}]
  (or (some (fn [receipt]
              (when (missing-prereq-receipt? receipt)
                {:reason :missing-prerequisite
                 :fingerprint (error-fingerprint receipt)}))
            receipts)
      (when-let [fp (same-error-receipt state receipts)]
        {:reason :repeated-same-error
         :fingerprint fp})
      (some (fn [receipt]
              (when (and (contains? calls/fs-mutation-tools
                                    (keyword (:tool-name receipt)))
                         (contains? #{:error :denied} (keyword (:status receipt)))
                         (contains? #{:not-found :not-directory :path-not-allowed :validation-failed}
                                    (keyword (:error-type receipt))))
                {:reason :edit-failure
                 :fingerprint (error-fingerprint receipt)}))
            receipts)))

(defn- retry-content [classification]
  (case (:reason classification)
    :bare-text "Use `respond` for final answers. If work needed, call one tool. Do not answer as plain assistant text."
    :unknown-tool "Use only tools in current schema. Pick closest valid tool or `respond`."
    :malformed-args "Retry tool call with valid JSON arguments matching schema."
    :repeated-tool-call "Do not repeat identical tool call. Use result, change input, or call `respond`."
    :missing-prerequisite "Required prerequisite missing. First read/list same path or parent, then retry mutation."
    :repeated-same-error "Same tool error repeated. Change approach or call `respond` with blocker."
    :premature-final "Do not finalize before required tool work. Execute needed tools first; call `respond` only when done."
    :max-token-truncation "Previous response hit max output tokens. Retry smaller, incremental output."
    :edit-failure "Filesystem edit failed. Read target context, then retry with exact unique replacement."
    "Retry with valid tool call or `respond`."))

(defn- budget-left? [profile state reason]
  (let [profile* (normalize-profile profile)
        used (get-in state [:counts reason] 0)
        total (:total state 0)
        reason-budget (get-in profile* [:nudge-budgets reason] 0)
        max-nudges (:max-nudges profile* 0)]
    (and (< used reason-budget)
         (< total max-nudges))))

(defn- verdict [profile state classification]
  (if (budget-left? profile state (:reason classification))
    {:action :retry
     :reason (:reason classification)
     :fingerprint (:fingerprint classification)
     :content (retry-content classification)}
    (let [reason (:reason classification)
          tool-name (some-> classification :fingerprint :tool-name name)]
      {:action :fatal
       :reason reason
       :fingerprint (:fingerprint classification)
       :content (str "I couldn't complete this after guardrail retries. Last issue: "
                     (name reason)
                     (when tool-name
                       (str " on " tool-name))
                     ". "
                     (retry-content classification))
       :stop-reason :guardrail-exhausted})))

(defn check-before-exec [profile state ctx]
  (if-not (enabled? profile)
    {:action :execute}
    (if-let [classification (classify-before-exec (assoc ctx :profile profile :state state))]
      (verdict profile state classification)
      {:action :execute})))

(defn check-after-exec [profile state ctx]
  (if-not (enabled? profile)
    {:action :execute}
    (if-let [classification (classify-after-exec (assoc ctx :state state))]
      (verdict profile state classification)
      {:action :execute})))

(defn record-retry [state verdict]
  (-> state
      (update-in [:counts (:reason verdict)] (fnil inc 0))
      (update :total (fnil inc 0))))

(defn record-execution [state step receipts]
  (let [tool-directive (first (filter tool-call-directive? (:directives step)))
        tool-fp (when tool-directive
                  {:tool-name (keyword (get-in tool-directive [:payload :tool-name]))
                   :input (get-in tool-directive [:payload :input])})
        error-fp (some error-fingerprint
                       (filter #(contains? #{:error :denied :blocked} (keyword (:status %))) receipts))
        fs-successes (filter #(and (contains? #{:fs_read :fs_list} (keyword (:tool-name %)))
                                   (contains? #{:ok :completed} (keyword (:status %))))
                             receipts)]
    (cond-> state
      tool-fp (assoc :last-tool-call tool-fp)
      error-fp (assoc :last-error error-fp)
      (seq fs-successes)
      (as-> s
          (reduce (fn [acc receipt]
                    (let [tool-name (keyword (:tool-name receipt))
                          path (str (get-in receipt [:input :path]))]
                      (case tool-name
                        :fs_read (update acc :fs-read-paths conj path)
                        :fs_list (update acc :fs-listed-paths conj path)
                        acc)))
                  s
                  fs-successes)))))

(defn nudge-message [verdict]
  {:role "system"
   :content (str "NUDGE (" (name (:reason verdict)) "): " (:content verdict))
   :metadata {:runtime/nudge? true
              :reason (:reason verdict)}})
