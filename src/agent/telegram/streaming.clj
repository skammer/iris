(ns agent.telegram.streaming
  "Telegram draft streaming, typing indicator, thinking quote, and tool summaries.

   Two streaming modes share one controls contract:
   - rich (default): sendRichMessageDraft flushes with live thinking in a
     <tg-thinking> block, finalized via sendRichMessage with thinking as a
     collapsed <details> block. Any rich API error downgrades the rest of
     the turn to the legacy mode.
   - legacy: MarkdownV2 sendMessageDraft flushes, thinking sent afterwards
     as a separate expandable HTML quote.

   Draft cadence is owned by a trailing-edge scheduler: content arriving
   inside the throttle window is flushed when the window elapses rather
   than waiting for the next delta, so drafts update at a steady
   stream-flush-ms metronome. A keepalive re-flush during silent gaps
   keeps the ephemeral draft from hitting Telegram's ~30s preview TTL."
  (:require
   [agent.telegram.api :as tg-api]
   [agent.telegram.rich :as rich]
   [agent.tools.display :as tool-display]
   [agent.util :as util]
   [clojure.string :as str]))

(def ^:private max-source-chars 3400)
;; Telegram's per-chat guideline is at most one message per second; drafts
;; follow it with the flush window pinned to that maximum.
(def ^:private stream-flush-ms 1000)
(def ^:private draft-keepalive-ms 25000)
(def ^:private scheduler-tick-ms 200)
(def ^:private typing-refresh-ms 4000)
(def ^:private max-draft-id 2147483647)

(defn- thinking-quote-html [text]
  (let [clipped (util/truncate text max-source-chars (constantly "\n\n[truncated]"))]
    (str "<blockquote expandable>thinking\n\n"
         (tool-display/escape-html clipped)
         "</blockquote>")))

(defn- private-chat? [chat]
  (= "private" (:type chat)))

(defn- next-draft-id []
  (inc (mod (System/currentTimeMillis) max-draft-id)))

(defn- rotate-draft-id
  [id]
  (if (>= id max-draft-id) 1 (inc id)))

(defn- make-flush-scheduler
  "Trailing-edge flush scheduling around `do-flush!` (which must not throw).

   - request!  marks new content; flushes immediately when the window from
     the previous flush has elapsed, otherwise the worker delivers it the
     moment the window closes — no waiting for the next delta.
   - keepalive: while `has-content?`, an unchanged draft is re-flushed
     before Telegram's preview TTL can expire during silent gaps (slow
     providers, long tool runs).
   - reset!    clears pending state after a finalize so the next turn
     segment paints immediately.
   - stop!     halts the worker; callers must invoke it when the turn ends.

   All flushes are serialized on `lock`, which callers can also hold to
   exclude flushes during finalization."
  [lock has-content? do-flush!]
  (let [last-flush (atom 0)
        dirty? (atom false)
        stopped? (atom false)
        flush-now! (fn []
                     (locking lock
                       (reset! dirty? false)
                       (reset! last-flush (System/currentTimeMillis))
                       (do-flush!)))
        worker (future
                 (while (not @stopped?)
                   (Thread/sleep scheduler-tick-ms)
                   (let [elapsed (- (System/currentTimeMillis) @last-flush)]
                     (cond
                       (and @dirty? (>= elapsed stream-flush-ms))
                       (flush-now!)

                       (and (not @dirty?)
                            (has-content?)
                            (>= elapsed draft-keepalive-ms))
                       (flush-now!)))))]
    {:request! (fn []
                 (reset! dirty? true)
                 (when (>= (- (System/currentTimeMillis) @last-flush)
                           stream-flush-ms)
                   (flush-now!)))
     :reset! (fn []
               (reset! dirty? false)
               (reset! last-flush 0))
     :stop! (fn []
              (reset! stopped? true)
              (future-cancel worker))}))

