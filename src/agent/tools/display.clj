(ns agent.tools.display
  "Per-channel formatting of tool calls and tool results.

   Tool execution is identical across channels; what differs is how much of the
   result the user sees inline. Web wraps results in a collapsible block with a
   capped-height body; Telegram aggregates consecutive calls into one summary;
   the API hands over the full payload untouched."
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

(defn- keyword-label [value]
  (if-let [ns (namespace value)]
    (str ns "/" (name value))
    (name value)))

(defn- label [value]
  (cond
    (nil? value) ""
    (keyword? value) (keyword-label value)
    :else (str value)))

(declare params->string)

(defn- value->string [value]
  (cond
    (nil? value) "nil"
    (keyword? value) (keyword-label value)
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
                              (str (if (keyword? k) (keyword-label k) (str k))
                                   ": "
                                   (value->string v))))
                       (str/join " "))
    :else (value->string params)))

(defn- escape-html-char [ch]
  (case ch
    \& "&amp;"
    \< "&lt;"
    \> "&gt;"
    (str ch)))

(defn escape-html
  "Escape &, <, and > for HTML-formatted channel messages."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn escape-html-truncated
  "Escape `s` for HTML, keeping at most max-chars escaped characters."
  [s max-chars]
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

(defn args-preview
  "Single-line, length-capped representation of tool arguments."
  [args max-chars]
  (-> args params->string single-line (truncate max-chars)))

(defn telegram-summary
  "Compact one-message summary of a tool turn for Telegram."
  [system {:keys [tool-name input status]}]
  (let [cfg (channel-config system :telegram tool-name)
        args-cap (:args-preview-chars cfg 1200)
        args (args-preview input args-cap)
        status-text (label status)
        line (str "🔧 " (or (not-empty (label tool-name)) "tool")
                  (when-not (str/blank? status-text)
                    (str " status: " status-text))
                  (when-not (str/blank? args)
                    (str " " args)))]
    (escape-html-truncated line telegram-max-message-chars)))

(defn- pretty-input [input]
  (try
    (json/generate-string (or input {}) {:pretty true})
    (catch Exception _
      (pr-str input))))

(defn- rich-tool-block
  [system idx {:keys [tool-name input status reason error-type]}]
  (let [cfg (channel-config system :telegram tool-name)
        args-cap (:args-preview-chars cfg 1200)
        title (str "<b>" (inc idx) ". "
                   (escape-html (or (not-empty (label tool-name)) "tool"))
                   "</b>"
                   (when-let [status* (not-empty (label status))]
                     (str " · " (escape-html status*))))
        details (cond-> {:input (or input {})}
                  reason (assoc :error reason)
                  error-type (assoc :error-type error-type))
        input* (-> (pretty-input details)
                   (truncate args-cap)
                   escape-html)]
    (str title "\n<pre>" input* "</pre>")))

(defn telegram-rich-batch-summary
  "One Rich Markdown summary for consecutive Telegram tool calls."
  ([system receipts]
   (telegram-rich-batch-summary system nil receipts))
  ([system title receipts]
   (let [receipts* (vec receipts)
         n (count receipts*)
         fallback (str "Called " n " " (if (= 1 n) "tool" "tools"))]
     (when (pos? n)
       (str "<details><summary>" (escape-html (or (not-empty title) fallback))
            "</summary>\n<blockquote>\n"
            (str/join "\n<hr>\n"
                      (map-indexed #(rich-tool-block system %1 %2) receipts*))
            "\n</blockquote>\n</details>")))))

(defn telegram-plain-batch-summary
  "Legacy fallback: aggregate consecutive calls without Rich Markdown tags."
  ([system receipts]
   (telegram-plain-batch-summary system nil receipts))
  ([system title receipts]
   (let [receipts* (vec receipts)
         n (count receipts*)]
     (when (pos? n)
       (str (or (not-empty title)
                (str "Called " n " " (if (= 1 n) "tool" "tools")))
            "\n\n"
            (str/join "\n\n"
                      (map #(telegram-summary system %) receipts*)))))))
