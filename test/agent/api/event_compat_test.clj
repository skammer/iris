(ns agent.api.event-compat-test
  (:require
   [agent.api.event-compat :as event-compat]
   [clojure.test :refer [deftest is]]))

(deftest canonicalizes-historical-chat-events-test
  (is (= "agent-start"
         (event-compat/canonical-event-type "chat.started")))
  (is (= "message-end"
         (event-compat/canonical-event-type :completion.completed)))
  (is (= "tool-execution-start"
         (event-compat/canonical-event-type "tool.execution.requested")))
  (is (= {:event-type "tool-execution-end"
          :entity-type "session"
          :entity-id "s1"
          :request-id "r1"
          :payload {:status :ok}}
         (event-compat/canonicalize-event
          {:event-type "tool.execution.succeeded"
           :entity-type "session"
           :entity-id "s1"
           :request-id "r1"
           :payload {:status :ok}}))))

(deftest leaves-current-events-unchanged-test
  (is (= {:event-type "message-update" :payload {:delta "x"}}
         (event-compat/canonicalize-event
          {:event-type "message-update" :payload {:delta "x"}}))))
