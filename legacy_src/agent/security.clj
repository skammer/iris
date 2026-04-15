(ns agent.security
  "Security hardening for the agent system.
  Implements input validation, authentication, and secure execution."
  (:require
   [clojure.string :as str]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.core.async :as async])
  (:import
   (java.security MessageDigest)
   (java.util Base64)
   (javax.crypto Cipher KeyGenerator)
   (javax.crypto.spec SecretKeySpec IvParameterSpec)))

;; ======================
;; Security Protocols
;; ======================

(defprotocol IInputValidator
  "Protocol for input validation and sanitization."
  (validate-input [this input context]
    "Validate input for given context. Returns validated input or throws.")
  (sanitize-input [this input]
    "Sanitize input to remove potentially dangerous content.")
  (check-permissions [this input user-context]
    "Check if user has permission to perform action with input."))

(defprotocol IAuthentication
  "Protocol for authentication and authorization."
  (authenticate [this credentials]
    "Authenticate user with credentials. Returns user context or nil.")
  (authorize [this user-context action resource]
    "Check if user is authorized to perform action on resource.")
  (generate-token [this user-context]
    "Generate authentication token for user.")
  (validate-token [this token]
    "Validate authentication token. Returns user context or nil."))

(defprotocol ISecureExecution
  "Protocol for secure tool and code execution."
  (create-sandbox [this execution-context]
    "Create secure sandbox for execution.")
  (execute-in-sandbox [this sandbox code]
    "Execute code in sandbox. Returns result or error.")
  (cleanup-sandbox [this sandbox]
    "Clean up sandbox resources."))

(defprotocol IAuditLogger
  "Protocol for security audit logging."
  (log-security-event [this event-type details]
    "Log security event.")
  (get-audit-trail [this filters]
    "Get audit trail with filters."))

;; ======================
;; Input Validation
;; ======================

