(ns agent.cli.render
  "Line-buffered markdown -> ANSI rendering for CLI streaming.

   Deltas accumulate until a full line is available, so the stream stays
   live while inline styling, fences, and blockquotes render per line.
   Tables buffer until their block completes, then draw with box-drawing
   characters. When stdout is not an interactive terminal the renderer is
   a byte-identical passthrough."
  (:require
   [clojure.string :as str]))

(def ^:private default-width 80)
(def ^:private esc "\u001b[")
(def ^:private ansi-rx #"\u001b\[[0-9;]*m")

(defn tty?
  "True when stdout is an interactive terminal and the user has not opted
   out of styling (NO_COLOR, TERM=dumb)."
  []
  (and (nil? (System/getenv "NO_COLOR"))
       (not= "dumb" (System/getenv "TERM"))
       (some? (System/console))))

(defn- sgr [codes s]
  (str esc codes "m" s esc "0m"))

(defn- visual-length [s]
  (count (str/replace s ansi-rx "")))

(defn- style-plain [s]
  (-> s
      (str/replace #"\[([^\]\n]+)\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)"
                   (fn [[_ text url]]
                     (str (sgr "4" text) " " (sgr "2" (str "(" url ")")))))
      (str/replace #"\*\*([^*\n]+?)\*\*" (fn [[_ body]] (sgr "1" body)))
      (str/replace #"(?<![A-Za-z0-9_])__([^_\n]+?)__(?![A-Za-z0-9_])"
                   (fn [[_ body]] (sgr "1" body)))
      (str/replace #"~~([^~\n]+?)~~" (fn [[_ body]] (sgr "9" body)))
      (str/replace #"==([^=\n]+?)==" (fn [[_ body]] (sgr "43;30" body)))
      (str/replace #"\|\|([^|\n]+?)\|\|" (fn [[_ body]] (sgr "2" body)))
      (str/replace #"(?<![A-Za-z0-9*])\*(?!\s)([^*\n]+?)(?<!\s)\*(?![A-Za-z0-9*])"
                   (fn [[_ body]] (sgr "3" body)))
      (str/replace #"(?<![A-Za-z0-9_])_(?!\s)([^_\n]+?)(?<!\s)_(?![A-Za-z0-9_])"
                   (fn [[_ body]] (sgr "3" body)))))

(defn- style-inline [s]
  (->> (re-seq #"`[^`\n]+`|[^`]+|`" (str s))
       (map (fn [part]
              (if (and (> (count part) 1)
                       (str/starts-with? part "`")
                       (str/ends-with? part "`"))
                (sgr "7" (subs part 1 (dec (count part))))
                (style-plain part))))
       (apply str)))

;; --- line classification ------------------------------------------------------

(defn- fence-open [line]
  (when-let [[_ _ marker lang] (re-matches #"(\s*)(`{3,}|~{3,})\s*(\S*)\s*" line)]
    {:char (first marker) :length (count marker) :lang lang}))

(defn- fence-close? [line {:keys [char length]}]
  (when-let [[_ marker] (re-matches #"\s*([`~]{3,})\s*" line)]
    (and (= char (first marker))
         (>= (count marker) length))))

(defn- table-row? [line]
  (some? (re-matches #"\s*\|.*" line)))

(defn- table-separator? [line]
  (and (str/includes? line "-")
       (some? (re-matches #"\s*\|?[\s:\-|]+\|?\s*" line))))

;; --- table drawing ------------------------------------------------------------

(defn- split-row [line]
  (let [trimmed (-> (str/trim line)
                    (str/replace #"^\|" "")
                    (str/replace #"\|\s*$" ""))]
    (mapv #(-> (str/trim %) (str/replace "\\|" "|"))
          (str/split trimmed #"(?<!\\)\|" -1))))

(defn- column-alignment [separator-cell]
  (let [cell (str/trim separator-cell)
        left? (str/starts-with? cell ":")
        right? (str/ends-with? cell ":")]
    (cond
      (and left? right?) :center
      right? :right
      :else :left)))

(defn- pad-cell [s width alignment]
  (let [gap (max 0 (- width (visual-length s)))]
    (case alignment
      :right (str (apply str (repeat gap " ")) s)
      :center (let [lead (quot gap 2)]
                (str (apply str (repeat lead " "))
                     s
                     (apply str (repeat (- gap lead) " "))))
      (str s (apply str (repeat gap " "))))))

(defn- table-border [widths left mid right]
  (sgr "2" (str left
                (str/join mid (map #(apply str (repeat (+ % 2) "─")) widths))
                right)))

(defn- draw-table! [lines]
  (let [rows (vec (keep-indexed (fn [idx line]
                                  (when-not (= 1 idx) (split-row line)))
                                lines))
        alignments (mapv column-alignment (split-row (second lines)))
        cols (apply max (map count rows))
        aligned #(nth alignments % :left)
        styled (map-indexed (fn [idx row]
                              (mapv (fn [col]
                                      (let [cell (nth row col "")]
                                        (if (zero? idx)
                                          (sgr "1" cell)
                                          (style-inline cell))))
                                    (range cols)))
                            rows)
        widths (mapv (fn [col]
                       (apply max 1 (map #(visual-length (nth % col)) styled)))
                     (range cols))
        render-row (fn [row]
                     (str (sgr "2" "│")
                          (str/join (sgr "2" "│")
                                    (map #(str " " (pad-cell (nth row %) (nth widths %) (aligned %)) " ")
                                         (range cols)))
                          (sgr "2" "│")))
        [header & body] styled]
    (println (table-border widths "┌" "┬" "┐"))
    (println (render-row header))
    (println (table-border widths "├" "┼" "┤"))
    (doseq [row body]
      (println (render-row row)))
    (println (table-border widths "└" "┴" "┘"))))

;; --- text lines ----------------------------------------------------------------

(defn- rule [width]
  (sgr "2" (apply str (repeat width "─"))))

(defn- fence-rule [width lang]
  (let [label (if (str/blank? (str lang)) "" (str " " lang " "))
        head (str "──" label)]
    (sgr "2" (str head (apply str (repeat (max 0 (- width (count head))) "─"))))))

(defn- styled-text-line [line width]
  (cond
    (re-matches #"\s*(#{1,6})\s+.*" line)
    (let [[_ hashes title] (re-matches #"\s*(#{1,6})\s+(.*?)\s*" line)]
      (if (<= (count hashes) 3)
        (sgr "1" title)
        (sgr "1;2" title)))

    (re-matches #"\s*([-*_])(\s*\1){2,}\s*" line)
    (rule width)

    (re-matches #"\s*>\s?.*" line)
    (let [body (str/replace line #"^\s*>\s?" "")]
      (str (sgr "2" "┃") " " (style-inline body)))

    (re-matches #"(\s*)[-*+]\s+\[[ xX]\]\s+.*" line)
    (let [[_ indent checked body] (re-matches #"(\s*)[-*+]\s+\[([ xX])\]\s+(.*)" line)]
      (str indent (if (= " " checked) "☐" "☑") " " (style-inline body)))

    (re-matches #"(\s*)[-*+]\s+.*" line)
    (let [[_ indent body] (re-matches #"(\s*)[-*+]\s+(.*)" line)]
      (str indent (sgr "2" "•") " " (style-inline body)))

    (re-matches #"(\s*)\d+[.)]\s+.*" line)
    (let [[_ indent label body] (re-matches #"(\s*)(\d+[.)])\s+(.*)" line)]
      (str indent (sgr "2" label) " " (style-inline body)))

    :else
    (style-inline line)))

;; --- state machine -------------------------------------------------------------

(declare handle-line!)

(defn- flush-table-rows!
  "Stashed lines never became a table; print them as plain text directly —
   reclassifying them would re-enter the table states and loop."
  [state width]
  (let [rows (:rows @state)]
    (swap! state assoc :mode :text :rows [])
    (doseq [row rows]
      (println (styled-text-line row width)))))

(defn- end-table! [state]
  (let [rows (:rows @state)]
    (swap! state assoc :mode :text :rows [])
    (draw-table! rows)))

(defn- handle-line! [state line width]
  (case (:mode @state)
    :fence
    (if (fence-close? line (:fence @state))
      (do (swap! state assoc :mode :text :fence nil)
          (println (rule width)))
      (println (sgr "36" line)))

    :table-pending
    (if (table-separator? line)
      (swap! state #(-> % (assoc :mode :table) (update :rows conj line)))
      (do (flush-table-rows! state width)
          (handle-line! state line width)))

    :table
    (if (table-row? line)
      (swap! state update :rows conj line)
      (do (end-table! state)
          (handle-line! state line width)))

    ;; :text
    (cond
      (fence-open line)
      (let [fence (fence-open line)]
        (swap! state assoc :mode :fence :fence fence)
        (println (fence-rule width (:lang fence))))

      (table-row? line)
      (swap! state #(-> % (assoc :mode :table-pending) (assoc :rows [line])))

      :else
      (println (styled-text-line line width)))))

(defn- finish! [state width]
  (let [{:keys [pending]} @state]
    (swap! state assoc :pending "")
    (when-not (str/blank? pending)
      (handle-line! state pending width)))
  (case (:mode @state)
    :fence (println (rule width))
    :table-pending (flush-table-rows! state width)
    :table (end-table! state)
    nil)
  (swap! state assoc :mode :text :fence nil :rows [])
  (flush))

(defn make-stream-renderer
  "Returns {:on-delta f :finish f}. With :tty? false both are raw
   passthrough — byte-identical to printing the deltas."
  [{:keys [tty? width] :or {width default-width}}]
  (if-not tty?
    {:on-delta (fn [delta] (print delta) (flush))
     :finish (fn [])}
    (let [state (atom {:pending "" :mode :text :fence nil :rows []})]
      {:on-delta (fn [delta]
                   (swap! state update :pending str delta)
                   (loop []
                     (let [pending (:pending @state)
                           idx (str/index-of pending "\n")]
                       (when idx
                         (swap! state assoc :pending (subs pending (inc idx)))
                         (handle-line! state (subs pending 0 idx) width)
                         (recur))))
                   (flush))
       :finish (fn [] (finish! state width))})))

(defn render-string!
  "Renders complete (non-streamed) content: styled when tty?, raw otherwise."
  [content opts]
  (if (:tty? opts)
    (let [{:keys [on-delta finish]} (make-stream-renderer opts)]
      (on-delta (str content))
      (finish))
    (do (print (str content))
        (flush))))
