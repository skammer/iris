(ns agent.tools.display
  "Per-channel formatting of tool calls and tool results.

   Tool execution is identical across channels; what differs is how much of the
   result the user sees inline. Web wraps results in a collapsible block with a
   capped-height body; telegram sends a short summary as a separate message; the
   API hands over the full payload untouched."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]))

(def default-channel-config
  {:web {:show-tool-calls? true
         :collapsed? true
         :preview-chars 800
         :args-preview-chars 800
         :max-result-height-px 320
         :per-tool {}}
   :telegram {:show-tool-calls? true
              :preview-chars 1600
              :args-preview-chars 1200
              :per-tool {}}
   :api {:show-tool-calls? true
         :full? true
         :per-tool {}}})

(def ^:private telegram-max-message-chars 4096)

(defn channel-config
  "Returns the display config for `channel`, merged with per-tool overrides for
   `tool-name` when present."
  [system channel tool-name]
  (let [base (or (get-in system [:config :tools :display channel])
                 (get default-channel-config channel))
        per-tool (when tool-name
                   (get-in base [:per-tool (keyword tool-name)]))]
    (merge base per-tool)))

(defn- truncate [s n]
  (let [s (str s)]
    (if (and n (> (count s) n))
      (str (subs s 0 n) "...")
      s)))

(defn- single-line [s]
  (-> (str s)
      (str/replace #"\s+" " ")
      str/trim))

(declare params->string)

(defn- value->string [value]
  (cond
    (nil? value) "nil"
    (keyword? value) (name value)
    (string? value) value
    (map? value) (str "{" (params->string value) "}")
    (sequential? value) (str/join ", " (map value->string value))
    :else (str value)))

(defn- params->string [params]
  (cond
    (nil? params) ""
    (string? params) params
    (map? params) (->> params
                       (map (fn [[k v]]
                              (str (if (keyword? k) (name k) (str k))
                                   ": "
                                   (value->string v))))
                       (str/join " "))
    :else (value->string params)))

(defn- result->string [result]
  (cond
    (nil? result) ""
    (string? result) result
    :else (json/generate-string result)))

(defn- escape-html-char [ch]
  (case ch
    \& "&amp;"
    \< "&lt;"
    \> "&gt;"
    (str ch)))

(defn- escape-html-truncated [s max-chars]
  (loop [chars (seq (str s))
         acc []
         n 0]
    (if-let [ch (first chars)]
      (let [escaped (escape-html-char ch)
            n* (+ n (count escaped))]
        (if (<= n* max-chars)
          (recur (next chars) (conj acc escaped) n*)
          (apply str acc)))
      (apply str acc))))

(defn- pretty-value [value]
  (cond
    (nil? value) ""
    (string? value) value
    :else (json/generate-string value {:pretty true})))

(defn args-preview
  "Single-line, length-capped representation of tool arguments."
  [args max-chars]
  (-> args params->string single-line (truncate max-chars)))

(defn result-preview
  "Single-line, length-capped representation of a tool result."
  [result max-chars]
  (-> result result->string single-line (truncate max-chars)))

(defn block-preview
  "Multi-line, length-capped representation of a value."
  [value max-chars]
  (-> value pretty-value (truncate max-chars)))

(defn params-preview
  "Human-readable, non-JSON, single-line representation of tool parameters."
  [params max-chars]
  (args-preview params max-chars))

(defn telegram-summary
  "Compact one-message summary of a tool turn for Telegram."
  [system {:keys [tool-name input]}]
  (let [cfg (channel-config system :telegram tool-name)
        args-cap (:args-preview-chars cfg 1200)
        args (params-preview input args-cap)
        line (str "🔧 " (or tool-name "tool")
                  (when-not (str/blank? args)
                    (str " " args)))]
    (escape-html-truncated line telegram-max-message-chars)))
