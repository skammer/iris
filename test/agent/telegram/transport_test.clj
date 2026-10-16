(ns agent.telegram.transport-test
  (:require
   [agent.telegram.test-support :refer [chat-stub photo-update-for recording-opts rich-test-config temp-db-path update-for voice-update-for]]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.events :as system-events]
   [agent.telegram :as telegram]
   [agent.telegram.api :as telegram-api]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is ]]))

(deftest telegram-get-updates-includes-callback-queries
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [_ method body]
	                                          (swap! calls conj {:method method :body body})
	                                          [])]
      (telegram-api/get-updates! "token" {:offset 10 :timeout 1 :limit 2})
      (is (= [{:method "getUpdates"
               :body {:timeout 1
                      :limit 2
                      :allowed_updates ["message" "callback_query"
                                        "stopped_message_generation"]
                      :offset 10}}]
             @calls)))))

(deftest telegram-send-chat-action-calls-api
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-chat-action! "token" 100 "typing")
      (is (= [{:token "token"
               :method "sendChatAction"
               :body {:chat_id 100
                      :action "typing"}}]
             @calls)))))

(deftest telegram-send-message-disables-link-preview
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-message! "token" 100 "https://example.com")
      (is (= [{:token "token"
               :method "sendMessage"
               :body {:chat_id 100
                      :text "https://example\\.com"
                      :parse_mode "MarkdownV2"
                      :link_preview_options {:is_disabled true}}}]
             @calls)))))

(deftest telegram-send-html-message-uses-rich-message
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-html-message! "token" 100 "<blockquote expandable>x</blockquote>")
      (is (= [{:token "token"
               :method "sendRichMessage"
               :body {:chat_id 100
                      :rich_message {:markdown "<blockquote expandable>x</blockquote>"}
                      :link_preview_options {:is_disabled true}}}]
             @calls)))))

(deftest telegram-long-html-message-uses-rich-chunks-without-truncation
  (let [calls (atom [])
        text (apply str (repeat 70000 "x"))]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-html-message! "token" 100 text))
    (is (= 3 (count @calls)))
    (is (every? #(= "sendRichMessage" (:method %)) @calls))
    (is (= 70000
           (->> @calls
                (map #(count (get-in % [:body :rich_message :markdown])))
                (reduce +))))
    (is (every? #(<= (count (get-in % [:body :rich_message :markdown])) 32768)
                @calls))))

(deftest telegram-rich-message-chunks-long-markdown
  (let [calls (atom [])
        text (apply str (repeat 70000 "x"))]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-rich-message! "token" 100 text))
    (is (= 3 (count @calls)))
    (is (every? #(= "sendRichMessage" (:method %)) @calls))
    (is (= 70000
           (->> @calls
                (map #(count (get-in % [:body :rich_message :markdown])))
                (reduce +))))))

(deftest telegram-rich-reply-markup-attaches-only-to-first-chunk
  (let [calls (atom [])
        text (apply str (repeat 70000 "x"))
        markup {:inline_keyboard [[{:text "Run" :callback_data "run"}]]}]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-message-with-reply-markup! "token" 100 text markup))
    (is (= 3 (count @calls)))
    (is (= markup (get-in (first @calls) [:body :reply_markup])))
    (is (every? #(nil? (get-in % [:body :reply_markup]))
                (rest @calls)))))

