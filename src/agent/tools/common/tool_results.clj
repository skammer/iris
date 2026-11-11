(ns agent.tools.common.tool-results
  "Durable, session-scoped recovery for tool output removed from model context."
  (:require [agent.runtime.messages :as messages]
            [agent.tools.core :as tools]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.util UUID)))

(defn- result-file [data-dir result-id]
  (when-not (and (string? result-id)
                 (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" result-id))
    (throw (tools/validation-error "Invalid result_id" {})))
  (io/file data-dir "tool-results" (str result-id ".edn")))

(defn preserve!
  "Persist before advertising recovery. On storage failure retain the full inline result."
  [data-dir session-id receipt]
  (let [text (messages/full-tool-output-content receipt)]
    (if (and data-dir session-id (> (count text) 2000)
             (not= "tool_result_read" (some-> (:tool-name receipt) name)))
      (try
        (let [id (str (UUID/randomUUID))
              file (result-file data-dir id)]
          (io/make-parents file)
          (spit file (pr-str {:session-id session-id :text text}) :encoding "UTF-8")
          (assoc receipt :output-ref id))
        (catch Exception _ receipt))
      receipt)))

(defn read-result
  [data-dir session-id {:keys [result_id offset limit] :or {offset 0 limit 16000}}]
  (when-not (and session-id (integer? offset) (<= 0 offset)
                 (integer? limit) (<= 1 limit 24000))
    (throw (tools/validation-error "Session, nonnegative offset and limit 1..24000 required" {})))
  (let [file (result-file data-dir result_id)
        stored (when (.isFile file) (edn/read-string (slurp file :encoding "UTF-8")))]
    (when-not (= session-id (:session-id stored))
      (throw (tools/validation-error "Result not found in this session" {})))
    (let [text (:text stored)
          total (count text)]
      (when (or (> offset total)
                (and (< offset total) (Character/isLowSurrogate (.charAt ^String text offset))))
        (throw (tools/validation-error "offset must be within total_chars and at a character boundary" {:total_chars total})))
      (let [end0 (min total (+ offset limit))
            end (if (and (< end0 total) (pos? end0)
                         (Character/isHighSurrogate (.charAt ^String text (dec end0)))
                         (Character/isLowSurrogate (.charAt ^String text end0)))
                  (inc end0)
                  end0)]
        {:result_id result_id :offset offset :next_offset end :total_chars total
         :eof (= end total) :content (subs text offset end)}))))

(defn create-read-tool [data-dir]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :tool_result_read
     "Read a saved tool result in this session without re-executing the original tool. Use result_id from the result reference; start offset=0 and follow next_offset until eof=true. Offsets use UTF-16 positions; pages never split a Unicode character."
     :category :system :operation :read :approval-sensitive? false
     :input-schema [:map
                    [:result_id :string]
                    [:offset {:optional true} [:int {:min 0}]]
                    [:limit {:optional true} [:int {:min 1 :max 24000}]]])
    :execute-fn (fn [input context]
                  (let [result (read-result data-dir (:session-id context) input)]
                    ;; Avoid JSON escaping doubling a page beyond the inline budget.
                    (assoc result :result-text
                           (str "result_id=" (:result_id result)
                                " offset=" (:offset result)
                                " next_offset=" (:next_offset result)
                                " total_chars=" (:total_chars result)
                                " eof=" (:eof result) "\n"
                                (:content result)))))}))
