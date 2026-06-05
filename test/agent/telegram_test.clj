(ns agent.telegram-test
  (:require
   [agent.channels.core :as channels]
   [agent.persistence.sqlite :as sqlite]
   [agent.telegram :as telegram]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-telegram-" ".db")))

(defn update-for
  [update-id chat-id user-id text]
  {:update_id update-id
   :message {:message_id update-id
             :from {:id user-id}
             :chat {:id chat-id
                    :type "private"
                    :first_name "Test"}
             :text text}})

(defn photo-update-for
  [update-id chat-id user-id caption]
  {:update_id update-id
   :message {:message_id update-id
             :from {:id user-id}
             :chat {:id chat-id
                    :type "private"
                    :first_name "Test"}
             :caption caption
             :photo [{:file_id "small" :file_size 10 :width 10 :height 10}
                     {:file_id "big" :file_size 20 :width 20 :height 20}]}})

(defn voice-update-for
  [update-id chat-id user-id]
  {:update_id update-id
   :message {:message_id update-id
             :from {:id user-id}
             :chat {:id chat-id
                    :type "private"
                    :first_name "Test"}
             :voice {:file_id "voice-1"
                     :file_size 12
                     :mime_type "audio/ogg"}}})

(deftest telegram-advertises-only-implemented-adapter-capabilities
  (let [service (telegram/create-service {:config {:channel-adapters {:telegram {:bot-token "token"}}}})
        caps (:capabilities (channels/describe-adapter service))]
    (is (= #{:supports-outbound
             :supports-streaming
             :supports-typing
             :supports-draft-updates
             :supports-draft-lifecycle}
           caps))
    (is (empty? (channels/capability-validation-errors service)))))

(deftest telegram-reuses-session-per-chat
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        calls (atom [])
        system {:store store
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:allow-all? true
                            :user-ids []
                            :chat-ids []}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))
              :chat-fn (fn [_ {:keys [session-id messages]}]
                             (swap! calls conj {:session-id session-id
                                                :messages messages})
                             {:content "pong"})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (is (= :processed
             (telegram/process-update! system config opts (update-for 2 100 7 "again"))))
      (is (= :processed
             (telegram/process-update! system config opts (update-for 3 200 8 "other"))))
      (is (= [100 100 200] (mapv :chat-id @sent)))
      (is (= ["pong" "pong" "pong"] (mapv :text @sent)))
      (is (= 3 (count @calls)))
      (is (= (:session-id (first @calls))
             (:session-id (second @calls))))
      (is (not= (:session-id (first @calls))
                (:session-id (nth @calls 2))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-reset-replaces-session
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true
                            :user-ids []
                            :chat-ids []}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))}]
    (try
      (telegram/process-update! system config opts (update-for 1 100 7 "/start"))
      (let [before (:session-id (sqlite/get-channel-session-mapping store :telegram 100))]
        (telegram/process-update! system config opts (update-for 2 100 7 "/reset"))
        (let [after (:session-id (sqlite/get-channel-session-mapping store :telegram 100))]
          (is (not= before after))
          (is (= "Session reset." (:text (last @sent))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-prompt-command-manages-session-mode
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))}]
    (try
      (telegram/process-update! system config opts (update-for 1 100 7 "/prompt"))
      (telegram/process-update! system config opts (update-for 2 100 7 "/prompt code"))
      (let [session-id (:session-id (sqlite/get-channel-session-mapping store :telegram 100))]
        (is (= "code" (:active-mode (sqlite/get-session store session-id))))
        (telegram/process-update! system config opts (update-for 3 100 7 "/prompt off"))
        (is (nil? (:active-mode (sqlite/get-session store session-id))))
        (telegram/process-update! system config opts (update-for 4 100 7 "/prompt missing"))
        (telegram/process-update! system config opts (update-for 5 100 7 "/help"))
        (is (str/includes? (:text (nth @sent 0)) "Prompt mode: off"))
        (is (str/includes? (:text (nth @sent 0)) "Available: ask"))
        (is (= "Prompt mode: code." (:text (nth @sent 1))))
        (is (= "Prompt mode off." (:text (nth @sent 2))))
        (is (str/includes? (:text (nth @sent 3)) "Unknown prompt mode: missing"))
        (is (str/includes? (:text (nth @sent 4)) "/prompt [name|off]")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-skills-command-and-slash-skill-ack-test
  (let [path (temp-db-path)
        root (.toFile (java.nio.file.Files/createTempDirectory "iris-telegram-skills-" (make-array java.nio.file.attribute.FileAttribute 0)))
        skill-dir (io/file root "review")
        _ (.mkdirs skill-dir)
        _ (spit (io/file skill-dir "SKILL.md")
                "---\nname: review\ndescription: Review code\n---\n# Review\n\nUse review checklist.")
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        prompts (atom [])
        system {:store store
                :skills-registry {:dirs [(.getAbsolutePath root)]}
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-chat-action-fn (fn [& _] nil)
              :send-message-draft-fn (fn [& _] nil)
              :chat-fn (fn [_ opts]
                         (swap! prompts conj (get-in opts [:messages 0 :content]))
                         {:content "ok"})}]
    (try
      (telegram/process-update! system config opts (update-for 1 100 7 "/skills rev"))
      (telegram/process-update! system config opts (update-for 2 100 7 "/review this"))
      (is (str/includes? (:text (first @sent)) "/review - Review code"))
      (is (= "Skills: /review" (:text (second @sent))))
      (is (= "/review this" (first @prompts)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file (io/file skill-dir "SKILL.md") true)
        (.delete skill-dir)
        (.delete root)
        (io/delete-file path true)))))

(deftest telegram-stop-cancels-active-chat
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        active-tasks (atom {})
        task-started (promise)
        release-task (promise)
        task-id "task-1"
        task (future
               (deliver task-started true)
               @release-task)
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:active-tasks active-tasks
              :send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))}]
    (try
      (is (true? (deref task-started 1000 false)))
      (swap! active-tasks assoc 100 {:id task-id :future task})
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "/stop"))))
      (is (= [{:chat-id 100 :text "Stopping."}] @sent))
      (is (nil? (get @active-tasks 100)))
      (deliver release-task true)
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-allowlist-blocks-unknown
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        system {:store store
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:user-ids ["7"]
                            :chat-ids []}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))}]
    (try
      (is (= :blocked
             (telegram/process-update! system config opts (update-for 1 100 8 "hi"))))
      (is (empty? @sent))
      (is (= :telegram.blocked (:event-type (first @events))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-denies-empty-allowlist-by-default
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        system {:store store
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:user-ids []
                            :chat-ids []}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id
                                                   :text text}))}]
    (try
      (is (= :blocked
             (telegram/process-update! system config opts (update-for 1 100 8 "hi"))))
      (is (empty? @sent))
      (is (= :telegram.blocked (:event-type (first @events))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-poll-retries-failed-update-before-advancing-offset
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        polls (atom [])
        attempts (atom 0)
        update (update-for 41 100 7 "hi")
        system {:store store
                :config {:channel-adapters {:telegram {:enabled true
                                                       :bot-token "token"
                                                       :poll-timeout-seconds 0
                                                       :poll-limit 1
                                                       :allowlist {:allow-all? true}}}}
                :event-sink #(swap! events conj %)}
        service (telegram/create-service
                 system
                 {:get-updates-fn (fn [{:keys [offset]}]
                                    (swap! polls conj offset)
                                    (if (< (count @polls) 3) [update] []))
	                  :send-message-fn (fn [_ _] nil)
                  :async-chat? false
	                  :chat-fn (fn [_ _]
                                 (if (= 1 (swap! attempts inc))
                                   (throw (ex-info "boom" {}))
                                   {:content "ok"}))})]
    (try
      (telegram/start! service)
      (Thread/sleep 1500)
      (telegram/stop! service 1000)
      (is (= [nil nil 42] (take 3 @polls)))
      (is (= 2 @attempts))
      (is (= 42 (:next_offset (sqlite/get-channel-offset store :telegram))))
      (is (= "processed" (:status (sqlite/get-channel-inbox-update store :telegram 41))))
      (finally
        (telegram/stop! service 1000)
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-streams-private-chat-replies
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        drafts (atom [])
        deltas ["hello" " " "world" "!"]
        system {:store store
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [chat-id draft-id text]
                                       (swap! drafts conj {:chat-id chat-id
                                                           :draft-id draft-id
                                                           :text text}))
              :chat-fn (fn [_ {:keys [session-id on-delta]}]
                                (doseq [d deltas]
                                  (Thread/sleep 700) ;; force flush throttle
                                  (on-delta d))
                                {:content (apply str deltas)
                                 :session-id session-id
                                 :stream? true})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (is (= [{:chat-id 100 :text "hello world!"}] @sent))
      (is (pos? (count @drafts)))
      (is (every? #(= 100 (:chat-id %)) @drafts))
      (let [draft-ids (set (map :draft-id @drafts))]
        (is (= 1 (count draft-ids))
            "all draft chunks should share one draft_id"))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-sends-thinking-as-expandable-html-quote
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        html-sent (atom [])
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [_ _ _] nil)
              :send-html-message-fn (fn [chat-id text]
                                      (swap! html-sent conj {:chat-id chat-id :text text}))
              :chat-fn (fn [_ {:keys [session-id on-delta on-thinking-delta]}]
                         (on-thinking-delta "think <x>")
                         (on-delta "answer")
                         {:content "answer"
                          :session-id session-id
                          :stream? true})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (is (= [{:chat-id 100 :text "answer"}] @sent))
      (is (= [{:chat-id 100
               :text "<blockquote expandable>thinking\n\nthink &lt;x&gt;</blockquote>"}]
             @html-sent))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-records-draft-send-failures
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        events (atom [])
        sent (atom [])
        system {:store store
                :event-sink #(swap! events conj %)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [_ _ _]
                                       (throw (ex-info "draft failed" {:type :draft-down})))
              :chat-fn (fn [_ {:keys [on-delta]}]
                         (on-delta "hello")
                         {:content "hello"})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (is (= [{:chat-id 100 :text "hello"}] @sent))
      (let [failure (first (filter #(= :telegram.operation.failed (:event-type %)) @events))]
        (is (= :draft-update (get-in failure [:payload :operation])))
        (is (= "draft failed" (get-in failure [:payload :message])))
        (is (= :draft-down (get-in failure [:payload :type]))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-records-typing-failures
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        typing-failure (promise)
        system {:store store
                :event-sink (fn [event]
                              (when (= :telegram.operation.failed (:event-type event))
                                (deliver typing-failure event)))}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [_ _] nil)
              :send-chat-action-fn (fn [_ _]
                                     (throw (ex-info "typing failed" {:type :typing-down})))
              :chat-fn (fn [_ _]
                         (is (some? (deref typing-failure 1000 nil)))
                         {:content "pong"})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (let [failure (deref typing-failure 1000 nil)]
        (is (= :typing (get-in failure [:payload :operation])))
        (is (= "typing failed" (get-in failure [:payload :message])))
        (is (= :typing-down (get-in failure [:payload :type]))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-finalizes-streamed-draft-before-tool-call-summary
  ;; Regression: Telegram drafts are ephemeral — sending any regular message
  ;; clears the in-flight draft. Streamed text emitted before a mid-turn tool
  ;; call must be promoted to a real sendMessage before the tool-call summary
  ;; is sent, otherwise it's lost from the chat history.
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        drafts (atom [])
        system {:store store
                :event-sink (fn [_] nil)
                :config {:tools {:display {:telegram {:show-tool-calls? true
                                                      :preview-chars 1600
                                                      :args-preview-chars 1200
                                                      :per-tool {}}}}}}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        step1-deltas ["cherry " "paragraph"]
        step2-deltas ["final " "answer"]
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [chat-id draft-id text]
                                       (swap! drafts conj {:chat-id chat-id
                                                           :draft-id draft-id
                                                           :text text}))
              :chat-fn (fn [_ {:keys [session-id on-delta on-tool-call]}]
                         (doseq [d step1-deltas]
                           (Thread/sleep 700)
                           (on-delta d))
                         (on-tool-call {:tool-call {:id "c1" :function {:name "list_dir"}}
                                        :receipt {:status :completed
                                                  :tool-name "list_dir"
                                                  :input {:path "./obsidian"}
                                                  :result {:files ["a.md"]}}})
                         (doseq [d step2-deltas]
                           (Thread/sleep 700)
                           (on-delta d))
                         {:content (apply str step2-deltas)
                          :session-id session-id
                          :stream? true})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (let [texts (mapv :text @sent)]
        (is (some #(= "cherry paragraph" %) texts)
            "streamed pre-tool-call text must be promoted to a real message")
        (is (some #(= "🔧 list_dir status: completed path: ./obsidian" %) texts)
            "tool-call summary must include tool name and status")
        (is (some #(= "final answer" %) texts)
            "final answer must be sent as a real message")
        (is (= "final answer" (last texts))
            "final answer is the last message sent"))
      (let [draft-ids (set (map :draft-id @drafts))]
        (is (>= (count draft-ids) 2)
            "draft id should rotate after finalize so step 2 streams onto a fresh draft slot"))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-sends-typing-while-chat-running
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        actions (atom [])
        typing-seen (promise)
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [_ _] nil)
              :send-chat-action-fn (fn [chat-id action]
                                     (swap! actions conj {:chat-id chat-id
                                                          :action action})
                                     (deliver typing-seen true))
              :chat-fn (fn [_ _]
                         (is (true? (deref typing-seen 1000 false)))
                         {:content "pong"})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts (update-for 1 100 7 "hi"))))
      (is (some #(= {:chat-id 100 :action "typing"} %) @actions))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-send-chat-action-calls-api
  (let [calls (atom [])]
    (with-redefs [telegram/api-request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram/send-chat-action! "token" 100 "typing")
      (is (= [{:token "token"
               :method "sendChatAction"
               :body {:chat_id 100
                      :action "typing"}}]
             @calls)))))

(deftest telegram-send-html-message-uses-html-parse-mode
  (let [calls (atom [])]
    (with-redefs [telegram/api-request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram/send-html-message! "token" 100 "<blockquote expandable>x</blockquote>")
      (is (= [{:token "token"
               :method "sendMessage"
               :body {:chat_id 100
                      :text "<blockquote expandable>x</blockquote>"
                      :parse_mode "HTML"}}]
             @calls)))))

(deftest telegram-photo-command-sends-photo
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        photos (atom [])
        system {:store store :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-photo-fn (fn [chat-id url caption]
                               (swap! photos conj {:chat-id chat-id
                                                   :url url
                                                   :caption caption}))}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts
                                       (update-for 1 100 7 "/photo https://example.com/a.png nice cat"))))
      (is (= [{:chat-id 100 :url "https://example.com/a.png" :caption "nice cat"}] @photos))
      (is (= :processed
             (telegram/process-update! system config opts
                                       (update-for 2 100 7 "/photo"))))
      (is (= "Usage: /photo <url> [caption]" (:text (last @sent))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-photo-message-downloads-and-sends-rich-content-to-chat-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        calls (atom [])
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}
                :max-download-bytes 1024}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :get-file-fn (fn [token file-id]
                             (swap! calls conj {:op :get-file
                                                :token token
                                                :file-id file-id})
                             {:file_path "photos/big.jpg"
                              :file_size 11})
              :download-file-fn (fn [token file-path]
                                  (swap! calls conj {:op :download
                                                     :token token
                                                     :file-path file-path})
                                  (.getBytes "image-bytes" "UTF-8"))
              :chat-fn (fn [_ {:keys [messages]}]
                         (swap! calls conj {:op :chat
                                            :messages messages})
                         {:content "ok"})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts
                                       (photo-update-for 1 100 7 "what is this?"))))
      (let [content (->> @calls (filter #(= :chat (:op %))) first :messages first :content)]
        (is (= [{:type :text :text "what is this?"}
                {:type :image
                 :source {:type :base64
                          :media-type "image/jpeg"
                          :value "aW1hZ2UtYnl0ZXM="}
                 :alt "Telegram photo"
                 :filename "big.jpg"}]
               content)))
      (is (= [{:op :get-file :token "token" :file-id "big"}
              {:op :download :token "token" :file-path "photos/big.jpg"}]
             (take 2 @calls)))
      (is (= [{:chat-id 100 :text "ok"}] @sent))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-voice-message-becomes-audio-content-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        seen (atom nil)
        system {:store store
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}
                :max-download-bytes 1024}
        opts {:send-message-fn (fn [_ _] nil)
              :get-file-fn (fn [_ _] {:file_path "voice/file.ogg"
                                      :file_size 12})
              :download-file-fn (fn [_ _] (.getBytes "ogg" "UTF-8"))
              :chat-fn (fn [_ {:keys [messages]}]
                         (reset! seen (-> messages first :content))
                         {:content "ok"})}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts
                                       (voice-update-for 2 100 7))))
      (is (= [{:type :text :text "Analyze attached audio."}
              {:type :audio
               :source {:type :base64
                        :media-type "audio/ogg"
                        :value "b2dn"}
               :alt "Telegram voice message"
               :filename "file.ogg"}]
             @seen))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-file-command-sends-document
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        docs (atom [])
        system {:store store :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}}
        opts {:send-message-fn (fn [_ _] nil)
              :send-document-fn (fn [chat-id url caption]
                                  (swap! docs conj {:chat-id chat-id
                                                    :url url
                                                    :caption caption}))}]
    (try
      (is (= :processed
             (telegram/process-update! system config opts
                                       (update-for 1 100 7 "/file https://example.com/a.pdf"))))
      (is (= [{:chat-id 100 :url "https://example.com/a.pdf" :caption nil}] @docs))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-session-ensure-is-concurrency-stable
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        mapping {:source :telegram
                 :external-chat-id 100
                 :title "Telegram: Test"
                 :metadata {:chat {:id 100}}}]
    (try
      (let [sessions (->> (repeatedly 20 #(future (:session-id (sqlite/ensure-channel-session! store mapping))))
                          doall
                          (mapv deref))]
        (is (= 1 (count (set sessions)))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest draft-id-stays-positive
  (let [next-draft-id @#'telegram/next-draft-id
        rotate-draft-id @#'telegram/rotate-draft-id
        valid? @#'telegram/valid-draft-id?
        max-id @#'telegram/max-draft-id]
    (testing "generated ids are in [1, max]"
      (let [id (next-draft-id)]
        (is (pos? id))
        (is (<= id max-id))))
    (testing "rotation never yields 0/negative/over-max and wraps at the ceiling"
      (is (= 1 (rotate-draft-id max-id)))
      (let [ids (take 5000 (iterate rotate-draft-id (- max-id 2)))]
        (is (every? pos? ids))
        (is (every? #(<= % max-id) ids))
        (is (some #(= 1 %) ids) "wraps back to 1")))
    (testing "external draft ids are validated"
      (is (valid? 1))
      (is (valid? max-id))
      (is (not (valid? 0)))
      (is (not (valid? -5)))
      (is (not (valid? (inc max-id))))
      (is (not (valid? 1.5)))
      (is (not (valid? nil))))))
