(ns agent.runtime.turn-policy
  "Pure normalization and execution decisions for a planned turn."
  (:require [agent.runtime.messages :as runtime-messages]))

(defn- directive-tool-name [directive]
  (keyword (get-in directive [:payload :tool-name])))

(defn- respond-directive? [directive]
  (and (= :tool-call (keyword (:type directive)))
       (= :respond (directive-tool-name directive))))

(defn- respond-content [directive]
  (or (get-in directive [:payload :input :content])
      (get-in directive [:payload :input "content"])
      ""))

(defn strip-respond-when-mixed [step llm-response]
  (let [directives (vec (:directives step))
        respond-only? (and (seq directives) (every? respond-directive? directives))
        mixed? (and (some respond-directive? directives)
                    (some #(and (= :tool-call (keyword (:type %)))
                                (not (respond-directive? %)))
                          directives))]
    (cond
      respond-only?
      (let [content (respond-content (first directives))]
        (-> step
            (assoc :directives [{:type :complete
                                 :payload {:result content}}])
            (assoc :llm-response (assoc llm-response
                                        :content content
                                        :tool-calls []
                                        :synthetic-respond? true))))

      mixed?
      (let [keep-directives (remove respond-directive? directives)
            keep-tools (remove #(= :respond (keyword (:name (runtime-messages/normalize-tool-call-block "respond" 0 %))))
                               (:tool-calls llm-response))]
        (-> step
            (assoc :directives (vec keep-directives))
            (assoc :llm-response (assoc llm-response :tool-calls (vec keep-tools)))))

      :else step)))

(defn attach-allowed-tools [step allowed-tools]
  (if (some? allowed-tools)
    (update step :directives
            (fn [directives]
              (mapv (fn [directive]
                      (if (= :tool-call (keyword (:type directive)))
                        (update-in directive [:payload :context] merge {:allowed-tools allowed-tools})
                        directive))
                    directives)))
    step))

(defn next-action [{:keys [submission pre-verdict max-token-terminal?]}]
  (cond
    submission :submission
    (= :retry (:action pre-verdict)) :retry
    (= :fatal (:action pre-verdict)) :fatal
    max-token-terminal? :max-tokens
    :else :execute))
