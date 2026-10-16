(ns agent.audit-regression-test
  (:require [agent.api.streaming :as streaming]
            [agent.chat :as chat]
            [agent.chat.test-support :as chat-test]
            [agent.api.ui-state :as ui-state]
            [agent.broker.core :as broker]
            [agent.broker.local :as local]
            [agent.chat.history :as history]
            [agent.chat.loop-control :as loop-control]
            [agent.chat.queue :as queue]
            [agent.chat.service :as service]
            [agent.chat.subscribers :as subscribers]
            [agent.chat.turn :as turn]
            [agent.config.defaults :as defaults]
            [agent.loop :as loop-support]
            [agent.memory.notes :as notes]
            [agent.memory.paths :as paths]
            [agent.memory.proposals :as proposals]
            [agent.memory.vault :as vault]
            [agent.persistence.sqlite :as sqlite]
            [agent.sessions.service :as sessions]
            [agent.telegram :as telegram]
            [agent.tools.common.http :as http-tool]
            [agent.tools.common.shell :as shell]
            [agent.tools.core :as tools]
            [agent.tools.shell-policy :as policy]
            [clj-http.client :as http]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as http-kit])
  (:import [java.io ByteArrayInputStream]
           [java.net InetAddress ServerSocket]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp [f]
  (let [root (.toFile (Files/createTempDirectory "iris-regression-" (make-array FileAttribute 0)))]
    (try (f root)
         (finally (doseq [file (reverse (file-seq root))] (io/delete-file file true))))))

(defn- public-resolver [_] [(InetAddress/getByName "93.184.216.34")])

