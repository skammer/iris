(ns agent.api.streaming
  "Server-Sent Events helpers for http-kit channels."
  (:require
   [agent.broker.core :as broker]
   [agent.logging :as logging]
   [agent.streaming.metrics :as metrics]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str]
   [org.httpkit.server :as http-kit]))

(declare send-sse-chunk!)

(defn- context? [target]
  (and (map? target)
       (contains? target :channel)
       (contains? target :open?)))

(defn- target-channel [target]
  (if (context? target) (:channel target) target))

(defn open? [target]
  (if (context? target)
    (true? @(:open? target))
    true))

(defn- record! [target k]
  (metrics/record! (when (context? target) (:metrics target)) k))

(defn- add-count! [target k n]
  (metrics/add-count! (when (context? target) (:metrics target)) k n))

(defn close! [target]
  (when-let [channel (target-channel target)]
    (when (or (not (context? target))
              (compare-and-set! (:open? target) true false))
      (when (context? target)
        (record! target :closed))
      (http-kit/close channel))))

(defn- cleanup! [ctx]
  (when (and (context? ctx)
             (compare-and-set! (:cleaned? ctx) false true))
    (doseq [cleanup (reverse @(:cleanups ctx))]
      (try
        (cleanup)
        (catch Throwable _
          (record! ctx :cleanup-errors))))))

(defn register-cleanup! [ctx cleanup]
  (when (context? ctx)
    (swap! (:cleanups ctx) conj cleanup))
  cleanup)

(defn subscribe!
  ([ctx broker-instance pattern]
   (subscribe! ctx broker-instance pattern {}))
  ([ctx broker-instance pattern opts]
   (let [subscription (broker/subscribe! broker-instance pattern opts)]
     (register-cleanup!
      ctx
      (fn []
        (add-count! ctx :dropped-events (some-> subscription :dropped-count deref))
        (broker/unsubscribe! broker-instance subscription)
        (record! ctx :unsubscribed)))
     subscription)))

(defn take! [ctx ch]
  (when (open? ctx)
    (async/<!! ch)))

(defn run-task! [ctx f]
  (let [ch (async/chan 1)
        worker (future
                 (try
                   (async/>!! ch {:result (f)})
                   (catch Throwable t
                     (async/>!! ch {:error t}))
                   (finally
                     (async/close! ch))))]
    (register-cleanup! ctx #(future-cancel worker))
    ch))

(defn- default-error! [ctx _error]
  (when (open? ctx)
    (send-sse-chunk! ctx {:type "error"
                          :error "stream_error"
                          :message "Stream failed"})))

(defn datastar-patch-frame [html]
  (let [lines (str/split (str html) #"\n" -1)]
    (str "event: datastar-patch-elements\n"
         (->> lines
              (map #(str "data: elements " %))
              (str/join "\n"))
         "\n\n")))

(defn sse-response
  ([request on-open on-close]
   (sse-response request on-open on-close ":\n\n"))
  ([request on-open on-close initial-body]
   (http-kit/as-channel
    request
    {:on-open (fn [channel]
                (http-kit/send! channel
                                {:status 200
                                 :headers {"Content-Type" "text/event-stream"
                                           "Cache-Control" "no-cache"}
                                 :body initial-body}
                                false)
                (when on-open
                  (on-open channel)))
     :on-close (fn [channel status]
                 (when on-close
                   (on-close channel status)))})))

(defn send-sse-text! [channel text]
  (if (open? channel)
    (try
      (let [sent? (true? (http-kit/send! (target-channel channel) text false))]
        (when-not sent?
          (record! channel :dropped-events))
        sent?)
      (catch Throwable _
        (record! channel :send-errors)
        false))
    (do
      (record! channel :dropped-events)
      false)))

(defn send-sse-chunk!
  ([channel payload]
   (send-sse-chunk! channel nil payload))
  ([channel event-id payload]
   (send-sse-text! channel
                   (str (when event-id (str "id: " event-id "\n"))
                        "data: " (json/generate-string payload) "\n\n"))))

(defn send-sse-done! [channel]
  (send-sse-text! channel "data: [DONE]\n\n"))

(defn send-datastar-patch! [channel html]
  (send-sse-text! channel (datastar-patch-frame html)))

(defn send-sse-error! [channel code message]
  (send-sse-chunk! channel {:type "error"
                            :error code
                            :message message}))

(defn managed-response
  ([request stream-fn]
   (managed-response request {} stream-fn))
  ([request {:keys [initial-body on-error close? name metrics]
             :or {initial-body ":\n\n"
                  close? true}} stream-fn]
   (let [ctx* (atom nil)
         worker* (atom nil)]
     (sse-response
      request
      (fn [channel]
	                 (let [ctx {:name name
	                   :channel channel
	                   :open? (atom true)
	                   :cleaned? (atom false)
                       :metrics metrics
	                   :cleanups (atom [])}]
	          (reset! ctx* ctx)
	          (record! ctx :opened)
          (reset!
           worker*
           (future
             (try
	               (stream-fn ctx)
	               (record! ctx :completed)
	               (catch Throwable t
	                 (record! ctx :errors)
                 (logging/log-error! :agent.api.streaming/stream-failed t
                                     (cond-> {}
                                       name (assoc :name name)))
                 ((or on-error default-error!) ctx t))
               (finally
                 (cleanup! ctx)
                 (when close?
                   (close! ctx))))))))
      (fn [_ _status]
	        (when-let [ctx @ctx*]
	          (when (compare-and-set! (:open? ctx) true false)
	            (record! ctx :closed))
          (cleanup! ctx))
        (when-let [worker @worker*]
          (future-cancel worker)))
      initial-body))))

(defn once-response
  ([request send-fn]
   (once-response request {} send-fn))
  ([request opts send-fn]
   (managed-response request
                     (merge {:name :once} opts)
                     (fn [ctx]
                       (send-fn ctx)))))