(deftest telegram-rich-send-failure-falls-back-to-legacy-chunks
  (let [calls (atom [])
        text (apply str (repeat 9000 "x"))]
    (with-redefs [telegram-api/request! (fn [_ method body]
                                          (if (= "sendRichMessage" method)
                                            (throw (ex-info "rich rejected"
                                                            {:type :telegram-api-error}))
                                            (do
                                              (swap! calls conj {:method method
                                                                 :body body})
                                              {:ok true})))]
      (telegram-api/send-html-message! "token" 100 text))
    (is (= ["sendMessage" "sendMessage" "sendMessage"] (mapv :method @calls)))
    (is (= 9000 (->> @calls (map #(count (get-in % [:body :text]))) (reduce +))))))

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
        data-dir (.toFile (java.nio.file.Files/createTempDirectory
                           "iris-telegram-media-"
                           (make-array java.nio.file.attribute.FileAttribute 0)))
        store (sqlite/create-store {:path path :evict-on-close? true})
        sent (atom [])
        calls (atom [])
        system {:store store
                :config {:iris {:data-dir (.getAbsolutePath data-dir)}}
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}
                :max-download-bytes 1024}
        opts {:send-message-fn (fn [chat-id text]
                                 (swap! sent conj {:chat-id chat-id :text text}))
              :send-message-draft-fn (fn [& _] nil)
              :send-chat-action-fn (fn [& _] nil)
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
                                  (.getBytes "image-bytes" "UTF-8"))}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [messages]} emit!]
                                 (swap! calls conj {:op :chat
                                                    :messages messages})
                                 (emit! :message-end {:content "ok" :final? true})
                                 {:content "ok"}))]
        (is (= :processed
               (telegram/process-update! system config opts
                                         (photo-update-for 1 100 7 "what is this?")))))
      (let [content (->> @calls (filter #(= :chat (:op %))) first :messages first :content)
            saved-file (io/file data-dir "telegram-media" "100" "1-01-big.jpg")]
        (is (= {:type :text
                :text (str "what is this?\n\n"
                           "Local copies of attached files (use file/shell operations, not fs_read):\n"
                           "- " (.getAbsolutePath saved-file))}
               (first content)))
        (is (= [{:type :image
                 :source {:type :base64
                          :media-type "image/jpeg"
                          :value "aW1hZ2UtYnl0ZXM="}
                 :alt "Telegram photo"
                 :filename "big.jpg"}]
               (rest content)))
        (is (.isFile saved-file))
        (is (= "image-bytes" (slurp saved-file))))
      (is (= [{:op :get-file :token "token" :file-id "big"}
              {:op :download :token "token" :file-path "photos/big.jpg"}]
             (take 2 @calls)))
      (is (= [{:chat-id 100 :text "ok"}] @sent))
      (finally
        (sqlite/close-store! store)
        (doseq [file (reverse (file-seq data-dir))]
          (io/delete-file file true))
        (io/delete-file path true)))))

(deftest telegram-voice-message-becomes-audio-content-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        seen (atom nil)
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        config {:bot-token "token"
                :allowlist {:allow-all? true}
                :max-download-bytes 1024}
        opts {:send-message-fn (fn [_ _] nil)
              :send-message-draft-fn (fn [& _] nil)
              :send-chat-action-fn (fn [& _] nil)
              :get-file-fn (fn [_ _] {:file_path "voice/file.ogg"
                                      :file_size 12})
              :download-file-fn (fn [_ _] (.getBytes "ogg" "UTF-8"))}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [messages]} _emit!]
                                 (reset! seen (-> messages first :content))
                                 {:content "ok"}))]
        (is (= :processed
               (telegram/process-update! system config opts
                                         (voice-update-for 2 100 7)))))
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

(deftest telegram-inbound-rich-message-converts-to-markdown
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path :evict-on-close? true})
        seen-content (atom nil)
        recorders {:sent (atom []) :html-sent (atom []) :drafts (atom [])
                   :rich-sent (atom []) :rich-drafts (atom [])}
        system {:store store
                :event-bus (system-events/create-event-bus)
                :event-sink (fn [_] nil)}
        opts (recording-opts recorders)
        update {:update_id 1
                :message {:message_id 1
                          :from {:id 7}
                          :chat {:id 100 :type "private" :first_name "Test"}
                          :rich_message {:blocks [{:type "heading" :text "Hi" :size 1}
                                                  {:type "paragraph" :text "body"}]}}}]
    (try
      (with-redefs [chat/run! (chat-stub
                               (fn [{:keys [messages]} emit!]
                                 (reset! seen-content (-> messages first :content))
                                 (emit! :message-end {:content "ok" :final? true})
                                 {:content "ok"}))]
        (is (= :processed
               (telegram/process-update! system (rich-test-config) opts update))))
      (is (= "# Hi\n\nbody" @seen-content))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest telegram-send-rich-message-calls-api
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [token method body]
                                          (swap! calls conj {:token token
                                                             :method method
                                                             :body body})
                                          {:ok true})]
      (telegram-api/send-rich-message! "token" 100 "# Hello")
      (telegram-api/send-rich-message! "token" 100 "pick" {:reply-markup {:inline_keyboard []}})
      (telegram-api/send-rich-message-draft! "token" 100 42 "partial"))
    (is (= [{:token "token"
             :method "sendRichMessage"
             :body {:chat_id 100
                    :rich_message {:markdown "# Hello"}
                    :link_preview_options {:is_disabled true}}}
            {:token "token"
             :method "sendRichMessage"
             :body {:chat_id 100
                    :rich_message {:markdown "pick"}
                    :reply_markup {:inline_keyboard []}
                    :link_preview_options {:is_disabled true}}}
            {:token "token"
             :method "sendRichMessageDraft"
             :body {:chat_id 100
                    :draft_id 42
                    :rich_message {:markdown "partial"}
                    :link_preview_options {:is_disabled true}}}]
           @calls))))