(deftest shell-defaults-require-review-for-write-and-exec-options
  (with-temp
    (fn [root]
      (let [checks (atom 0)
            registry (tools/register-tool
                      (tools/create-registry {:approval-check (fn [_] (swap! checks inc) {:block true})})
                      (shell/create-shell-tool {:roots [(str root)] :working-dir (str root)}))]
        (doseq [argv [["find" "." "-exec" "touch" "marker" ";"]
                      ["sort" "-o" "output" "input"]
                      ["uniq" "input" "output"]
                      ["rg" "--pre" "sh" "pattern"]
                      ["git" "branch" "-D" "branch"]]]
          (is (thrown? clojure.lang.ExceptionInfo
                       (tools/execute-tool registry :shell {:argv argv} {:permissions #{:shell-exec}}))))
        (is (= 5 @checks))
        (is (= "ok" (:stdout (tools/execute-tool registry :shell {:argv ["printf" "ok"]}
                                                {:permissions #{:shell-exec}}))))
        (is (= policy/default-rules shell/default-rules (get-in defaults/default-config [:tools :shell :rules])))
        (let [custom (shell/create-shell-tool {:rules [{:argv ["find" "**"] :decision :allow}]})]
          (is (false? ((:sensitive-fn custom) {:argv ["find" "." "-print"]}))))))))

(deftest shell-output-limit-does-not-break-process
  (with-temp
    (fn [root]
      (let [tool (shell/create-shell-tool {:roots [(str root)] :working-dir (str root) :max-output-bytes 128})
            result (tools/execute tool {:argv ["/bin/bash" "-c"
                                               "for ((i=0;i<10000;i++)); do printf '01234567890123456789012345678901234567890123456789\\n'; done"]} {})]
        (is (zero? (:exit result)))
        (is (= 128 (count (:stdout result))))
        (is (true? (:stdout-truncated? result)))
        (is (false? (:stderr-truncated? result)))))))

(deftest redirect-strips-credentials-only-on-origin-change
  (doseq [[destination retain?] [["https://first.example/next" true]
                                ["https://first.example:443/next" true]
                                ["http://first.example/next" false]
                                ["https://first.example:8443/next" false]
                                ["https://other.example/next" false]]]
    (let [requests (atom []) closed (atom false)
          body (proxy [ByteArrayInputStream] [(byte-array 0)]
                 (close [] (reset! closed true)))
          tool (http-tool/create-http-tool {:resolve-host-fn public-resolver})]
      (with-redefs [http/request (fn [request]
                                  (swap! requests conj request)
                                  (if (= 1 (count @requests))
                                    {:status 302 :headers {"Location" destination} :body body}
                                    {:status 200 :headers {} :body "ok"}))]
        (tools/execute tool {:url "https://first.example/start"
                             :headers {"aUtHoRiZaTiOn" "test-token" "Cookie" "test-cookie" "X-Secret" "test-secret"
                                       "Accept" "text/plain"}} {}))
      (is @closed)
      (is (every? #(= :none (:redirect-strategy %)) @requests))
      (is (= retain? (contains? (:headers (second @requests)) "aUtHoRiZaTiOn")))
      (is (= retain? (contains? (:headers (second @requests)) "Cookie")))
      (is (= retain? (contains? (:headers (second @requests)) "X-Secret")))
      (is (= "text/plain" (get-in (second @requests) [:headers "Accept"]))))))

(deftest http-limit-bounds-reading-and-closes-body
  (let [read-bytes (atom 0) closed (atom false)
        body (proxy [ByteArrayInputStream] [(byte-array 100000)]
               (read [buffer offset length]
                 (let [n (proxy-super read buffer offset length)]
                   (when (pos? n) (swap! read-bytes + n)) n))
               (close [] (reset! closed true)))
        tool (http-tool/create-http-tool {:resolve-host-fn public-resolver :max-response-bytes 32})]
    (with-redefs [http/request (fn [request]
                                (is (= :stream (:as request)))
                                {:status 200 :headers {} :body body})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"max-response-bytes"
                            (tools/execute tool {:url "https://example.com"} {}))))
    (is (= 33 @read-bytes))
    (is @closed)))

(deftest cancellation-retains-owner-and-late-final-cannot-clear-new-stream
  (let [svc (service/create-service) system {:chat-service svc :event-sink identity}
        entered (promise) queued (promise) next-entered (promise)
        release (promise) release-next (promise)]
    (try
      (with-redefs [history/activate-queued-message! (fn [& _])
                    history/persist-queued-user-turn! (fn [& _] (deliver queued true) {:id 1})
                    turn/run-turn! (fn [_ opts]
                                     (if (= "old" (:request-id opts))
                                       (do (deliver entered true) @release)
                                       (do (deliver next-entered true) @release-next))
                                     {:content "done"})]
        (let [old (future (queue/run! system {:session-id "s" :request-id "old" :messages []}))]
          (is (= true (deref entered 3000 :timeout)))
          (service/cancel-session! system "s")
          (let [new (future (queue/run! system {:session-id "s" :request-id "new" :messages []}))]
            (is (= true (deref queued 3000 :timeout)))
            (is (not (realized? next-entered)))
            (deliver release true)
            (is (= true (deref next-entered 3000 :timeout)))
            (reset! (:streaming-state svc) {"s" {:request-id "new" :content "new answer"}})
            ((subscribers/streaming-subscriber system "s" nil)
             {:event-type :message-end :request-id "old" :payload {:final? true}})
            (is (= "new answer" (:content (service/streaming-state system "s"))))
            (deliver release-next true)
            (is (= "done" (:content (deref old 3000 {}))))
            (is (= "done" (:content (deref new 3000 {})))))))
      (finally (deliver release true) (deliver release-next true) (service/stop! svc)))))

(deftest failed-notification-does-not-prevent-worker-start
  (let [svc (service/create-service)
        system {:chat-service svc :event-sink (fn [_] (throw (ex-info "notification failure" {})))}]
    (try
      (with-redefs [history/activate-queued-message! (fn [& _])
                    turn/run-turn! (fn [& _] {:content "done"})]
        (is (= "done" (:content (queue/run! system {:session-id "s" :messages []})))))
      (finally (service/stop! svc)))))

(deftest rejected-submission-cleans-session-and-promise
  (let [svc (service/create-service) system {:chat-service svc :event-sink identity}]
    (try
      (.shutdown (:turn-executor svc))
      (is (thrown? Exception (queue/run! system {:session-id "s" :messages []})))
      (is (empty? @(:session-runtimes svc)))
      (finally (service/stop! svc)))))

(deftest required-persistence-fails-before-success-publication
  (let [events (atom [])
        sink (subscribers/loop-event-sink {:event-sink #(swap! events conj %)}
                                        [{:operation :persistence :required? true
                                          :f (fn [_] (throw (ex-info "db failed" {})))}])]
    (is (= :chat-persistence-failed
           (try (sink {:event-type :message-end :payload {:final? true}})
                (catch Exception e (:type (ex-data e))))))
    (is (empty? @events))))

(deftest memory-audit-failure-does-not-undo-applied-file
  (doseq [fail-at [:audit :decision]]
    (with-temp
      (fn [root]
        (let [path (str (io/file root "note.md")) before "before" after "after"
              record (atom {:id "p" :status "pending" :operation "note-update" :target-path path
                            :base-revision (vault/content-revision before)
                            :proposed-revision (vault/content-revision after) :proposed-content after})]
          (spit path before)
          (with-redefs [proposals/get-memory-note-update (fn [& _] @record)
                        paths/ensure-vault-path! (fn [_ p] p)
                        notes/reindex-vault! (fn [_] {:ok? true})
                        sqlite/update-memory-note-update-status!
                        (fn [_ _ _ status _ _]
                          (if (= :decision fail-at) (throw (ex-info "decision failed" {}))
                              (swap! record assoc :status (name status))))
                        sqlite/log-event! (fn [& _] (throw (ex-info "audit failed" {})))]
            (is (thrown? Exception (proposals/apply-memory-note-update! {:update-lock (Object.)} "p" "test"))))
          (is (= (if (= :audit fail-at) "applied" "pending") (:status @record)))
          (is (= (if (= :audit fail-at) after before) (slurp path))))))))

(deftest broker-overflow-is-counted-and-signalled
  (doseq [strategy [:sliding :dropping]]
    (let [b (local/create-broker) sub (broker/subscribe! b "test" {:buffer-size 2 :buffer-strategy strategy})]
      (try
        (doseq [i (range 5)] (broker/publish! b {:subject "test" :payload i}))
        (is (= 3 @(:dropped-count sub)))
        (let [first-message (async/poll! (:channel sub))]
          (is (= 3 (:broker/dropped-count first-message)))
          (is (= (if (= :sliding strategy) 3 0) (:payload first-message))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"overflow"
                              (streaming/take! {:channel nil :open? (atom true)} (:channel sub))))
        (finally (broker/unsubscribe! b sub))))))

(deftest loop-and-ui-state-are-owned-and-cleared
  (let [a (service/create-service) b (service/create-service)
        ui-a (ui-state/create-state) ui-b (ui-state/create-state) closed (atom [])]
    (try
      (loop-support/start! (:loop-state a) "same-id" {} "task")
      (is (loop-support/active? (:loop-state a) "same-id"))
      (is (not (loop-support/active? (:loop-state b) "same-id")))
      (reset! (:session-streams ui-a) {"client" :stream})
      (is (empty? @(:session-streams ui-b)))
      (with-redefs [streaming/close! #(swap! closed conj %)] (ui-state/close! ui-a))
      (is (= [:stream] @closed))
      (is (empty? @(:session-streams ui-a)))
      (service/stop! a)
      (is (not (loop-support/active? (:loop-state a) "same-id")))
      (finally (service/stop! a) (service/stop! b)))))

(deftest bounded-queue-rejects-before-persisting-overload
  (let [svc (service/create-service {:max-pending-turns 1})
        system {:chat-service svc :event-sink identity}
        entered (promise) release (promise) persisted (atom 0)]
    (try
      (with-redefs [history/activate-queued-message! (fn [& _])
                    history/persist-queued-user-turn! (fn [& _] (swap! persisted inc))
                    turn/run-turn! (fn [& _] (deliver entered true) @release {:content "done"})]
        (let [running (future (queue/run! system {:session-id "s" :messages []}))]
          (is (= true (deref entered 3000 :timeout)))
          (is (= :chat-overloaded
                 (try (queue/run! system {:session-id "s" :messages []})
                      (catch Exception e (:type (ex-data e))))))
          (is (zero? @persisted))
          (deliver release true)
          (is (= "done" (:content (deref running 3000 {}))))))
      (finally (deliver release true) (service/stop! svc)))))

(deftest rejected-loop-worker-does-not-leave-active-state
  (let [svc (service/create-service)
        system {:chat-service svc :event-sink identity}]
    (try
      (loop-support/start! (:loop-state svc) "loop" {} "task")
      (with-redefs [service/submit-loop! (fn [& _] (throw (ex-info "full" {:type :chat-overloaded})))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (loop-control/start-loop-worker! system "loop" identity))))
      (is (not (loop-support/active? (:loop-state svc) "loop")))
      (is (empty? @(:loop-workers svc)))
      (finally (service/stop! svc)))))

(deftest actual-http-redirect-cannot-bypass-origin-check
  (let [port (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))
        received (atom nil)
        server (http-kit/run-server
                (fn [request]
                  (if (= "/start" (:uri request))
                    {:status 302 :headers {"Location" (str "http://localhost:" port "/target")} :body "redirect"}
                    (do (reset! received (:headers request)) {:status 200 :body "ok"})))
                {:ip "127.0.0.1" :port port})
        tool (http-tool/create-http-tool {:allow-private? true
                                          :resolve-host-fn (fn [_] [(InetAddress/getByName "127.0.0.1")])})]
    (try
      (is (= "ok" (:body (tools/execute tool {:url (str "http://127.0.0.1:" port "/start")
                                              :headers {"Authorization" "test-token" "Cookie" "test-cookie"}} {}))))
      (is (nil? (get @received "authorization")))
      (is (nil? (get @received "cookie")))
      (finally (server :timeout 100)))))

(deftest persistence-failure-never-invokes-model-fallback
  (with-temp
    (fn [root]
      (let [requests (atom [])
            provider (chat-test/->PlannerProvider (atom ["first" "must not be requested"]) requests)
            system (chat-test/test-system (str (io/file root "test.db")) provider identity)
            session (sessions/create-session! system "audit")]
        (try
          (with-redefs [history/persist-completion! (fn [& _] (throw (ex-info "disk failure" {})))]
            (is (= :chat-persistence-failed
                   (try (turn/run-turn! system {:session-id (:id session) :messages [{:role "user" :content "hello"}]})
                        (catch Exception e (:type (ex-data e)))))))
          (is (= 1 (count @requests)))
          (finally (service/stop! (:chat-service system)) (sqlite/close-store! (:store system))))))))

(deftest telegram-finishes-when-terminal-events-are-missing
  (let [b (local/create-broker) finalized (atom [])]
    (with-redefs [chat/run! (fn [& _] {:content "authoritative final"})]
      (let [run-events #'telegram/run-chat-events!
            result (future (run-events {:event-bus b} {} {} 1 "s" []
                                      {:finalize! #(swap! finalized conj %)} {} (atom false)))]
        (try
          (is (= "authoritative final" (:content (deref result 3000 {}))))
          (is (= ["authoritative final"] @finalized))
          (is (zero? (:subscription_count (broker/health-check b))))
          (finally (future-cancel result)))))))

(deftest bounded-executor-limits-running-and-pending-work
  (let [svc (service/create-service {:max-concurrent-turns 1 :max-pending-turns 1})
        entered (promise) release (promise) second-entered (promise)]
    (try
      (let [first-task (service/submit! svc #(do (deliver entered true) @release))]
        (is (= true (deref entered 3000 :timeout)))
        (let [second-task (service/submit! svc #(deliver second-entered true))]
          (is (not (realized? second-entered)))
          (is (= :chat-overloaded (try (service/submit! svc (constantly nil))
                                      (catch Exception e (:type (ex-data e))))))
          (deliver release true)
          (.get first-task)
          (.get second-task)
          (is (realized? second-entered))))
      (finally (deliver release true) (service/stop! svc)))))
