(ns agent.llm.dsml
  "Recovers tool calls that DeepSeek-style models leak as DSML markup
   (e.g. `<｜DSML｜tool_calls>...`) inside `message.content` instead of
   populating the structured `tool_calls` field."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private tool-calls-re
  #"(?s)<｜DSML｜tool_calls>(.*?)</｜DSML｜tool_calls>")

(def ^:private invoke-re
  #"(?s)<｜DSML｜invoke\s+name=\"([^\"]+)\">(.*?)</｜DSML｜invoke>")

(def ^:private parameter-re
  #"(?s)<｜DSML｜parameter\s+name=\"([^\"]+)\"[^>]*>(.*?)</｜DSML｜parameter>")

(defn- parse-invoke [invoke-body]
  (->> (re-seq parameter-re invoke-body)
       (reduce (fn [acc [_ k v]]
                 (assoc acc k (str/trim v)))
               {})))

(defn- block->tool-calls [block-body]
  (mapv (fn [[_ tool-name body]]
          {:id (str "dsml_" (java.util.UUID/randomUUID))
           :type "function"
           :function {:name tool-name
                      :arguments (json/generate-string (parse-invoke body))}})
        (re-seq invoke-re block-body)))

(defn- strip-blocks [content]
  (-> (str/replace content tool-calls-re "")
      str/trim))

(defn recover-tool-calls
  "If `:tool-calls` is empty and `:content` contains DSML tool-call blocks,
   parse them out, append them as structured tool calls, and strip the markup
   from `:content`. Otherwise return the turn unchanged."
  [{:keys [content tool-calls] :as turn}]
  (if (or (seq tool-calls) (not (string? content)) (str/blank? content))
    turn
    (try
      (let [blocks (re-seq tool-calls-re content)
            recovered (mapcat (fn [[_ body]] (block->tool-calls body)) blocks)]
        (if (empty? recovered)
          turn
          (assoc turn
                 :content (strip-blocks content)
                 :tool-calls (vec (concat (or tool-calls []) recovered)))))
      (catch Exception e
        (tap> {:event :dsml/recover-failed
               :error (.getMessage e)
               :content-preview (subs content 0 (min 200 (count content)))})
        turn))))
