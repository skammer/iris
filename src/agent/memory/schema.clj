(ns agent.memory.schema
  (:require
   [clojure.string :as str]))

(def allowed-scope-types #{"global" "session" "agent"})

(defn non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn normalize-scope
  [{:keys [type id] :as scope}]
  (let [type* (name (or type :global))]
    (when-not (allowed-scope-types type*)
      (throw (ex-info "scope type must be global, session, or agent"
                      {:type :invalid-memory-scope
                       :scope scope})))
    {:type type*
     :id (when-not (= "global" type*) id)}))

(defn normalize-scope-option
  [{:keys [scope scope-type scope-id session-id agent-id]}]
  (normalize-scope
   (or scope
       (cond
         session-id {:type :session :id session-id}
         agent-id {:type :agent :id agent-id}
         scope-type {:type scope-type :id scope-id}
         :else {:type :global}))))

(defn validate-fact!
  [{:keys [subject predicate object confidence] :as fact}]
  (doseq [[field value] {:subject subject
                         :predicate predicate
                         :object object}]
    (when-not (non-blank-string? value)
      (throw (ex-info "memory fact fields must be non-blank strings"
                      {:type :invalid-memory-fact
                       :field field
                       :fact fact}))))
  (when (and (some? confidence)
             (or (not (number? confidence))
                 (neg? (double confidence))
                 (> (double confidence) 1.0)))
    (throw (ex-info "memory fact confidence must be a number from 0.0 to 1.0"
                    {:type :invalid-memory-fact
                     :field :confidence
                     :fact fact})))
  fact)

(defn validate-fact-selector!
  [{:keys [id subject predicate object] :as selector}]
  (when-not (or (non-blank-string? id)
                (and (non-blank-string? subject)
                     (non-blank-string? predicate)
                     (non-blank-string? object)))
    (throw (ex-info "provide fact id or exact subject, predicate, and object"
                    {:type :invalid-memory-fact-selector
                     :selector selector})))
  selector)
