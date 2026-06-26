(ns agent.cli-test
  (:require
	   [agent.chat :as chat]
	   [agent.cli :as cli]
	   [agent.cli.render :as cli-render]
	   [agent.logging :as logging]
	   [agent.nrepl :as nrepl]
   [agent.sessions.service :as sessions]
   [agent.system :as system]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

;; Exact-stdout assertions below depend on the CLI renderer staying in raw
;; passthrough, regardless of whether the test JVM has an interactive console.
(use-fixtures :each
  (fn [f]
    (with-redefs [cli-render/tty? (constantly false)]
      (f))))

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

(deftest one-shot-cli-closes-system-test
  (let [closed (atom [])]
    (with-redefs [system/create-system (fn [_] {:id :prompt})
                  system/close-system! #(swap! closed conj (:id %))
                  sessions/create-session! (fn [_ _] {:id "new-session"})
                  chat/run! (fn [_ _] {:content "ok"})
                  logging/log! (fn [& _] nil)]
      (is (= "ok\n"
             (with-out-str
               (cli/main ["prompt"])))))
    (is (= [:prompt] @closed))))

(deftest one-shot-cli-closes-system-on-error-test
  (let [closed (atom [])
        error (ex-info "boom" {:type :test})]
    (with-redefs [system/create-system (fn [_] {:id :prompt})
                  system/close-system! #(swap! closed conj (:id %))
                  sessions/create-session! (fn [_ _] {:id "new-session"})
                  chat/run! (fn [_ _] (throw error))
                  logging/log! (fn [& _] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"boom"
                            (with-out-str
                              (cli/main ["prompt"])))))
    (is (= [:prompt] @closed))))

(deftest serve-cli-registers-idempotent-shutdown-hook-test
  (let [hooks (atom [])
        stopped (atom [])
        closed (atom [])
        started-system {:id :serve
                        :config {:api {:host "127.0.0.1"
                                       :port 8787}
                                 :nrepl {:enabled true}}}
        api-system (assoc started-system :api-server ::api)
        nrepl-server {:server ::nrepl
                      :bind "127.0.0.1"
                      :port 8999
                      :port-file ".test-nrepl-port"}]
    (with-redefs [system/create-system (fn [_] started-system)
                  system/start-api! (fn [system] (assoc system :api-server ::api))
                  system/close-system! #(swap! closed conj %)
                  nrepl/start! (fn [_ _] nrepl-server)
                  nrepl/stop! #(swap! stopped conj %)
                  logging/log! (fn [& _] nil)]
      (binding [cli/*add-shutdown-hook!* #(swap! hooks conj %)
                cli/*serve-block!* (fn [] nil)]
        (is (str/includes?
             (with-out-str
               (cli/main ["serve"]))
             "API listening on http://127.0.0.1:8787")))
      (is (= 1 (count @hooks)))
      (.run ^Thread (first @hooks))
      (.run ^Thread (first @hooks))
      (is (= [nrepl-server] @stopped))
      (is (= [api-system] @closed)))))

(deftest loop-cli-runs-one-iteration-test
  (let [calls (atom [])]
    (with-redefs [system/create-system (fn [_] ::system)
                  sessions/create-session! (fn [_ title]
                                           (swap! calls conj [:create title])
                                           {:id "loop-session"})
                  chat/run! (fn [_ opts]
                              (swap! calls conj [:complete (:content (first (:messages opts))) (:session-id opts)])
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

(deftest loop-cli-uses-configured-max-and-rejects-invalid-max-test
  (let [calls (atom [])]
    (with-redefs [system/create-system (fn [_] {:config {:loop {:max-iterations 1}}})
                  sessions/create-session! (fn [_ title]
                                           (swap! calls conj [:create title])
                                           {:id "loop-session"})
                  chat/run! (fn [_ opts]
                              (swap! calls conj [:complete (:content (first (:messages opts))) (:session-id opts)])
                              {:content "done"})
                  logging/log! (fn [& _] nil)]
      (is (= "done\n"
             (with-out-str
               (binding [*err* (java.io.StringWriter.)]
                 (cli/main ["loop" "--prompt" "fix bug"])))))
      (is (= 1 (count (filter #(= :complete (first %)) @calls))))))
  (with-redefs [system/create-system (fn [_] {:config {}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"loop max iterations must be positive"
                          (cli/main ["loop" "--prompt" "fix bug" "--max" "0"])))))

(deftest prompt-cli-creates-session-and-streams-test
  (let [calls (atom [])]
    (with-redefs [system/create-system (fn [_] ::system)
                  sessions/create-session! (fn [_ title]
                                           (swap! calls conj [:create title])
                                           {:id "new-session"})
                  chat/run! (fn [_ opts]
                              (swap! calls conj [:complete (:messages opts) (:session-id opts)])
                              ((:on-delta opts) "streamed")
                              {:content "final"})
                  logging/log! (fn [& _] nil)]
      (is (= "streamed\n"
             (with-out-str
               (cli/main ["-p" "first" "prompt"]))))
      (is (= [[:create nil]
              [:complete [{:role "user" :content "first prompt"}] "new-session"]]
             @calls)))))

(deftest prompt-cli-resumes-and-supports-ephemeral-runs-test
  (let [sessions [{:id "latest" :title "Latest"}
                  {:id "older" :title "Older"}]
        session-ids (atom [])]
    (with-redefs [system/create-system (fn [_] ::system)
                  sessions/list-sessions (fn [_] sessions)
                  sessions/create-session! (fn [& _]
                                           (throw (ex-info "unexpected create" {})))
                  chat/run! (fn [_ opts]
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
                  sessions/list-sessions (fn [_] sessions)
                  sessions/session-exists? (fn [_ session-id]
                                           (= "session-b" session-id))
                  chat/run! (fn [_ opts]
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
