(ns agent.cli-test
  (:require
   [agent.cli :as cli]
   [agent.logging :as logging]
   [agent.system :as system]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(deftest parse-headless-session-flags-test
  (is (= {:config-path "local.edn"
          :print? true
          :continue? true
          :session-id "session-1"
          :prompt "finish work"}
         (select-keys (cli/parse-args ["--config" "local.edn"
                                       "-p"
                                       "-c"
                                       "--session" "session-1"
                                       "finish" "work"])
                      [:config-path :print? :continue? :session-id :prompt])))
  (is (= {:no-session? true
          :prompt "--literal flag"}
         (select-keys (cli/parse-args ["--no-session" "--" "--literal" "flag"])
                      [:no-session? :prompt]))))

(deftest parse-loop-command-flags-test
  (is (= {:command "loop"
          :loop-prompt "fix bug"
          :loop-plan "PLAN.md"
          :loop-max 3
          :loop-run "clojure -M:test"
          :prompt ""}
         (select-keys (cli/parse-args ["loop"
                                       "--prompt" "fix bug"
                                       "--plan" "PLAN.md"
                                       "--max" "3"
                                       "--run" "clojure -M:test"])
                      [:command :loop-prompt :loop-plan :loop-max :loop-run :prompt]))))

(deftest loop-cli-runs-one-iteration-test
  (let [calls (atom [])]
    (with-redefs [system/create-system (fn [_] ::system)
                  system/create-session! (fn [_ title]
                                           (swap! calls conj [:create title])
                                           {:id "loop-session"})
                  system/complete! (fn [_ messages opts]
                                     (swap! calls conj [:complete (:content (first messages)) (:session-id opts)])
                                     ((:on-delta opts) "done")
                                     {:content "done"})
                  logging/log! (fn [& _] nil)]
      (is (= "done\n"
             (with-out-str
               (binding [*err* (java.io.StringWriter.)]
                 (cli/main ["loop" "--prompt" "fix bug" "--max" "1"])))))
      (is (= :create (ffirst @calls)))
      (is (str/includes? (second (second @calls)) "Loop Context"))
      (is (= "loop-session" (nth (second @calls) 2))))))

(deftest prompt-cli-creates-session-and-streams-test
  (let [calls (atom [])]
    (with-redefs [system/create-system (fn [_] ::system)
                  system/create-session! (fn [_ title]
                                           (swap! calls conj [:create title])
                                           {:id "new-session"})
                  system/complete! (fn [_ messages opts]
                                     (swap! calls conj [:complete messages (:session-id opts)])
                                     ((:on-delta opts) "streamed")
                                     {:content "final"})
                  logging/log! (fn [& _] nil)]
      (is (= "streamed\n"
             (with-out-str
               (cli/main ["-p" "first" "prompt"]))))
      (is (= [[:create "first prompt"]
              [:complete [{:role "user" :content "first prompt"}] "new-session"]]
             @calls)))))

(deftest prompt-cli-resumes-and-supports-ephemeral-runs-test
  (let [sessions [{:id "latest" :title "Latest"}
                  {:id "older" :title "Older"}]
        session-ids (atom [])]
    (with-redefs [system/create-system (fn [_] ::system)
                  system/list-sessions (fn [_] sessions)
                  system/create-session! (fn [& _]
                                           (throw (ex-info "unexpected create" {})))
                  system/complete! (fn [_ _ opts]
                                     (swap! session-ids conj (:session-id opts))
                                     ((:on-delta opts) "ok")
                                     {:content "ok"})
                  logging/log! (fn [& _] nil)]
      (is (= "ok\n"
             (with-out-str
               (cli/main ["-c" "continue"]))))
      (is (= "ok\n"
             (with-out-str
               (cli/main ["--no-session" "ephemeral"]))))
      (is (= ["latest" nil] @session-ids)))))

(deftest prompt-cli-picks-or-loads-session-test
  (let [sessions [{:id "session-a" :title "A"}
                  {:id "session-b" :title "B"}]
        session-ids (atom [])]
    (with-redefs [system/create-system (fn [_] ::system)
                  system/list-sessions (fn [_] sessions)
                  system/session-exists? (fn [_ session-id]
                                           (= "session-b" session-id))
                  system/complete! (fn [_ _ opts]
                                     (swap! session-ids conj (:session-id opts))
                                     ((:on-delta opts) "ok")
                                     {:content "ok"})
                  logging/log! (fn [& _] nil)]
      (binding [*err* (java.io.StringWriter.)]
        (is (= "ok\n"
               (with-in-str "2\n"
                 (with-out-str
                   (cli/main ["-r" "picked"]))))))
      (is (= "ok\n"
             (with-out-str
               (cli/main ["--session" "session-b" "loaded"]))))
      (is (= ["session-b" "session-b"] @session-ids)))))
