(ns agent.chat.lifecycle-test
  (:require
   [agent.chat.test-support :refer [->BlockingProvider ->PlannerProvider dialogue-texts eventually message-text temp-db-path test-system tool-call-response]]
   [agent.chat :as chat]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.loop :as loop]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.loop :as runtime-loop]
   [agent.sessions.service :as sessions]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest chat-blocks-normal-message-while-loop-active-test
  (let [path (temp-db-path)
        responses (atom ["unexpected"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "loop-block")]
    (try
      (loop/start! (get-in system [:chat-service :loop-state]) (:id session) (:config system) "work plan")
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hello"}]})
            messages (sqlite/list-messages (:store system) (:id session))]
        (is (= "Loop active. Use /loop status or /loop stop." (:content result)))
        (is (empty? @requests))
        (is (= ["hello" "Loop active. Use /loop status or /loop stop."]
               (mapv :content messages))))
      (finally
        (loop/stop! (get-in system [:chat-service :loop-state]) (:id session))
        (io/delete-file path true)))))

(deftest chat-loop-slash-starts-worker-and-stops-at-max-test
  (let [path (temp-db-path)
        plan (java.io.File/createTempFile "iris-loop-chat-" ".md")
        responses (atom ["iteration done src/agent/loop.clj"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider
                            #(-> %
                                 (assoc :loop {:max-iterations 1
                                               :plan-file (.getAbsolutePath plan)
                                               :summary-max-chars 400
                                               :validation-max-chars 400})
                                 (assoc-in [:memory :notes :extractor :enabled] false)))
        session (sessions/create-session! system "loop-start")]
    (try
      (spit plan "- [ ] one")
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "/loop fix bug"}]})]
        (is (str/starts-with? (:content result) "Loop started: LOOP 0/1"))
        (is (eventually #(some (fn [message]
                                 (str/includes? (:content message) "Loop complete: LOOP 1/1"))
                               (sqlite/list-messages (:store system) (:id session)))))
        (is (= 1 (count @requests)))
        (is (some #(str/includes? (message-text %) "Loop Context")
                  (get-in (first @requests) [:request :messages]))))
      (finally
        (loop/stop! (get-in system [:chat-service :loop-state]) (:id session))
        (io/delete-file plan true)
        (io/delete-file path true)))))

