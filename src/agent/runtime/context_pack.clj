(ns agent.runtime.context-pack
  "Per-call LLM context shrinking."
  (:require
   [agent.llm.messages :as llm-messages]
   [agent.runtime.schema :as runtime-schema]
   [clojure.string :as str]))

(def default-config
  {:max-context-tokens 8192
   :reserve-output-tokens 1024
   :warning-threshold 0.8
   :destructive-threshold 1.0
   :max-summary-input-tokens 8192
   :summarizer-input-cap 8192
   :summary-max-tokens 512
   :tool-result-truncate-chars 2000
   :budgets {:system 1200
             :memory 1200
             :recent-conversation 4096
             :tool-schema 1600
             :pending-tool-result 800
             :referenced-file 2400
             :output-reserve 1024}})

(defn estimate-tokens [value]
  (let [text (cond
               (string? value) value
               (nil? value) ""
               :else (pr-str value))]
    (long (Math/ceil (/ (count text) 4.0)))))

(defn- role [message]
  (cond
    (keyword? (:role message)) (name (:role message))
    (string? (:role message)) (:role message)
    :else (str (:role message))))

(defn- message-tokens [message]
  (estimate-tokens message))

(defn- messages-tokens [messages]
  (reduce + 0 (map message-tokens messages)))

(defn- tools-tokens [tools]
  (estimate-tokens (or tools [])))

(defn- total-context-tokens [{:keys [messages system-prompt tools reserve-output-tokens]}]
  (+ (estimate-tokens system-prompt)
     (tools-tokens tools)
     (long (or reserve-output-tokens 0))
     (messages-tokens messages)))

(defn- threshold-tokens [limit threshold]
  (let [threshold* (or threshold 1.0)]
    (if (> threshold* 1)
      (long threshold*)
      (long (Math/floor (* limit threshold*))))))

(defn- normalize-messages [messages]
  (llm-messages/messages->internal (or messages [])))

