(ns benchmark-context-pack
  "Deterministic equivalence and paired context-packing microbenchmark.
   Run: clojure -M scripts/benchmark_context_pack.clj target/context-pack-before.clj"
  (:require
   [agent.runtime.context-pack :as pack]
   [clojure.string :as str]))

(defn history [n tools?]
  (into [{:id "system" :role "system" :content "Relevant memory JSON: {}"}]
        (concat
         (mapcat (fn [i]
                   (if tools?
                     [{:id (str "call-" i) :role "assistant"
                       :content [{:type :tool-call :id (str i) :name "http" :arguments {}}]}
                      {:id (str "result-" i) :role "tool"
                       :content [{:type :tool-result :tool-call-id (str i)
                                  :content (apply str (repeat 600 "x"))}]}]
                     [{:id (str "message-" i)
                       :role (if (even? i) "user" "assistant")
                       :content (apply str (repeat 600 "x"))}]))
                 (range n))
         [{:id "latest" :role "user" :content "latest request"}])))

(defn context [n tools?]
  {:messages (history n tools?)
   :system-prompt "planner"
   :tools []
   :config {:max-context-tokens (if tools? 10000000 500)
            :reserve-output-tokens 0
            :max-summary-input-tokens 128
            :tool-result-truncate-chars 2000
            :budgets {:pending-tool-result (if tools? 100 1000000)}}})

(defn- milliseconds [f ctx]
  (let [start (System/nanoTime)]
    (f ctx)
    (/ (- (System/nanoTime) start) 1e6)))

(defn- median [xs]
  (nth (vec (sort xs)) (quot (count xs) 2)))

(defn -main [baseline-file]
  (when-not baseline-file
    (throw (ex-info "Supply the baseline context_pack.clj source path" {})))
  (load-string (str/replace-first (slurp baseline-file)
                                  "(ns agent.runtime.context-pack"
                                  "(ns context-pack-baseline"))
  (let [before (ns-resolve 'context-pack-baseline 'pack-context)
        after pack/pack-context
        cases (for [n [0 1 8 40]
                    tools? [false true]
                    limit [100 500 100000]
                    summarizer [nil (constantly "short summary")
                                (fn [_] (throw (ex-info "offline" {})))]]
                (-> (context n tools?)
                    (assoc :summarizer-fn summarizer)
                    (assoc-in [:config :max-context-tokens] limit)))]
    (doseq [ctx cases]
      (when-not (= (before ctx) (after ctx))
        (throw (ex-info "Context pack changed" {:config (:config ctx)
                                                :messages (count (:messages ctx))}))))
    (prn {:equivalent-cases (count cases)})
    (doseq [tools? [false true]
            n [100 300 600]
            :let [ctx (context n tools?)]]
      (dotimes [_ 3] (before ctx) (after ctx))
      (let [pairs (vec (for [i (range 7)]
                         (if (even? i)
                           [(milliseconds before ctx) (milliseconds after ctx)]
                           (let [new-ms (milliseconds after ctx)]
                             [(milliseconds before ctx) new-ms]))))
            old-ms (median (map first pairs))
            new-ms (median (map second pairs))]
        (prn {:scenario (if tools? :tool-budget :prefix-summary)
              :messages (count (:messages ctx))
              :before-ms old-ms :after-ms new-ms :speedup (/ old-ms new-ms)})))))

(apply -main *command-line-args*)