(deftest chat-loop-uses-configured-max-steps-test
  (let [path (temp-db-path)
        responses (atom [(tool-call-response :fs_list {:path "."})
                         "listed"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(-> %
                                               (assoc-in [:chat :max-steps] 1)
                                               (assoc-in [:memory :notes :extractor :enabled] false)))
        session (sessions/create-session! system "max-steps")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "list files"}]})]
        (is (= "Stopped: max chat tool steps reached." (:content result)))
        (is (= 1 (count @requests))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-can-cancel-active-session-test
  (let [path (temp-db-path)
        started (promise)
        response (promise)
        provider (->BlockingProvider started response)
        system (test-system path provider #(assoc-in % [:memory :notes :extractor :enabled] false))
        session (sessions/create-session! system "cancel")]
    (try
      (let [result-f (future
                       (chat/run! system {:session-id (:id session)
                                          :messages [{:role "user" :content "wait"}]}))]
        (is (true? (deref started 1000 false)))
        (is (:cancelled-active? (chat/cancel-session! system (:id session))))
        (deliver response "late answer")
        (is (= chat/stopped-content (:content (deref result-f 1000 nil))))
        (is (eventually #(false? (:working? (chat/session-state system (:id session))))))
        (is (= ["wait" chat/stopped-content]
               (mapv :content (sqlite/list-messages (:store system) (:id session))))))
      (finally
        (io/delete-file path true)))))

(deftest chat-service-stop-cancels-active-run-test
  (let [path (temp-db-path)
        started (promise)
        response (promise)
        provider (->BlockingProvider started response)
        system (test-system path provider #(assoc-in % [:memory :notes :extractor :enabled] false))
        session (sessions/create-session! system "service-stop")]
    (try
      (let [result-f (future
                       (chat/run! system {:session-id (:id session)
                                          :messages [{:role "user" :content "wait"}]}))]
        (is (true? (deref started 1000 false)))
        (is (= {:stopped true} (chat/stop! (:chat-service system))))
        (is (= chat/stopped-content (:content (deref result-f 1000 nil))))
        (is (= 0 (:active-session-count (chat/health-check (:chat-service system)))))
        (is (= 0 (:queued-count (chat/health-check (:chat-service system))))))
      (finally
        (deliver response "late answer")
        (io/delete-file path true)))))

(deftest chat-service-state-is-system-local-test
  (let [path-a (temp-db-path)
        path-b (temp-db-path)
        release (promise)
        entered (promise)
        provider-a (->PlannerProvider (atom ["unused"]) (atom []))
        provider-b (->PlannerProvider (atom ["unused"]) (atom []))
        system-a (test-system path-a provider-a #(assoc-in % [:memory :notes :extractor :enabled] false))
        system-b (test-system path-b provider-b #(assoc-in % [:memory :notes :extractor :enabled] false))
        session-a (sessions/create-session! system-a "a")]
    (try
      (with-redefs [runtime-loop/run!
                    (fn [{:keys [event-sink session-id request-id]}]
                      (event-sink {:event-type "message-update"
                                   :entity-type "session"
                                   :entity-id session-id
                                   :request-id request-id
                                   :payload {:delta "partial"}})
                      (deliver entered true)
                      @release
                      (event-sink {:event-type "message-end"
                                   :entity-type "session"
                                   :entity-id session-id
                                   :request-id request-id
                                   :payload {:role "assistant"
                                             :content "done"
                                             :final? true}})
                      {:content "done"
                       :stop-reason :completed})]
        (let [result-f (future
                         (chat/run! system-a {:session-id (:id session-a)
                                              :messages [{:role "user" :content "go"}]
                                              :stream? true}))]
          (is (true? (deref entered 1000 false)))
          (is (eventually #(:working? (chat/session-state system-a (:id session-a)))))
          (is (false? (:working? (chat/session-state system-b (:id session-a)))))
          (is (eventually #(= "partial"
                              (:content (chat/streaming-state system-a (:id session-a))))))
          (is (nil? (chat/streaming-state system-b (:id session-a))))
          (deliver release true)
          (is (= "done" (:content (deref result-f 1000 nil))))
          (is (eventually #(false? (:working? (chat/session-state system-a (:id session-a))))))
          (is (nil? (chat/streaming-state system-a (:id session-a))))))
      (finally
        (deliver release true)
        (io/delete-file path-a true)
        (io/delete-file path-b true)))))

(deftest chat-loop-queues-rapid-sequential-session-messages-test
  (let [path (temp-db-path)
        first-response (promise)
        responses (atom [first-response "second answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :notes :extractor :enabled] false))
        session (sessions/create-session! system "queue")]
    (try
      (let [first-f (future
                      (chat/run! system {:session-id (:id session)
                                         :messages [{:role "user" :content "first"}]}))]
        (is (eventually #(= 1 (count @requests))))
        (let [second-f (future
                         (chat/run! system {:session-id (:id session)
                                            :messages [{:role "user" :content "second"}]}))]
          (is (eventually #(= 2 (count (sqlite/list-messages (:store system) (:id session))))))
          (let [queued (second (sqlite/list-messages (:store system) (:id session)))]
            (is (= "second" (:content queued)))
            (is (true? (get-in queued [:metadata :queued])))
            (is (true? (:excluded-from-context? queued))))
          (deliver first-response "first answer")
          (is (= "first answer" (:content (deref first-f 2000 nil))))
          (is (= "second answer" (:content (deref second-f 2000 nil))))
          (is (= ["first" "first answer" "second" "second answer"]
                 (mapv :content (sqlite/list-messages (:store system) (:id session)))))
          (is (not-any? :excluded-from-context?
                        (sqlite/list-messages (:store system) (:id session))))
          (let [second-request (get-in (second @requests) [:request :messages])]
            (is (= ["first" "first answer" "second"] (dialogue-texts second-request))))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-queues-during-tool-execution-test
  (let [path (temp-db-path)
        release-tool (promise)
        tool-started (promise)
        responses (atom [(tool-call-response :fs_list {:path "."})
                         "first done"
                         "second done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :notes :extractor :enabled] false))
        session (sessions/create-session! system "tool-queue")]
    (try
      (with-redefs [kernel-runtime/execute-step!
                    (fn [_ _ step _]
                      (deliver tool-started true)
                      @release-tool
                      (assoc step
                             :receipts (mapv (fn [directive]
                                               (case (:type directive)
                                                 :complete {:directive :complete
                                                            :status :completed
                                                            :result (get-in directive [:payload :result])}
                                                 :tool-call {:directive :tool-call
                                                             :status :ok
                                                             :tool-name :fs_list
                                                             :tool-call-id "call_fs"
                                                             :input {:path "."}
                                                             :result "listed"}))
                                             (:directives step))))]
        (let [first-f (future
                        (chat/run! system {:session-id (:id session)
                                           :messages [{:role "user" :content "first"}]}))]
          (is (true? (deref tool-started 1000 false)))
          (let [second-f (future
                           (chat/run! system {:session-id (:id session)
                                              :messages [{:role "user" :content "second"}]}))]
            (is (eventually #(some (fn [m]
                                      (and (= "second" (:content m))
                                           (:excluded-from-context? m)))
                                    (sqlite/list-messages (:store system) (:id session)))))
            (deliver release-tool true)
            (is (= "first done" (:content (deref first-f 2000 nil))))
            (is (= "second done" (:content (deref second-f 2000 nil))))
            (let [during-tool-request (get-in (second @requests) [:request :messages])
                  queued-request (get-in (nth @requests 2) [:request :messages])]
              (is (not-any? #(= "second" (message-text %)) during-tool-request))
              (is (some #(= "second" (message-text %)) queued-request))))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-cancellation-drains-queued-turn-test
  (let [path (temp-db-path)
        first-response (promise)
        responses (atom [first-response "after cancel answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :notes :extractor :enabled] false))
        session (sessions/create-session! system "cancel-queue")]
    (try
      (let [first-f (future
                      (chat/run! system {:session-id (:id session)
                                         :messages [{:role "user" :content "wait"}]}))]
        (is (eventually #(= 1 (count @requests))))
        (let [second-f (future
                         (chat/run! system {:session-id (:id session)
                                            :messages [{:role "user" :content "after cancel"}]}))]
          (is (eventually #(= 2 (count (sqlite/list-messages (:store system) (:id session))))))
          (let [cancelled (chat/cancel-session! system (:id session))]
            (is (:cancelled-active? cancelled))
            (is (= 1 (:cleared-queued-count cancelled))))
          (deliver first-response "late answer")
          (is (= chat/stopped-content (:content (deref first-f 2000 nil))))
          (is (= chat/stopped-content (:content (deref second-f 2000 nil))))
          (is (= ["wait" "after cancel" chat/stopped-content]
                 (mapv :content (sqlite/list-messages (:store system) (:id session)))))))
      (finally
        (io/delete-file path true)))))