(defn- leading-system-count [messages]
  (count (take-while #(= "system" (role %)) messages)))

(defn- latest-user-index [messages]
  (last (keep-indexed #(when (= "user" (role %2)) %1) messages)))

(defn- tool-call-blocks [message]
  (filterv #(= :tool-call (:type %))
           (runtime-schema/normalize-content (:content message))))

(defn- tool-result-blocks [message]
  (filterv #(= :tool-result (:type %))
           (runtime-schema/normalize-content (:content message))))

(defn- tool-call-message? [message]
  (and (= "assistant" (role message))
       (seq (tool-call-blocks message))))

(defn- tool-result-message? [message]
  (= "tool" (role message)))

(defn- last-tool-loop-start [messages]
  (last (keep-indexed #(when (tool-call-message? %2) %1) messages)))

(defn- tool-loop-indices [messages start]
  (when start
    (loop [idx (inc start)
           acc #{start}]
      (if (and (< idx (count messages))
               (tool-result-message? (nth messages idx)))
        (recur (inc idx) (conj acc idx))
        acc))))

(defn- protected-indices [messages]
  (let [leading (set (range (leading-system-count messages)))
        latest-user (latest-user-index messages)
        loop-start (last-tool-loop-start messages)
        recent-loop (or (tool-loop-indices messages loop-start) #{})]
    (cond-> (into leading recent-loop)
      latest-user (conj latest-user))))

(defn- memory-message? [message]
  (and (= "system" (role message))
       (str/includes? (llm-messages/content-text message)
                      "Relevant memory JSON:")))

(defn- system-token-count [messages system-prompt]
  (+ (estimate-tokens system-prompt)
     (messages-tokens (filter #(= "system" (role %)) messages))))

(defn- memory-token-count [messages]
  (messages-tokens (filter memory-message? messages)))

(defn- tool-result-token-count [messages]
  (messages-tokens (filter tool-result-message? messages)))

(defn- budget-report [cfg messages system-prompt tools]
  (let [budgets (:budgets cfg)]
    {:system {:used (system-token-count messages system-prompt)
              :limit (:system budgets)}
     :memory {:used (memory-token-count messages)
              :limit (:memory budgets)}
     :recent-conversation {:used (messages-tokens (remove #(= "system" (role %)) messages))
                           :limit (:recent-conversation budgets)}
     :tool-schema {:used (tools-tokens tools)
                   :limit (:tool-schema budgets)}
     :pending-tool-result {:used (tool-result-token-count messages)
                           :limit (:pending-tool-result budgets)}
     :referenced-file {:used (tool-result-token-count messages)
                       :limit (:referenced-file budgets)}
     :output-reserve {:used (:reserve-output-tokens cfg)
                      :limit (:output-reserve budgets)}}))

(defn- truncate [text max-chars]
  (let [text* (str (or text ""))]
    (if (> (count text*) max-chars)
      (str (subs text* 0 max-chars)
           "\n\n[context-pack truncated "
           (- (count text*) max-chars)
           " chars]")
      text*)))

(defn- compact-tool-result-message [message max-chars compact?]
  (let [blocks (runtime-schema/normalize-content (:content message))
        blocks* (mapv (fn [block]
                        (if (= :tool-result (:type block))
                          (assoc block :content
                                 (if compact?
                                   (str "[compacted tool result omitted; tool-call-id="
                                        (:tool-call-id block)
                                        "]")
                                   (truncate (:content block) max-chars)))
                          block))
                      blocks)]
    (assoc message :content blocks*)))

(defn- truncate-old-tool-results [messages protected cfg]
  (reduce-kv (fn [acc idx message]
               (if (and (tool-result-message? message)
                        (not (contains? protected idx))
                        (> (message-tokens message)
                           (estimate-tokens (:tool-result-truncate-chars cfg))))
                 (-> acc
                     (update :messages assoc idx
                             (compact-tool-result-message message
                                                          (:tool-result-truncate-chars cfg)
                                                          false))
                     (update :decisions conj {:action :truncate-tool-result
                                              :index idx}))
                 acc))
             {:messages (vec messages) :decisions []}
             (vec messages)))

(defn- compact-old-tool-results [messages protected]
  (reduce-kv (fn [acc idx message]
               (if (and (tool-result-message? message)
                        (not (contains? protected idx)))
                 (-> acc
                     (update :messages assoc idx
                             (compact-tool-result-message message 0 true))
                     (update :decisions conj {:action :compact-tool-result
                                              :index idx}))
                 acc))
             {:messages (vec messages) :decisions []}
             (vec messages)))

(defn- safe-cut-index [messages idx]
  (loop [i idx]
    (cond
      (>= i (count messages)) i
      (tool-result-message? (nth messages i)) (recur (inc i))
      :else i)))

(defn- text-preview [message]
  (-> (llm-messages/content-text message)
      (str/replace #"\s+" " ")
      (truncate 360)))

(defn summary-input [messages max-tokens]
  (loop [remaining messages
         tokens 0
         acc []]
    (if-let [message (first remaining)]
      (let [line {:role (role message)
                  :content (text-preview message)
                  :tool-calls (mapv #(select-keys % [:id :name :arguments])
                                    (tool-call-blocks message))
                  :tool-results (mapv #(select-keys % [:tool-call-id :name :status])
                                      (tool-result-blocks message))}
            tokens* (+ tokens (estimate-tokens line))]
        (if (> tokens* max-tokens)
          acc
          (recur (rest remaining) tokens* (conj acc line))))
      acc)))

(defn summary-prompt [messages cfg]
  (str "Summarize compacted Iris chat context for the next LLM call. "
       "Preserve user goals, constraints, decisions, files, tool results, pending work. "
       "Be concise.\n\n"
       (str/join "\n"
                 (map (fn [{:keys [role content tool-calls tool-results]}]
                        (str role ": " content
                             (when (seq tool-calls)
                               (str " tool-calls=" (pr-str tool-calls)))
                             (when (seq tool-results)
                               (str " tool-results=" (pr-str tool-results)))))
                      (summary-input messages
                                     (or (:summarizer-input-cap cfg)
                                         (:max-summary-input-tokens cfg)))))))

(defn deterministic-summary [messages cfg]
  (let [input (summary-input messages (:max-summary-input-tokens cfg))
        roles (frequencies (map :role input))
        latest-user (some #(when (= "user" (:role %)) (:content %))
                          (reverse input))]
    (str "Compacted earlier context: "
         (count input)
         " messages; roles "
         (pr-str roles)
         "."
         (when latest-user
           (str " Latest earlier user: " latest-user))
         (when (seq input)
           (str "\nKey excerpts:\n"
                (str/join "\n"
                          (map (fn [{:keys [role content]}]
                                 (str "- " role ": " content))
                               (take-last 8 input))))))))

(defn- compact-summary [messages cfg summarizer-fn]
  (let [fallback #(deterministic-summary messages cfg)
        summary (try
                  (let [result (when summarizer-fn
                                 (summarizer-fn {:messages messages
                                                 :prompt (summary-prompt messages cfg)}))]
                    (if (str/blank? (str result))
                      (fallback)
                      (str result)))
                  (catch Exception _
                    (fallback)))]
    (truncate summary (* 4 (:summary-max-tokens cfg)))))

(defn- summary-message [summary]
  {:role "system"
   :content [{:type :text
              :text (str "Context summary for compacted earlier conversation:\n"
                         summary)}]})

(defn- pack-by-prefix-summary
  [{:keys [messages system-prompt tools summarizer-fn] :as ctx} cfg decisions]
  (let [prefix-count (leading-system-count messages)
        protected (protected-indices messages)
        limit (threshold-tokens (:max-context-tokens cfg)
                                (:destructive-threshold cfg))
        first-protected (apply min
                               (conj (vec (filter #(>= % prefix-count) protected))
                                     (count messages)))
        max-cut first-protected
        with-tokens (fn [candidate]
                      (total-context-tokens (assoc ctx
                                                   :messages candidate
                                                   :reserve-output-tokens (:reserve-output-tokens cfg))))
        choose-cut (fn []
                     (loop [cut (inc prefix-count)]
                       (let [cut* (min max-cut (safe-cut-index messages cut))
                             dropped (subvec messages prefix-count cut*)
                             summary (summary-message
                                      (deterministic-summary dropped cfg))
                             candidate (vec (concat (subvec messages 0 prefix-count)
                                                    [summary]
                                                    (subvec messages cut*)))]
                         (cond
                           (>= cut* max-cut) max-cut
                           (and (<= (with-tokens candidate) limit)
                                (not-any? protected (range prefix-count cut*))) cut*
                           :else (recur (inc cut*))))))]
    (if (or (>= prefix-count (count messages))
            (<= max-cut prefix-count))
      {:messages messages :decisions decisions :compaction nil}
      (let [cut (choose-cut)
            dropped (subvec messages prefix-count cut)
            summary (compact-summary dropped cfg summarizer-fn)
            summary-msg (summary-message summary)
            kept (vec (concat (subvec messages 0 prefix-count)
                              [summary-msg]
                              (subvec messages cut)))
            first-kept (first (subvec messages cut))]
        {:messages kept
         :decisions (conj decisions {:action :summarize-prefix
                                     :dropped-count (count dropped)
                                     :first-kept-entry-id (:id first-kept)})
         :compaction {:summary summary
                      :first-kept-entry-id (:id first-kept)
                      :dropped-count (count dropped)
                      :tokens-before (:tokens-before ctx)}}))))

(defn pack-context
  "Return a ContextPack map. Input keys: :messages, :system-prompt, :tools,
   :config, :summarizer-fn."
  [{:keys [messages system-prompt tools config] :as ctx}]
  (let [cfg (merge default-config config)
        cfg (assoc cfg :budgets (merge (:budgets default-config)
                                       (:budgets config)))
        messages* (normalize-messages messages)
        tokens-before (total-context-tokens {:messages messages*
                                             :system-prompt system-prompt
                                             :tools tools
                                             :reserve-output-tokens (:reserve-output-tokens cfg)})
        max-context (:max-context-tokens cfg)
        warning-at (threshold-tokens max-context (:warning-threshold cfg))
        destructive-at (threshold-tokens max-context (:destructive-threshold cfg))
        warnings (cond-> []
                   (>= tokens-before warning-at)
                   (conj {:level :warning
                          :tokens tokens-before
                          :threshold warning-at})
                   (>= tokens-before destructive-at)
                   (conj {:level :destructive
                          :tokens tokens-before
                          :threshold destructive-at}))
        protected (protected-indices messages*)
        truncated (if (>= tokens-before destructive-at)
                    (truncate-old-tool-results messages* protected cfg)
                    {:messages messages* :decisions []})
        tokens-after-truncate (total-context-tokens {:messages (:messages truncated)
                                                     :system-prompt system-prompt
                                                     :tools tools
                                                     :reserve-output-tokens (:reserve-output-tokens cfg)})
        compacted-tools (if (and (>= tokens-before destructive-at)
                                 (> tokens-after-truncate destructive-at))
                          (compact-old-tool-results (:messages truncated) protected)
                          {:messages (:messages truncated) :decisions []})
        tokens-after-tools (total-context-tokens {:messages (:messages compacted-tools)
                                                  :system-prompt system-prompt
                                                  :tools tools
                                                  :reserve-output-tokens (:reserve-output-tokens cfg)})
        prefix-packed (if (and (>= tokens-before destructive-at)
                               (> tokens-after-tools destructive-at))
                        (pack-by-prefix-summary (assoc ctx
                                                       :messages (:messages compacted-tools)
                                                       :tokens-before tokens-before)
                                                cfg
                                                (vec (concat (:decisions truncated)
                                                             (:decisions compacted-tools))))
                        {:messages (:messages compacted-tools)
                         :decisions (vec (concat (:decisions truncated)
                                                 (:decisions compacted-tools)))
                         :compaction nil})
        tokens-after (total-context-tokens {:messages (:messages prefix-packed)
                                            :system-prompt system-prompt
                                            :tools tools
                                            :reserve-output-tokens (:reserve-output-tokens cfg)})]
    {:messages (:messages prefix-packed)
     :budgets (budget-report cfg (:messages prefix-packed) system-prompt tools)
     :decisions (:decisions prefix-packed)
     :tokens-before tokens-before
     :tokens-after tokens-after
     :warnings warnings
     :compaction (some-> (:compaction prefix-packed)
                         (assoc :tokens-after tokens-after))}))
