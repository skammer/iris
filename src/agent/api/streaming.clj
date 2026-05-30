(ns agent.api.streaming
  "Server-Sent Events helpers for http-kit channels."
  (:require
   [agent.broker.core :as broker]
   [agent.streaming.metrics :as metrics]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str]
   [org.httpkit.server :as http-kit]))

(declare send-sse-chunk!)

(defn metrics []
  (metrics/metrics))

(defn reset-metrics! []
  (metrics/reset-metrics!))

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

(defn close! [target]
  (when-let [channel (target-channel target)]
    (when (or (not (context? target))
              (compare-and-set! (:open? target) true false))
      (when (context? target)
        (metrics/record! :closed))
      (http-kit/close channel))))

(defn- cleanup! [ctx]
  (when (and (context? ctx)
             (compare-and-set! (:cleaned? ctx) false true))
    (doseq [cleanup (reverse @(:cleanups ctx))]
      (try
        (cleanup)
        (catch Throwable _
          (metrics/record! :cleanup-errors))))))

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
        (metrics/add-count! :dropped-events (some-> subscription :dropped-count deref))
        (broker/unsubscribe! broker-instance subscription)
        (metrics/record! :unsubscribed)))
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

(defn- default-error! [ctx error]
  (when (open? ctx)
    (send-sse-chunk! ctx {:type "error"
                          :error "stream_error"
                          :message (.getMessage error)})))

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
          (metrics/record! :dropped-events))
        sent?)
      (catch Throwable _
        (metrics/record! :send-errors)
        false))
    (do
      (metrics/record! :dropped-events)
      false)))

(defn send-sse-chunk! [channel payload]
  (send-sse-text! channel (str "data: " (json/generate-string payload) "\n\n")))

(defn send-sse-done! [channel]
  (send-sse-text! channel "data: [DONE]\n\n"))

(defn send-datastar-patch! [channel html]
  (send-sse-text! channel (datastar-patch-frame html)))

(defn send-sse-error! [channel code message]
  (send-sse-chunk! channel {:type "error"
                            :error code
                            :message message}))

(defn send-sse-terminal! [channel payload]
  (send-sse-chunk! channel (merge {:type "terminal"} payload))
  (send-sse-done! channel)
  (close! channel))

(defn managed-response
  ([request stream-fn]
   (managed-response request {} stream-fn))
  ([request {:keys [initial-body on-error close? name]
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
                   :cleanups (atom [])}]
          (reset! ctx* ctx)
          (metrics/record! :opened)
          (reset!
           worker*
           (future
             (try
               (stream-fn ctx)
               (metrics/record! :completed)
               (catch Throwable t
                 (metrics/record! :errors)
                 ((or on-error default-error!) ctx t))
               (finally
                 (cleanup! ctx)
                 (when close?
                   (close! ctx))))))))
      (fn [_ _status]
        (when-let [ctx @ctx*]
          (when (compare-and-set! (:open? ctx) true false)
            (metrics/record! :closed))
          (cleanup! ctx))
        (when-let [worker @worker*]
          (future-cancel worker)))
      initial-body))))

(defn once-response [request send-fn]
  (managed-response request
                    {:name :once}
                    (fn [ctx]
                      (send-fn ctx))))
