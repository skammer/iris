(ns agent.llm.attachments
  "Keep unsupported media available to tools without sending binary data to models."
  (:require
   [agent.config :as config]
   [agent.llm.messages :as messages]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)
   (java.util Base64 HexFormat)))

(def ^:private media-capabilities
  {:image :supports-vision
   :audio :supports-audio
   :video :supports-video
   :file :supports-files})

(def ^:private materialize-lock (Object.))

(defn- local-copy! [{:keys [source filename]}]
  (or (when-let [path (:local-path source)]
        (when (.isFile (io/file path)) path))
      (case (:type source)
        :file (.getAbsolutePath (io/file (:value source)))
        :url (:value source)
        :base64
        (let [bytes (.decode (Base64/getDecoder) ^String (:value source))
              digest (.formatHex (HexFormat/of)
                                 (.digest (MessageDigest/getInstance "SHA-256") bytes))
              name* (-> (or (not-empty filename) "attachment")
                        (str/replace #"[^A-Za-z0-9._-]" "_")
                        (str/replace #"^\.+$" "attachment"))
              name* (subs name* 0 (min 160 (count name*)))
              file (io/file (config/data-dir (config/global-config-dir))
                            "attachments" digest name*)]
          (locking materialize-lock
            (when-not (and (.isFile file) (= (.length file) (alength bytes)))
              (io/make-parents file)
              (with-open [out (io/output-stream file)]
                (.write out bytes))))
          (.getAbsolutePath file)))))

(defn- reference-block! [{:keys [type filename source transcript] :as block}]
  {:type :text
   :text (str "[Attachment: " (or filename (name type))
              (when-let [mime (:media-type source)] (str " (" mime ")"))
              "]\n" (if (= :url (:type source)) "URL: " "Local file: ")
              (local-copy! block)
              "\nUse available tools to inspect this attachment if needed."
              (when transcript (str "\nTranscript: " transcript)))})

(defn prepare-messages
  "Adapt each request to its actual model, preserving original history for fallback."
  [input capabilities]
  (mapv (fn [message]
          (update (messages/message->internal message) :content
                  (fn [blocks]
                    (mapv (fn [block]
                            (if-let [capability (media-capabilities (:type block))]
                              (if (true? (get capabilities capability))
                                block
                                (reference-block! block))
                              block))
                          blocks))))
        input))
