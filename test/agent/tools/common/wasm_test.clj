(ns agent.tools.common.wasm-test
  (:require
   [agent.config :as config]
   [agent.tools.common.wasm :as wasm-tool]
   [agent.tools.core :as tools]
   [agent.tools.service :as tool-service]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]])
  (:import
   [java.util Base64]))

(defn- ba [xs]
  (byte-array (map unchecked-byte xs)))

(def add-wasm
  (ba [0x00 0x61 0x73 0x6d 0x01 0x00 0x00 0x00
       0x01 0x07 0x01 0x60 0x02 0x7f 0x7f 0x01 0x7f
       0x03 0x02 0x01 0x00
       0x07 0x07 0x01 0x03 0x61 0x64 0x64 0x00 0x00
       0x0a 0x09 0x01 0x07 0x00 0x20 0x00 0x20 0x01 0x6a 0x0b]))

(def start-wasm
  (ba [0x00 0x61 0x73 0x6d 0x01 0x00 0x00 0x00
       0x01 0x04 0x01 0x60 0x00 0x00
       0x03 0x02 0x01 0x00
       0x08 0x01 0x00
       0x0a 0x04 0x01 0x02 0x00 0x0b]))

(defn- b64 [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(deftest wasm-tool-invoke-test
  (let [registry (-> (tools/create-registry)
                     (tools/register-tool (wasm-tool/create-wasm-tool {:enabled true})))
        result (tools/execute-tool registry
                                   :wasm_execute
                                   {:wasm-base64 (b64 add-wasm)
                                    :export "add"
                                    :args [2 40]}
                                   {:permissions #{:wasm-execute}
                                    :yolo? true})]
    (is (= {:export "add" :results [42]} (:result result)))
    (is (= "wasm.invoke ok: add -> [42]" (:result-text result)))))

(deftest wasm-tool-wasi-test
  (let [registry (-> (tools/create-registry)
                     (tools/register-tool (wasm-tool/create-wasm-tool {:enabled true})))
        result (tools/execute-tool registry
                                   :wasm_execute
                                   {:wasm-base64 (b64 start-wasm)
                                    :mode "wasi"}
                                   {:permissions #{:wasm-execute}
                                    :yolo? true})]
    (is (= 0 (get-in result [:result :exit-code])))
    (is (str/includes? (:result-text result) "wasm.wasi exit=0"))))

(deftest registry-gates-wasm-tool-by-config-test
  (let [default-registry (tool-service/create-tool-registry
                          {:cfg (:tools config/default-config)})
        enabled-registry (tool-service/create-tool-registry
                          {:cfg (assoc-in (:tools config/default-config)
                                          [:wasm :enabled]
                                          true)})]
    (is (nil? (tools/get-tool default-registry :wasm_execute)))
    (is (some? (tools/get-tool enabled-registry :wasm_execute)))))