(defn- build-legacy-controls
  [safe-telegram! system config opts chat-id]
  (let [token (:bot-token config)
        lock (Object.)
        draft-id (atom (next-draft-id))
        accumulator (atom "")
        thinking-accumulator (atom "")
        send-draft! (or (:send-message-draft-fn opts)
                        (fn [cid did text] (tg-api/send-message-draft! token cid did text)))
        send-msg! (or (:send-message-fn opts)
                      (fn [cid text] (tg-api/send-message! token cid text)))
        send-html! (or (:send-html-message-fn opts)
                       (fn [cid text] (tg-api/send-html-message! token cid text)))
        do-flush! (fn []
                    (let [text @accumulator]
                      (when-not (str/blank? text)
                        (safe-telegram! system chat-id :draft-update
                                        #(send-draft! chat-id @draft-id text)))))
        scheduler (make-flush-scheduler lock
                                        #(not (str/blank? @accumulator))
                                        do-flush!)
        finalize-thinking! (fn []
                             (let [text @thinking-accumulator]
                               (reset! thinking-accumulator "")
                               (when-not (str/blank? text)
                                 (safe-telegram! system chat-id :thinking-summary
                                                 #(send-html! chat-id (thinking-quote-html text))))))
        finalize! (fn []
                    (locking lock
                      (let [text @accumulator]
                        (reset! accumulator "")
                        ((:reset! scheduler))
                        (swap! draft-id rotate-draft-id)
                        (finalize-thinking!)
                        (when-not (str/blank? text)
                          (safe-telegram! system chat-id :draft-finalize
                                          #(send-msg! chat-id text))))))]
    {:on-delta (fn [delta]
                 (swap! accumulator str delta)
                 ((:request! scheduler)))
     :on-thinking-delta (fn [delta]
                          (swap! thinking-accumulator str delta))
     :finalize-thinking! finalize-thinking!
     :finalize! finalize!
     :stop! (:stop! scheduler)}))

(defn- build-rich-controls
  [safe-telegram! system config opts chat-id]
  (let [token (:bot-token config)
        lock (Object.)
        draft-id (atom (next-draft-id))
        accumulator (atom "")
        thinking-accumulator (atom "")
        ;; Thinking drained by finalize-thinking! but not yet delivered; it
        ;; rides along in drafts and lands in the final <details> block.
        pending-thinking (atom "")
        ;; Sticky per-turn downgrade: a failed rich send would most likely
        ;; fail again at flush cadence, so the rest of the turn goes legacy.
        ;; The next turn builds fresh controls and retries rich.
        rich-ok? (atom true)
        send-rich-draft! (or (:send-rich-message-draft-fn opts)
                             (fn [cid did markdown]
                               (tg-api/send-rich-message-draft! token cid did markdown)))
        send-rich! (or (:send-rich-message-fn opts)
                       (fn [cid markdown] (tg-api/send-rich-message! token cid markdown)))
        send-draft! (or (:send-message-draft-fn opts)
                        (fn [cid did text] (tg-api/send-message-draft! token cid did text)))
        send-msg! (or (:send-message-fn opts)
                      (fn [cid text] (tg-api/send-message! token cid text)))
        send-html! (or (:send-html-message-fn opts)
                       (fn [cid text] (tg-api/send-html-message! token cid text)))
        thinking-now (fn [] (str @pending-thinking @thinking-accumulator))
        has-content? (fn [] (or (not (str/blank? @accumulator))
                                (not (str/blank? (thinking-now)))))
        ;; Legacy drafts can't carry thinking text, but an empty draft shows
        ;; Telegram's native "Thinking…" placeholder — without it a downgrade
        ;; during a thinking-only phase leaves the chat blank until the
        ;; previous draft's TTL wipes it.
        legacy-draft! (fn [text]
                        (send-draft! chat-id @draft-id (if (str/blank? text) "" text)))
        legacy-finalize! (fn [thinking text]
                           (when-not (str/blank? thinking)
                             (safe-telegram! system chat-id :thinking-summary
                                             #(send-html! chat-id (thinking-quote-html thinking))))
                           (when-not (str/blank? text)
                             (safe-telegram! system chat-id :draft-finalize
                                             #(send-msg! chat-id text))))
        do-flush! (fn []
                    (let [text @accumulator
                          thinking (thinking-now)]
                      (when (or (not (str/blank? text))
                                (not (str/blank? thinking)))
                        (if @rich-ok?
                          (safe-telegram! system chat-id :rich-draft-update
                                          #(try
                                             (send-rich-draft! chat-id @draft-id
                                                               (rich/compose-draft thinking text))
                                             (catch Exception e
                                               (reset! rich-ok? false)
                                               (legacy-draft! text)
                                               ;; rethrow so the failure event is recorded
                                               (throw e))))
                          (safe-telegram! system chat-id :draft-update
                                          #(legacy-draft! text))))))
        scheduler (make-flush-scheduler lock has-content? do-flush!)
        finalize! (fn []
                    (locking lock
                      (let [text @accumulator
                            thinking (thinking-now)]
                        (reset! accumulator "")
                        (reset! thinking-accumulator "")
                        (reset! pending-thinking "")
                        ((:reset! scheduler))
                        (swap! draft-id rotate-draft-id)
                        (when (or (not (str/blank? text))
                                  (not (str/blank? thinking)))
                          (if @rich-ok?
                            (safe-telegram! system chat-id :rich-finalize
                                            #(try
                                               (doseq [chunk (rich/final-chunks thinking text)]
                                                 (send-rich! chat-id chunk))
                                               (catch Exception e
                                                 (reset! rich-ok? false)
                                                 (legacy-finalize! thinking text)
                                                 (throw e))))
                            (legacy-finalize! thinking text))))))]
    {:on-delta (fn [delta]
                 (swap! accumulator str delta)
                 ((:request! scheduler)))
     ;; Rich drafts can show thinking live, so thinking deltas flush too.
     :on-thinking-delta (fn [delta]
                          (swap! thinking-accumulator str delta)
                          ((:request! scheduler)))
     ;; Drain into pending: thinking is delivered with the final message
     ;; rather than as a separate send.
     :finalize-thinking! (fn []
                           (let [text @thinking-accumulator]
                             (reset! thinking-accumulator "")
                             (swap! pending-thinking str text)))
     :finalize! finalize!
     :stop! (:stop! scheduler)}))

(defn build-controls
  [safe-telegram! system config opts chat chat-id]
  (when (private-chat? chat)
    (if (rich/enabled? config)
      (build-rich-controls safe-telegram! system config opts chat-id)
      (build-legacy-controls safe-telegram! system config opts chat-id))))

(defn build-on-tool-call
  [safe-telegram! system opts chat-id stream-controls]
  (let [cfg (tool-display/channel-config system :telegram nil)]
    (when (true? (:show-tool-calls? cfg))
      (let [send! (or (:send-message-fn opts)
                      (fn [cid text]
                        (tg-api/send-html-message! (get-in system [:config :channel-adapters :telegram :bot-token])
                                                   cid text)))
            finalize! (:finalize! stream-controls)]
        (fn [{:keys [receipt]}]
          (safe-telegram! system chat-id :tool-call-summary
                          (fn []
                            (when finalize! (finalize!))
                            (let [text (tool-display/telegram-summary system receipt)]
                              (when-not (str/blank? text)
                                (send! chat-id text))))))))))

(defn start-typing-indicator!
  [safe-telegram! system config opts chat-id]
  (let [token (:bot-token config)
        running? (atom true)
        send-action! (or (:send-chat-action-fn opts)
                         (fn [cid action] (tg-api/send-chat-action! token cid action)))
        worker (future
                 (while @running?
                   (safe-telegram! system chat-id :typing
                                   #(send-action! chat-id "typing"))
                   (Thread/sleep typing-refresh-ms)))]
    (fn []
      (reset! running? false)
      (future-cancel worker))))
