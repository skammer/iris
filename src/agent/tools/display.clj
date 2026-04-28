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
         :preview-chars 120
         :max-result-height-px 320
         :per-tool {}}
   :telegram {:show-tool-calls? true
              :preview-chars 240
              :args-preview-chars 120
              :per-tool {}}
   :api {:show-tool-calls? true
         :full? true
         :per-tool {}}})

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
      (str (subs s 0 n) "…")
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

(defn args-preview
  "Single-line, length-capped representation of tool arguments."
  [args max-chars]
  (-> args args->string single-line (truncate max-chars)))

(defn result-preview
  "Single-line, length-capped representation of a tool result."
  [result max-chars]
  (-> result result->string single-line (truncate max-chars)))

(defn telegram-summary
  "Compact one-message summary of a tool turn for Telegram."
  [system {:keys [tool-name status input result]}]
  (let [cfg (channel-config system :telegram tool-name)
        args-cap (:args-preview-chars cfg 120)
        result-cap (:preview-chars cfg 240)
        head (str "🔧 " (or tool-name "tool")
                  (when status (str " · " (name (keyword status)))))
        args-line (let [a (args-preview input args-cap)]
                    (when-not (str/blank? a) (str "args: " a)))
        result-line (let [r (result-preview result result-cap)]
                      (when-not (str/blank? r) (str "→ " r)))]
    (->> [head args-line result-line]
         (remove nil?)
         (str/join "\n"))))
