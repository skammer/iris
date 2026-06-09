(ns agent.runtime.schema
  "Canonical runtime message and event contracts."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me]))

(def ^:private runtime-event-types
  #{:agent-start
    :agent-end
    :turn-start
    :turn-end
    :message-start
    :message-update
    :message-end
    :nudge-injected
    :guardrail-blocked
    :tool-execution-start
    :tool-execution-update
    :tool-execution-end})

(defn- normalize-token [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (-> value
                                 str/trim
                                 str/lower-case
                                 (str/replace #"_" "-")))
    :else value))

(defn normalize-block-type [value]
  (case (normalize-token value)
    :toolcall :tool-call
    :tool-call :tool-call
    :tool-result :tool-result
    :toolresult :tool-result
    :image-url :image-url
    :image_url :image-url
    :input-image :input-image
    :input_image :input-image
    :input-audio :input-audio
    :input_audio :input-audio
    :input-file :input-file
    :input_file :input-file
    :text :text
    :thinking :thinking
    :image :image
    :audio :audio
    :video :video
    :file :file
    :custom :custom
    (normalize-token value)))

(def ^:private text-block-schema
  [:map {:closed true}
   [:type [:= :text]]
   [:text :string]
   [:annotations {:optional true} :any]])

(def ^:private thinking-block-schema
  [:map {:closed true}
   [:type [:= :thinking]]
   [:text :string]
   [:signature {:optional true} [:maybe :string]]])

(def ^:private media-source-schema
  [:map {:closed true}
   [:type [:enum :url :base64 :file]]
   [:value :string]
   [:media-type {:optional true} [:maybe :string]]])

(def ^:private image-source-schema media-source-schema)

(def ^:private image-block-schema
  [:map {:closed true}
   [:type [:= :image]]
   [:source image-source-schema]
   [:alt {:optional true} [:maybe :string]]
   [:detail {:optional true} [:maybe [:or :keyword :string]]]
   [:filename {:optional true} [:maybe :string]]])

(def ^:private audio-block-schema
  [:map {:closed true}
   [:type [:= :audio]]
   [:source media-source-schema]
   [:alt {:optional true} [:maybe :string]]
   [:transcript {:optional true} [:maybe :string]]
   [:filename {:optional true} [:maybe :string]]])

(def ^:private video-block-schema
  [:map {:closed true}
   [:type [:= :video]]
   [:source media-source-schema]
   [:alt {:optional true} [:maybe :string]]
   [:filename {:optional true} [:maybe :string]]])

(def ^:private file-block-schema
  [:map {:closed true}
   [:type [:= :file]]
   [:source media-source-schema]
   [:alt {:optional true} [:maybe :string]]
   [:filename {:optional true} [:maybe :string]]])

(def ^:private tool-call-block-schema
  [:map {:closed true}
   [:type [:= :tool-call]]
   [:id {:optional true} [:maybe :string]]
   [:name [:or :keyword :string]]
   [:arguments {:optional true} [:map-of :any :any]]
   [:raw {:optional true} :any]])

(def ^:private tool-result-block-schema
  [:map {:closed true}
   [:type [:= :tool-result]]
   [:tool-call-id :string]
   [:name {:optional true} [:maybe [:or :keyword :string]]]
   [:status {:optional true} [:maybe [:or :keyword :string]]]
   [:content :any]
   [:raw {:optional true} :any]])

(def ^:private custom-block-schema
  [:map {:closed true}
   [:type [:= :custom]]
   [:kind [:or :keyword :string]]
   [:data :any]])

(def ^:private message-block-schema
  [:multi {:dispatch :type}
   [:text text-block-schema]
   [:thinking thinking-block-schema]
   [:image image-block-schema]
   [:audio audio-block-schema]
   [:video video-block-schema]
   [:file file-block-schema]
   [:tool-call tool-call-block-schema]
   [:tool-result tool-result-block-schema]
   [:custom custom-block-schema]])

(def ^:private assistant-turn-schema
  [:map {:closed true}
   [:provider [:maybe [:or :keyword :string]]]
   [:model [:maybe :string]]
   [:response-model [:maybe :string]]
   [:response-id [:maybe :string]]
   [:content [:vector message-block-schema]]
   [:usage [:maybe [:map-of :any :any]]]
   [:stop-reason [:maybe [:or :keyword :string]]]
   [:error [:maybe :any]]
   [:timestamp :string]])

(def ^:private stop-reason-schema [:or :keyword :string])
(def ^:private tool-status-schema [:or :keyword :string])
(def ^:private step-schema [:int {:min 0}])

(defn- open-payload [& entries]
  (into [:map {:closed false}] entries))

(defn- event-payload-schema [event-type]
  (case event-type
    :agent-start
    (open-payload [:message-count :int]
                  [:stream :boolean])

    :agent-end
    (open-payload [:stop-reason stop-reason-schema]
                  [:steps {:optional true} :int]
                  [:stream {:optional true} :boolean]
                  [:fallback? {:optional true} :boolean]
                  [:message {:optional true} [:maybe :string]]
                  [:type {:optional true} [:maybe [:or :keyword :string]]])

    :turn-start
    (open-payload [:step step-schema])

    :turn-end
    (open-payload [:step step-schema]
                  [:directives {:optional true} [:sequential :any]]
                  [:receipts {:optional true} [:sequential :any]])

    :message-start
    (open-payload [:role :string]
                  [:step {:optional true} :int]
                  [:fallback? {:optional true} :boolean]
                  [:reason {:optional true} [:maybe :string]])

    :message-update
    (open-payload [:kind {:optional true} [:or :keyword :string]]
                  [:role {:optional true} :string]
                  [:delta {:optional true} :string]
                  [:thinking-delta {:optional true} :string]
                  [:append? {:optional true} :boolean])

    :message-end
    (open-payload [:role :string]
                  [:content {:optional true} :any]
                  [:content-blocks {:optional true} [:sequential :any]]
                  [:final? {:optional true} :boolean]
                  [:tool-turn? {:optional true} :boolean]
                  [:audit? {:optional true} :boolean]
                  [:stop-reason {:optional true} stop-reason-schema])

    :nudge-injected
    (open-payload [:step step-schema]
                  [:reason :string]
                  [:content :string])

    :guardrail-blocked
    (open-payload [:step step-schema]
                  [:action :string]
                  [:reason :string]
                  [:fingerprint {:optional true} :any])

    :tool-execution-start
    (open-payload [:tool-name :string]
                  [:tool-call-id :string]
                  [:source-index {:optional true} :int])

    :tool-execution-update
    (open-payload [:kind {:optional true} [:or :keyword :string]]
                  [:tool-name {:optional true} :string]
                  [:tool-call-id {:optional true} :string])

    :tool-execution-end
    (open-payload [:tool-name :string]
                  [:tool-call-id :string]
                  [:status tool-status-schema]
                  [:duration-ms {:optional true} number?]
                  [:tool-call {:optional true} :any]
                  [:receipt {:optional true} :any]
                  [:error {:optional true} [:maybe :string]]
                  [:error-type {:optional true} [:maybe [:or :keyword :string]]])))

(defn- event-schema [event-type]
  [:map {:closed true}
   [:event-type [:= event-type]]
   [:entity-type {:optional true} [:maybe [:or :keyword :string]]]
   [:entity-id {:optional true} [:maybe :string]]
   [:request-id {:optional true} [:maybe :string]]
   [:timestamp :string]
   [:payload {:optional true} (event-payload-schema event-type)]])

(def ^:private runtime-event-schema
  (into [:multi {:dispatch :event-type}]
        (map (fn [event-type] [event-type (event-schema event-type)])
             runtime-event-types)))

(def ^:private message-block-validator (m/validator message-block-schema))
(def ^:private message-block-explainer (m/explainer message-block-schema))
(def ^:private assistant-turn-validator (m/validator assistant-turn-schema))
(def ^:private assistant-turn-explainer (m/explainer assistant-turn-schema))
(def ^:private runtime-event-validator (m/validator runtime-event-schema))
(def ^:private runtime-event-explainer (m/explainer runtime-event-schema))

(defn- validation-error [label errors value]
  (ex-info (str label " failed schema validation")
           {:type :validation-failed
            :schema label
            :errors errors
            :value value}))

(defn validate!
  [schema value label]
  (if (m/validate schema value)
    value
    (throw (validation-error label (me/humanize (m/explain schema value)) value))))

(defn- validate-compiled!
  [validator explainer value label]
  (if (validator value)
    value
    (throw (validation-error label (me/humanize (explainer value)) value))))

(defn validate-message-block! [block]
  (validate-compiled! message-block-validator message-block-explainer block :message-block))

(defn validate-assistant-turn! [turn]
  (validate-compiled! assistant-turn-validator assistant-turn-explainer turn :assistant-turn))

(defn validate-runtime-event! [event]
  (validate-compiled! runtime-event-validator runtime-event-explainer event :runtime-event))

(defn- data-uri-source [value fallback-media-type]
  (let [value* (str (or value ""))]
    (if-let [[_ media-type data] (re-matches #"(?is)^data:([^;,]+)?(?:;base64)?,(.*)$" value*)]
      (cond-> {:type :base64
               :value data}
        (not (str/blank? media-type)) (assoc :media-type media-type))
      (cond-> {:type :url
               :value value*}
        (not (str/blank? fallback-media-type)) (assoc :media-type fallback-media-type)))))

(defn- base64-source [value media-type]
  (let [value* (str (or value ""))]
    (if (str/starts-with? (str/lower-case value*) "data:")
      (data-uri-source value* media-type)
      (cond-> {:type :base64
               :value value*}
        (not (str/blank? media-type)) (assoc :media-type media-type)))))

(defn- audio-media-type [format]
  (case (some-> format str/lower-case)
    "wav" "audio/wav"
    "mp3" "audio/mpeg"
    "flac" "audio/flac"
    "ogg" "audio/ogg"
    "opus" "audio/opus"
    nil))

(defn- filename-extension [filename]
  (some-> (re-find #"(?i)\.([a-z0-9]+)$" (or filename ""))
          second
          str/lower-case))

(defn- data-uri-media-type [value]
  (some-> (re-matches #"(?is)^data:([^;,]+)?(?:;base64)?,.*$" (str (or value "")))
          second
          str/lower-case))

(defn- video-file? [file-data media-type filename]
  (or (some-> media-type str/lower-case (str/starts-with? "video/"))
      (some-> (data-uri-media-type file-data) (str/starts-with? "video/"))
      (contains? #{"mp4" "webm" "mov" "m4v" "mpeg" "mpg"} (filename-extension filename))))

(defn- provider-content-part [block]
  (case (:type block)
    :image-url
    (let [image-url (:image_url block)]
      (cond-> {:type :image
               :source (data-uri-source (:url image-url) nil)}
        (:detail image-url) (assoc :detail (:detail image-url))
        (:detail block) (assoc :detail (:detail block))))

    :input-image
    (cond-> {:type :image
             :source (data-uri-source (:image_url block) nil)}
      (:detail block) (assoc :detail (:detail block)))

    :input-audio
    (let [input-audio (:input_audio block)
          media-type (audio-media-type (:format input-audio))]
      (cond-> {:type :audio
               :source (base64-source (:data input-audio) media-type)}
        (:filename block) (assoc :filename (:filename block))))

    (:file :input-file)
    (if (and (= :file (:type block)) (:source block))
      block
      (let [file (or (:file block) block)
            file-data (or (:file_data file) (:file_data block))
            filename (or (:filename file) (:filename block))
            media-type (or (:media-type file) (:media_type file)
                           (:media-type block) (:media_type block))
            type* (if (video-file? file-data media-type filename) :video :file)]
        (cond-> {:type type*
                 :source (base64-source file-data media-type)}
          filename (assoc :filename filename))))

    block))

(defn normalize-block
  [block]
  (let [block* (provider-content-part
                (cond
                  (string? block) {:type :text :text block}
                  (map? block) (update block :type normalize-block-type)
                  :else {:type :custom :kind :value :data block}))]
    (case (:type block*)
      :text (-> {:type :text
                 :text (str (or (:text block*) (:content block*) ""))}
                (cond-> (contains? block* :annotations)
                  (assoc :annotations (:annotations block*))))
      :thinking (-> {:type :thinking
                     :text (str (or (:text block*)
                                    (:thinking block*)
                                    (:content block*)
                                    ""))}
                    (cond-> (contains? block* :signature)
                      (assoc :signature (:signature block*))))
      :image (-> {:type :image
                  :source (update (:source block*) :type normalize-token)}
                 (cond-> (contains? block* :alt) (assoc :alt (:alt block*))
                         (contains? block* :detail) (assoc :detail (:detail block*))
                         (contains? block* :filename) (assoc :filename (:filename block*))))
      :audio (-> {:type :audio
                  :source (update (:source block*) :type normalize-token)}
                 (cond-> (contains? block* :alt) (assoc :alt (:alt block*))
                         (contains? block* :transcript) (assoc :transcript (:transcript block*))
                         (contains? block* :filename) (assoc :filename (:filename block*))))
      :video (-> {:type :video
                  :source (update (:source block*) :type normalize-token)}
                 (cond-> (contains? block* :alt) (assoc :alt (:alt block*))
                         (contains? block* :filename) (assoc :filename (:filename block*))))
      :file (-> {:type :file
                 :source (update (:source block*) :type normalize-token)}
                (cond-> (contains? block* :alt) (assoc :alt (:alt block*))
                        (contains? block* :filename) (assoc :filename (:filename block*))))
      :tool-call (cond-> {:type :tool-call
                          :name (or (:name block*) (:tool-name block*) (:tool_name block*))
                          :arguments (or (:arguments block*) (:input block*) (:args block*) {})}
                   (contains? block* :id) (assoc :id (:id block*))
                   (contains? block* :raw) (assoc :raw (:raw block*)))
      :tool-result (cond-> {:type :tool-result
                            :tool-call-id (or (:tool-call-id block*) (:tool_call_id block*))
                            :content (:content block*)}
                     (contains? block* :name) (assoc :name (:name block*))
                     (contains? block* :status) (assoc :status (:status block*))
                     (contains? block* :raw) (assoc :raw (:raw block*)))
      :custom {:type :custom
               :kind (or (:kind block*) :unknown)
               :data (or (:data block*) (dissoc block* :type :kind))})))

(defn normalize-content
  [content]
  (mapv (comp validate-message-block! normalize-block)
        (cond
          (nil? content) []
          (vector? content) content
          (sequential? content) (vec content)
          :else [content])))
