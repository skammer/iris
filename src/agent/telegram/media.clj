(ns agent.telegram.media
  "Telegram inbound media normalization for LLM message content."
  (:require
   [agent.telegram.api :as tg-api]
   [clojure.string :as str])
  (:import
   (java.util Base64)))

(def ^:private default-max-download-bytes (* 20 1024 1024))

(defn- max-download-bytes [config]
  (long (or (:max-download-bytes config) default-max-download-bytes)))

(defn- mime-kind [mime-type fallback]
  (let [mime (str/lower-case (or mime-type ""))]
    (cond
      (str/starts-with? mime "image/") :image
      (str/starts-with? mime "audio/") :audio
      (str/starts-with? mime "video/") :video
      :else fallback)))

(defn- photo-size [photo]
  (or (:file_size photo)
      (* (long (or (:width photo) 0))
         (long (or (:height photo) 0)))))

(defn- largest-photo [photos]
  (last (sort-by photo-size photos)))

(defn- descriptor
  [kind media & [{:keys [media-type filename alt]}]]
  (when-let [file-id (:file_id media)]
    {:kind kind
     :file-id file-id
     :file-size (:file_size media)
     :media-type media-type
     :filename filename
     :alt alt}))

(defn- sticker-media-type [sticker]
  (cond
    (:is_video sticker) "video/webm"
    (:is_animated sticker) "application/x-tgsticker"
    :else "image/webp"))

(defn descriptors [message]
  (cond-> []
    (seq (:photo message))
    (conj (descriptor :image
                      (largest-photo (:photo message))
                      {:media-type "image/jpeg"
                       :alt "Telegram photo"}))

    (:document message)
    (conj (let [doc (:document message)
                mime (:mime_type doc)]
            (descriptor (mime-kind mime :file)
                        doc
                        {:media-type mime
                         :filename (:file_name doc)
                         :alt (:file_name doc)})))

    (:audio message)
    (conj (let [audio (:audio message)]
            (descriptor :audio
                        audio
                        {:media-type (:mime_type audio)
                         :filename (:file_name audio)
                         :alt (:title audio)})))

    (:voice message)
    (conj (descriptor :audio
                      (:voice message)
                      {:media-type (or (get-in message [:voice :mime_type]) "audio/ogg")
                       :alt "Telegram voice message"}))

    (:video message)
    (conj (let [video (:video message)]
            (descriptor :video
                        video
                        {:media-type (:mime_type video)
                         :filename (:file_name video)
                         :alt (:file_name video)})))

    (:video_note message)
    (conj (descriptor :video
                      (:video_note message)
                      {:media-type "video/mp4"
                       :alt "Telegram video note"}))

    (:animation message)
    (conj (let [animation (:animation message)]
            (descriptor :video
                        animation
                        {:media-type (:mime_type animation)
                         :filename (:file_name animation)
                         :alt (:file_name animation)})))

    (:sticker message)
    (conj (let [sticker (:sticker message)
                mime (sticker-media-type sticker)]
            (descriptor (mime-kind mime :file)
                        sticker
                        {:media-type mime
                         :alt (or (:emoji sticker) "Telegram sticker")})))))

(defn count-media [message]
  (count (descriptors message)))

(defn- ensure-download-size! [limit {:keys [file-size file-id]}]
  (when (and file-size (> (long file-size) limit))
    (throw (ex-info "Telegram media is too large to send to LLM"
                    {:type :telegram-media-too-large
                     :file-id file-id
                     :file-size file-size
                     :max-download-bytes limit}))))

(defn- infer-filename [descriptor file-path]
  (or (:filename descriptor)
      (some-> file-path (str/split #"/") last not-empty)
      (str (:file-id descriptor))))

(defn- media-block!
  [config opts descriptor]
  (let [token (:bot-token config)
        limit (max-download-bytes config)
        get-file (or (:get-file-fn opts) tg-api/get-file!)
        download-file (or (:download-file-fn opts) tg-api/download-file!)]
    (ensure-download-size! limit descriptor)
    (let [file (get-file token (:file-id descriptor))
          file-path (:file_path file)]
      (ensure-download-size! limit (assoc descriptor :file-size (or (:file_size file)
                                                                    (:file-size descriptor))))
      (when (str/blank? file-path)
        (throw (ex-info "Telegram getFile response missing file_path"
                        {:type :telegram-file-path-missing
                         :file-id (:file-id descriptor)})))
      (let [bytes (download-file token file-path)
            filename (infer-filename descriptor file-path)
            media-type (or (:media-type descriptor) "application/octet-stream")]
        (cond-> {:type (:kind descriptor)
                 :source {:type :base64
                          :media-type media-type
                          :value (.encodeToString (Base64/getEncoder) bytes)}}
          (:alt descriptor) (assoc :alt (:alt descriptor))
          filename (assoc :filename filename))))))

(defn- default-media-prompt [descriptors]
  (let [kinds (->> descriptors (map (comp name :kind)) distinct (str/join ", "))]
    (str "Analyze attached " kinds ".")))

(defn user-content!
  [config opts message]
  (let [text (or (:text message) (:caption message))
        descriptors* (vec (keep identity (descriptors message)))
        media-blocks (mapv #(media-block! config opts %) descriptors*)
        prompt (or (some-> text str/trim not-empty)
                   (when (seq media-blocks) (default-media-prompt descriptors*)))]
    (if (seq media-blocks)
      (cond-> []
        prompt (conj {:type :text :text prompt})
        true (into media-blocks))
      text)))

(defn processable-message? [message]
  (or (not (str/blank? (:text message)))
      (not (str/blank? (:caption message)))
      (seq (descriptors message))))