(deftest telegram-bot-api-10-3-rich-ephemeral-and-stop-payloads
  (let [calls (atom [])]
    (with-redefs [telegram-api/request! (fn [_ method body]
                                          (swap! calls conj {:method method :body body})
                                          {:ephemeral_message_id 9})]
      (telegram-api/send-rich-message!
       "token" -100 "<tg-document src=\"tg://document?id=report\"></tg-document>"
       {:media [{:id "report"
                 :media {:type "document" :media "file-id"}}]
        :ephemeral-message-parameters {:receiver_user_id 7
                                       :callback_query_id "cb-1"
                                       :replace_callback_query_message true}})
      (telegram-api/send-message-draft!
       "token" 100 42 "partial" {:can-stop? true :keep-on-stop? true})
      (telegram-api/send-rich-message-draft!
       "token" 100 42 "partial" {:can-stop? true :keep-on-stop? true})
      (telegram-api/edit-ephemeral-rich-message! "token" -100 7 9 "done")
      (telegram-api/edit-ephemeral-message-media!
       "token" -100 7 9 {:type "document" :media "new-file-id"})
      (telegram-api/delete-ephemeral-message! "token" -100 7 9))
    (is (= [{:method "sendRichMessage"
             :body {:chat_id -100
                    :rich_message
                    {:markdown "<tg-document src=\"tg://document?id=report\"></tg-document>"
                     :media [{:id "report"
                              :media {:type "document" :media "file-id"}}]}
                    :ephemeral_message_parameters
                    {:receiver_user_id 7
                     :callback_query_id "cb-1"
                     :replace_callback_query_message true}
                    :link_preview_options {:is_disabled true}}}
            {:method "sendMessageDraft"
             :body {:chat_id 100 :draft_id 42
                    :text "partial" :parse_mode "MarkdownV2"
                    :can_stop true :keep_on_stop true
                    :link_preview_options {:is_disabled true}}}
            {:method "sendRichMessageDraft"
             :body {:chat_id 100 :draft_id 42
                    :rich_message {:markdown "partial"}
                    :can_stop true :keep_on_stop true
                    :link_preview_options {:is_disabled true}}}
            {:method "editEphemeralMessageText"
             :body {:chat_id -100 :receiver_user_id 7 :ephemeral_message_id 9
                    :rich_message {:markdown "done"}
                    :link_preview_options {:is_disabled true}}}
            {:method "editEphemeralMessageMedia"
             :body {:chat_id -100 :receiver_user_id 7 :ephemeral_message_id 9
                    :media {:type "document" :media "new-file-id"}}}
            {:method "deleteEphemeralMessage"
             :body {:chat_id -100 :receiver_user_id 7 :ephemeral_message_id 9}}]
           @calls))))

(deftest telegram-ephemeral-media-supports-new-file-upload
  (let [call (atom nil)]
    (with-redefs [telegram-api/multipart-request!
                  (fn [token method parts]
                    (reset! call {:token token :method method :parts parts})
                    true)]
      (telegram-api/edit-ephemeral-message-media-file!
       "token" -100 7 9 {:type "document"} "/tmp/report.pdf"))
    (is (= "token" (:token @call)))
    (is (= "editEphemeralMessageMedia" (:method @call)))
    (is (= ["chat_id" "receiver_user_id" "ephemeral_message_id" "media"
            "ephemeral_media"]
           (mapv :name (:parts @call))))
    (is (= "{\"type\":\"document\",\"media\":\"attach://ephemeral_media\"}"
           (:content (nth (:parts @call) 3))))))
