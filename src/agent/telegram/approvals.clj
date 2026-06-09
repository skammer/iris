(ns agent.telegram.approvals
  "Telegram inline-keyboard tool approvals."
  (:require
   [agent.telegram.api :as tg-api]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private callback-prefix "ta")
(def ^:private input-preview-chars 1800)
(def ^:private tool-context-chars 8000)

(defn- escape-html [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- escape-html-truncated [s max-chars]
  (loop [chars (seq (str s))
         acc []
         n 0]
    (if-let [ch (first chars)]
      (let [escaped (escape-html ch)
            n* (+ n (count escaped))]
        (if (<= n* max-chars)
          (recur (next chars) (conj acc escaped) n*)
          (apply str acc)))
      (apply str acc))))

(defn- callback-data [action approval-id]
  (str callback-prefix ":" (name action) ":" approval-id))

(defn parse-callback [data]
  (let [[prefix action approval-id extra] (str/split (or data "") #":" 4)]
    (when (and (= callback-prefix prefix)
               (contains? #{"run" "deny"} action)
               (not (str/blank? approval-id))
               (nil? extra))
      {:action (keyword action)
       :approval-id approval-id})))

(defn- truncate [s max-chars]
  (let [s* (str s)]
    (if (> (count s*) max-chars)
      (str (subs s* 0 max-chars) "\n[truncated]")
      s*)))

(defn- approval-reason [approval]
  (or (some-> (:reason approval) str str/trim not-empty)
      "Agent requested tool execution"))

(defn- approval-details [approval]
  (str "approval_id: " (:id approval) "\n"
       "input:\n"
       (truncate (json/generate-string (:input approval) {:pretty true})
                 input-preview-chars)))

(defn card-html [approval]
  (str "Tool approval required\n"
       "Tool: " (escape-html (:tool-name approval)) "\n"
       "Reason: " (escape-html (approval-reason approval)) "\n"
       "<blockquote expandable>details\n\n"
       (escape-html-truncated (approval-details approval) 3000)
       "</blockquote>"))

(defn keyboard [approval-id]
  {:inline_keyboard [[{:text "Approve & run"
                       :callback_data (callback-data :run approval-id)}
                      {:text "Deny"
                       :callback_data (callback-data :deny approval-id)}]]})

(defn send-card!
  [safe-telegram! system config opts chat-id approval]
  (let [token (:bot-token config)
        send! (or (:send-html-message-with-reply-markup-fn opts)
                  (fn [cid text reply-markup]
                    (tg-api/send-html-message-with-reply-markup! token cid text reply-markup)))]
    (safe-telegram! system chat-id :approval-card
                    #(send! chat-id
                            (card-html approval)
                            (keyboard (:id approval))))))

(defn- actor [callback-query]
  (str "telegram:" (get-in callback-query [:from :id])))

(defn- execute-approved-tool!
  [system chat-id approval-id actor]
  (let [{:keys [tool-name input permissions approval]}
        (tool-approvals/resolve-approved-request (:store system) approval-id)
        user (or (not-empty (:requested-by approval)) actor "telegram")]
    {:tool-name tool-name
     :input input
     :approval approval
     :result (tools/execute-tool
              (:tool-registry system)
              tool-name
              input
              {:permissions permissions
               :approval-id approval-id
               :telegram-chat-id chat-id
               :user user
               :request-id (str "telegram-approval-" approval-id)
               :yolo? (true? (get-in system [:config :tools :yolo?]))})}))

(defn- ensure-approved-for-run!
  [store approval-id actor]
  (try
    (tool-approvals/approve! store approval-id actor "approved in telegram")
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (= :approval-decision-conflict (:type data))
          (let [approval (tool-approvals/get-request store approval-id)]
            (when-not (= "approved" (:status approval))
              (throw e))
            approval)
          (throw e))))))

(defn- ensure-denied!
  [store approval-id actor]
  (try
    (tool-approvals/deny! store approval-id actor "denied in telegram")
    {:already? false}
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (= :approval-decision-conflict (:type data))
          (let [approval (tool-approvals/get-request store approval-id)]
            (when-not (= "denied" (:status approval))
              (throw e))
            {:already? true})
          (throw e))))))

(defn approved-status-text [tool-name]
  (str (name tool-name) " status: ok"))

(defn result-context-text [tool-name input result]
  (str "Approved tool result. Use this result to continue answering the user's previous request.\n"
       "Tool: " (name tool-name) "\n"
       "Input:\n"
       (json/generate-string input {:pretty true})
       "\nResult:\n"
       (truncate
        (if (and (map? result)
                 (or (contains? result :stdout)
                     (contains? result :stderr)))
          (str "stdout:\n" (or (:stdout result) "")
               (when-not (str/blank? (:stderr result))
                 (str "\nstderr:\n" (:stderr result)))
               (when (contains? result :exit-code)
                 (str "\nexit-code: " (:exit-code result))))
          (json/generate-string result {:pretty true}))
        tool-context-chars)))

(defn- remove-callback-keyboard!
  [safe-telegram! system config opts chat-id message-id]
  (let [token (:bot-token config)
        edit! (or (:edit-message-reply-markup-fn opts)
                  (fn [cid mid reply-markup]
                    (tg-api/edit-message-reply-markup! token cid mid reply-markup)))]
    (safe-telegram! system chat-id :approval-keyboard-clear
                    #(edit! chat-id message-id nil))))

(defn answer-callback!
  [safe-telegram! system config opts callback-query text & [{:keys [alert?]}]]
  (let [token (:bot-token config)
        answer! (or (:answer-callback-query-fn opts)
                    (fn [callback-query-id body]
                      (tg-api/answer-callback-query! token callback-query-id body)))]
    (safe-telegram! system
                    (get-in callback-query [:message :chat :id])
                    :callback-answer
                    #(answer! (:id callback-query)
                              (cond-> {}
                                (not (str/blank? text)) (assoc :text text)
                                alert? (assoc :show_alert true))))))

(defn process-callback!
  [safe-telegram! continue! system config opts callback-query {:keys [action approval-id]}]
  (let [chat-id (get-in callback-query [:message :chat :id])
        message-id (get-in callback-query [:message :message_id])
        send! (or (:send-message-fn opts)
                  (fn [cid text] (tg-api/send-message! (:bot-token config) cid text)))
        actor* (actor callback-query)]
    (case action
      :deny
      (let [{:keys [already?]} (ensure-denied! (:store system) approval-id actor*)]
        (remove-callback-keyboard! safe-telegram! system config opts chat-id message-id)
        (answer-callback! safe-telegram! system config opts callback-query
                          (if already? "Already denied." "Denied."))
        (when-not already?
          (send! chat-id "Tool denied."))
        :processed)

      :run
      (do
        (ensure-approved-for-run! (:store system) approval-id actor*)
        (remove-callback-keyboard! safe-telegram! system config opts chat-id message-id)
        (answer-callback! safe-telegram! system config opts callback-query "Running.")
        (let [{:keys [tool-name input result approval]} (execute-approved-tool! system chat-id approval-id actor*)]
          (send! chat-id (approved-status-text tool-name))
          (continue! (get-in callback-query [:message :chat])
                     chat-id
                     (:requested-by approval)
                     tool-name
                     input
                     result))
        :processed))))