(defrecord BasicInputValidator [allowed-patterns blocked-patterns]
  IInputValidator
  (validate-input [this input context]
    (let [input-str (str input)]
      ;; Check for blocked patterns
      (doseq [pattern blocked-patterns]
        (when (re-find pattern input-str)
          (throw (ex-info "Input contains blocked pattern"
                          {:pattern pattern :input input-str}))))
      
      ;; Check for allowed patterns if specified
      (when (seq allowed-patterns)
        (when-not (some #(re-find % input-str) allowed-patterns)
          (throw (ex-info "Input doesn't match allowed patterns"
                          {:input input-str}))))
      
      input))
  
  (sanitize-input [this input]
    (let [input-str (str input)]
      ;; Remove potentially dangerous characters
      (-> input-str
          (str/replace #"<script.*?>.*?</script>" "") ; Remove script tags
          (str/replace #"javascript:" "") ; Remove javascript: URLs
          (str/replace #"on\w+\s*=" "") ; Remove event handlers
          (str/replace #"[<>\"']" "") ; Remove HTML special chars
          str/trim)))
  
  (check-permissions [this input user-context]
    (let [user-roles (:roles user-context #{})
          required-roles (:required-roles context #{})]
      (when (seq required-roles)
        (when-not (seq (set/intersection user-roles required-roles))
          (throw (ex-info "Insufficient permissions"
                          {:user-roles user-roles
                           :required-roles required-roles})))))))

;; ======================
;; Authentication
;; ======================

(defrecord SimpleAuthenticator [users token-secret]
  IAuthentication
  (authenticate [this credentials]
    (let [username (:username credentials)
          password (:password credentials)
          user (get users username)]
      (when (and user (= password (:password user)))
        (dissoc user :password))))
  
  (authorize [this user-context action resource]
    (let [user-roles (:roles user-context #{})
          required-roles (get-in resource [:permissions action] #{})]
      (seq (set/intersection user-roles required-roles))))
  
  (generate-token [this user-context]
    (let [payload (assoc user-context :exp (+ (System/currentTimeMillis) (* 24 60 60 1000))) ; 24 hours
          payload-str (pr-str payload)
          md (MessageDigest/getInstance "SHA-256")]
      (.update md (.getBytes payload-str))
      (.update md (.getBytes token-secret))
      (let [digest (.digest md)]
        (-> (Base64/getEncoder)
            (.encodeToString digest)))))
  
  (validate-token [this token]
    ;; Simplified validation - in production use JWT or similar
    (when-let [user (get-in this [:tokens token])]
      (when (> (:exp user) (System/currentTimeMillis))
        user))))

;; ======================
;; Secure Execution Sandbox
;; ======================

(defrecord BasicSandbox [timeout-ms memory-limit]
  ISecureExecution
  (create-sandbox [this execution-context]
    {:id (str (java.util.UUID/randomUUID))
     :created-at (System/currentTimeMillis)
     :timeout timeout-ms
     :memory-limit memory-limit
     :context execution-context})
  
  (execute-in-sandbox [this sandbox code]
    (let [ch (async/chan)
          timeout-ch (async/timeout (:timeout sandbox))]
      (async/go
        (try
          ;; In real implementation, would use actual sandboxing
          ;; For now, just evaluate with limited permissions
          (let [result (binding [*read-eval* false] ; Disable eval in read
                         (eval `(do ~code)))]
            (async/>! ch {:success true :result result}))
          (catch Exception e
            (async/>! ch {:success false :error (.getMessage e)}))))
      
      (async/go
        (let [[v ch] (async/alts! [ch timeout-ch])]
          (if (= ch timeout-ch)
            {:success false :error "Execution timeout"}
            v))))
    
    ;; Simplified synchronous version for now
    (try
      (let [result (binding [*read-eval* false]
                     (eval code))]
        {:success true :result result})
      (catch Exception e
        {:success false :error (.getMessage e)})))
  
  (cleanup-sandbox [this sandbox]
    ;; Clean up resources
    nil))

;; ======================
;; Audit Logging
;; ======================

(defrecord FileAuditLogger [log-file]
  IAuditLogger
  (log-security-event [this event-type details]
    (let [entry {:timestamp (System/currentTimeMillis)
                 :event-type event-type
                 :details details
                 :user (get details :user "system")}]
      (spit log-file (str (pr-str entry) "\n") :append true)))
  
  (get-audit-trail [this filters]
    (when (.exists (java.io.File. log-file))
      (->> (line-seq (java.io.BufferedReader. (java.io.FileReader. log-file)))
           (map #(try (read-string %) (catch Exception _ nil)))
           (filter identity)
           (filter (fn [entry]
                     (every? (fn [[k v]]
                               (= v (get entry k)))
                             filters)))
           vec))))

;; ======================
;; Security Manager
;; ======================

(defrecord SecurityManager [input-validator authenticator sandbox-factory audit-logger]
  {:doc "Main security manager coordinating all security components."}
  
  ;; Input validation
  (validate [this input context]
    (validate-input input-validator input context))
  
  (sanitize [this input]
    (sanitize-input input-validator input))
  
  ;; Authentication
  (login [this credentials]
    (authenticate authenticator credentials))
  
  (check-auth [this user-context action resource]
    (authorize authenticator user-context action resource))
  
  ;; Secure execution
  (create-execution-context [this user-context]
    (create-sandbox sandbox-factory {:user user-context}))
  
  (execute-securely [this execution-context code]
    (execute-in-sandbox sandbox-factory execution-context code))
  
  ;; Audit logging
  (log-event [this event-type details]
    (log-security-event audit-logger event-type details))
  
  (get-audit-logs [this filters]
    (get-audit-trail audit-logger filters)))

;; ======================
;; Factory Functions
;; ======================

(defn create-security-manager
  "Create a security manager with default configurations."
  [config]
  (let [input-validator (->BasicInputValidator
                         (:allowed-patterns config [#".*"])
                         (:blocked-patterns config [#"<script.*?>.*?</script>"
                                                    #"javascript:"
                                                    #"on\\w+\\s*="]))
        
        authenticator (->SimpleAuthenticator
                       (:users config {})
                       (:token-secret config "default-secret-change-in-production"))
        
        sandbox-factory (->BasicSandbox
                         (:execution-timeout config 5000)
                         (:memory-limit config (* 128 1024 1024))) ; 128MB
        
        audit-logger (->FileAuditLogger
                      (:audit-log-file config "/tmp/agent-audit.log"))]
    
    (->SecurityManager input-validator authenticator sandbox-factory audit-logger)))

;; ======================
;; Security Specs
;; ======================

(s/def ::username string?)
(s/def ::password string?)
(s/def ::roles (s/coll-of keyword? :kind set?))
(s/def ::user-context (s/keys :req-un [::username ::roles]))

(s/def ::event-type #{:login :logout :execution :error :permission-denied})
(s/def ::audit-details map?)

;; ======================
;; Example Usage
;; ======================

(comment
  ;; Create security manager
  (def security (create-security-manager
                 {:users {"admin" {:password "secret" :roles #{:admin}}
                          "user" {:password "password" :roles #{:user}}}
                  :token-secret "my-secret-key"
                  :execution-timeout 3000
                  :audit-log-file "/tmp/my-agent-audit.log"}))
  
  ;; Authenticate user
  (def user-context (.login security {:username "admin" :password "secret"}))
  
  ;; Validate input
  (def safe-input (.validate security "<script>alert('xss')</script>" {:context :user-input}))
  
  ;; Execute code securely
  (def sandbox (.create-execution-context security user-context))
  (def result (.execute-securely security sandbox '(+ 1 2 3)))
  
  ;; Log security event
  (.log-event security :execution {:user "admin" :action "calculate" :result 6}))