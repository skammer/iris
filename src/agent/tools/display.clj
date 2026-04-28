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
(def ^:private telegram-blockquote-open "<blockquote expandable>")
(def ^:private telegram-blockquote-close "</blockquote>")

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

(defn- args->string [args]
  (cond
    (nil? args) ""
    (string? args) args
    :else (json/generate-string args)))

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
  (-> args args->string single-line (truncate max-chars)))

(defn result-preview
  "Single-line, length-capped representation of a tool result."
  [result max-chars]
  (-> result result->string single-line (truncate max-chars)))

(defn block-preview
  "Multi-line, length-capped representation of a value."
  [value max-chars]
  (-> value pretty-value (truncate max-chars)))

(defn- blockquote [s]
  (let [max-body (- telegram-max-message-chars
                    (count telegram-blockquote-open)
                    (count telegram-blockquote-close))]
    (str telegram-blockquote-open
         (escape-html-truncated s max-body)
         telegram-blockquote-close)))

(defn telegram-summary
  "Compact one-message summary of a tool turn for Telegram."
  [system {:keys [tool-name status input result]}]
  (let [cfg (channel-config system :telegram tool-name)
        args-cap (:args-preview-chars cfg 1200)
        result-cap (:preview-chars cfg 1600)
        head (str "🔧 " (or tool-name "tool")
                  (when status (str " " (name (keyword status)))))
        args-block (let [a (block-preview input args-cap)]
                     (when-not (str/blank? a) a))
        result-block (let [r (block-preview result result-cap)]
                       (when-not (str/blank? r) r))]
    (blockquote
     (->> [head args-block result-block]
         (remove nil?)
         (str/join "\n")))))
