(ns agent.tools.common.wasm
  "Disabled-by-default Endive WASM execution tool."
  (:require
   [agent.tools.core :as tools]
   [clojure.string :as str]
   [endive-clj.core :as wasm])
  (:import
   [java.util Base64]))

(def default-config
  {:enabled false
   :timeout-ms 30000
   :max-wasm-bytes 1048576
   :max-stdout-bytes 1048576
   :max-stderr-bytes 1048576
   :max-memory-pages 64
   :wasi {:args []
          :env {}
          :stdin ""
          :fs {:mounts []
               :allowed-roots []
               :max-copy-bytes 10485760}}
   :network {:enabled? false
             :allowed-hosts []
             :allow-private? false
             :timeout-ms 10000
             :max-response-bytes 1048576}})

(def ^:private modes #{:invoke :wasi})

(defn- normalize-mode [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    (nil? value) :invoke
    :else value))

(defn- clean-string [value]
  (some-> value str str/trim not-empty))

(defn- decode-base64! [value max-bytes]
  (let [text (clean-string value)]
    (when-not text
      (throw (tools/validation-error "wasm-base64 is required" {})))
    (try
      (let [bytes (.decode (Base64/getDecoder) text)]
        (when (< max-bytes (alength bytes))
          (throw (tools/validation-error "wasm module exceeds max bytes"
                                         {:max-wasm-bytes max-bytes
                                          :actual-bytes (alength bytes)})))
        bytes)
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (throw (tools/validation-error "wasm-base64 must be valid Base64"
                                       {:message (.getMessage e)}))))))

(defn- sanitize-wasi-input [cfg input-wasi]
  (let [cfg-wasi (:wasi cfg)
        input-wasi* (or input-wasi {})
        fs-cfg (:fs cfg-wasi)
        fs-input (:fs input-wasi*)]
    (assoc (merge cfg-wasi (select-keys input-wasi* [:args :env :stdin :stdout :stderr]))
           :fs (merge fs-cfg (select-keys fs-input [:mounts :max-copy-bytes])))))

(defn- wasm-options [cfg input]
  {:engine :interpreter
   :limits {:timeout-ms (long (or (:timeout-ms input) (:timeout-ms cfg)))
            :max-stdout-bytes (:max-stdout-bytes cfg)
            :max-stderr-bytes (:max-stderr-bytes cfg)
            :max-memory-pages (:max-memory-pages cfg)}
   :wasi (sanitize-wasi-input cfg (:wasi input))
   :network (:network cfg)
   :host-functions []})

(defn- validate-input [cfg input]
  (let [mode (normalize-mode (:mode input))
        bytes (decode-base64! (:wasm-base64 input) (:max-wasm-bytes cfg))]
    (when-not (contains? modes mode)
      (throw (tools/validation-error "mode must be invoke or wasi"
                                     {:mode (:mode input)})))
    (when (and (= :invoke mode) (str/blank? (clean-string (:export input))))
      (throw (tools/validation-error "export is required for invoke mode" {})))
    (assoc input
           :mode mode
           :wasm-bytes bytes
           :args (mapv long (or (:args input) [])))))

(defn- compact-text [text]
  (let [text* (or text "")]
    (if (< 2000 (count text*))
      (str (subs text* 0 2000) "\n...[truncated]")
      text*)))

(defn- invoke-text [result]
  (str "wasm.invoke ok: " (:export result) " -> " (pr-str (:results result))))

(defn- wasi-text [result]
  (str/join "\n"
            (cond-> [(str "wasm.wasi exit=" (:exit-code result)
                          " stdout-truncated=" (boolean (:stdout-truncated? result))
                          " stderr-truncated=" (boolean (:stderr-truncated? result)))]
              (seq (:stdout result)) (conj (str "stdout:\n" (compact-text (:stdout result))))
              (seq (:stderr result)) (conj (str "stderr:\n" (compact-text (:stderr result)))))))

(defn create-wasm-tool
  [cfg]
  (let [config (merge default-config cfg)]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :wasm_execute
       "Execute a WebAssembly module with Endive. Disabled unless :tools :wasm :enabled is true. Host functions and network are controlled by server config, not tool input."
       :category :system
       :timeout-ms (:timeout-ms config)
       :required-permissions #{:wasm-execute}
       :input-schema [:map {:closed true}
                      [:wasm-base64 :string]
                      [:mode {:optional true}
                       [:or [:enum :invoke :wasi]
                        [:enum "invoke" "wasi"]]]
                      [:export {:optional true} :string]
                      [:args {:optional true} [:vector :int]]
                      [:wasi {:optional true} [:map-of :any :any]]
                      [:timeout-ms {:optional true} [:int {:min 1}]]]
       :operation :act
       :approval-sensitive? true
       :routing-categories #{:run :code :tools})
      :validate-fn (partial validate-input config)
      :health-fn (fn []
                   {:healthy true
                    :details (select-keys config [:enabled :timeout-ms :max-wasm-bytes
                                                  :max-memory-pages :network])})
      :execute-fn
      (fn [{:keys [mode wasm-bytes export args] :as input} _context]
        (let [options (wasm-options config input)]
          (case mode
            :invoke
            (let [runtime (wasm/instantiate wasm-bytes options)]
              (try
                (let [result (wasm/invoke runtime export args)]
                  {:result result
                   :result-text (invoke-text result)})
                (finally
                  (wasm/close! runtime))))

            :wasi
            (let [result (wasm/run-wasi wasm-bytes options)]
              {:result result
               :result-text (wasi-text result)}))))})))
