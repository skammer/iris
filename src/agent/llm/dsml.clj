(ns agent.llm.dsml
  "Recovers tool calls that DeepSeek-style models leak as DSML markup
  (e.g. `<｜DSML｜tool_calls>...`) inside `message.content` instead of
   populating the structured `tool_calls` field."
  (:require
   [agent.logging :as logging]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private dsml-tool-calls-re
  #"(?s)<｜+DSML｜+tool_calls>(.*?)</｜+DSML｜+tool_calls>")

(def ^:private dsml-invoke-re
  #"(?s)<｜+DSML｜+invoke\s+name=\"([^\"]+)\">(.*?)</｜+DSML｜+invoke>")

(def ^:private dsml-parameter-re
  #"(?s)<｜+DSML｜+parameter\s+name=\"([^\"]+)\"[^>]*>(.*?)</｜+DSML｜+parameter>")

(def ^:private tool-call-re
  #"(?s)<tool_call>(.*?)</tool_call>")

(def ^:private function-re
  #"(?s)<function\s*=\s*\"?([^>\"]+?)\"?\s*>(.*?)</function>")

(def ^:private parameter-re
  #"(?s)<parameter\s*=\s*\"?([^>\"]+?)\"?\s*>(.*?)</parameter>")

(def ^:private tool-markup-openers
  ["<｜DSML｜tool_calls>" "<tool_call>"])

(defn- normalize-marker-bars [text]
  (str/replace text #"｜+" "｜"))

(defn- starts-with-tool-markup? [text]
  (let [trimmed (normalize-marker-bars (str/triml (str text)))]
    (some #(str/starts-with? trimmed %) tool-markup-openers)))

(defn- possible-tool-markup-prefix? [text]
  (let [trimmed (normalize-marker-bars (str/triml (str text)))]
    (or (str/blank? trimmed)
        (some #(or (str/starts-with? % trimmed)
                   (str/starts-with? trimmed %))
              tool-markup-openers))))

(defn guard-content-delta
  "Suppress streamed leaked tool markup while still allowing normal text.
   Normally enabled only when tools were sent. `force?` protects recovery
   responses, where tools are intentionally unavailable but models may still
   leak tool markup copied from conversation history."
  ([on-content-delta tools]
   (guard-content-delta on-content-delta tools false))
  ([on-content-delta tools force?]
   (cond
     (nil? on-content-delta) nil
     (and (empty? tools) (not force?)) on-content-delta
     :else
     (let [mode (atom :undecided)
           buffered (atom "")]
       (fn [chunk]
         (case @mode
           :streaming
           (on-content-delta chunk)

           :suppressing
           nil

           :undecided
           (let [text (swap! buffered str chunk)]
             (cond
               (starts-with-tool-markup? text)
               (reset! mode :suppressing)

               (possible-tool-markup-prefix? text)
               nil

               :else
               (do
                 (reset! mode :streaming)
                 (on-content-delta text))))))))))

(defn- parse-parameters [re invoke-body]
  (->> (re-seq re invoke-body)
       (reduce (fn [acc [_ k v]]
                 (assoc acc (str/trim k) (str/trim v)))
               {})))

(defn- tool-call [prefix tool-name args]
  {:id (str prefix "_" (java.util.UUID/randomUUID))
   :type "function"
   :function {:name (str/trim tool-name)
              :arguments (json/generate-string args)}})

(defn- dsml-block->tool-calls [block-body]
  (mapv (fn [[_ tool-name body]]
          (tool-call "dsml" tool-name (parse-parameters dsml-parameter-re body)))
        (re-seq dsml-invoke-re block-body)))

(defn- tagged-block->tool-calls [block-body]
  (mapv (fn [[_ tool-name body]]
          (tool-call "toolcall" tool-name (parse-parameters parameter-re body)))
        (re-seq function-re block-body)))

(defn- strip-blocks [content]
  (-> content
      (str/replace dsml-tool-calls-re "")
      (str/replace tool-call-re "")
      str/trim))

(defn recover-tool-calls
  "Recover DSML/tool_call markup into structured tool calls and strip markup.
   If native tool calls already exist, keep them and only strip leaked markup."
  [{:keys [content tool-calls] :as turn}]
  (if (or (not (string? content)) (str/blank? content))
    turn
    (try
      (let [dsml-blocks (re-seq dsml-tool-calls-re content)
            tagged-blocks (re-seq tool-call-re content)
            recovered (concat
                       (mapcat (fn [[_ body]] (dsml-block->tool-calls body)) dsml-blocks)
                       (mapcat (fn [[_ body]] (tagged-block->tool-calls body)) tagged-blocks))]
        (if (and (empty? recovered)
                 (not (seq tool-calls)))
          turn
          (assoc turn
                 :content (strip-blocks content)
                 :tool-calls (vec (concat (or tool-calls [])
                                          (when-not (seq tool-calls)
                                            recovered))))))
      (catch Exception e
        (logging/log! :agent.llm.dsml/recover-failed
                      {:error/message (.getMessage e)
                       :content/chars (count content)})
        turn))))
