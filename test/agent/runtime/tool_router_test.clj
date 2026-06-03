(ns agent.runtime.tool-router-test
  (:require
   [agent.runtime.tool-router :as tool-router]
   [clojure.test :refer :all]))

(def tools
  [{:name :fs_read :category :system}
   {:name :fs_list :category :system}
   {:name :fs_write :category :system}
   {:name :shell :category :system}
   {:name :http :category :system}
   {:name :telegram_send_document :category :messaging}])

(deftest route-tools-adds-respond-and-shrinks-schema-test
  (let [routed (tool-router/route-tools
                {:tools tools
                 :profile {:respond-tool? true
                           :tool-routing? true}
                 :messages [{:role "user" :content "read file"}]})]
    (is (= #{:fs_read :fs_list :http :respond} (:allowed-tools routed)))))

(deftest route-tools-keeps-telegram-document-for-russian-send-request-test
  (let [routed (tool-router/route-tools
                {:tools tools
                 :profile {:respond-tool? true
                           :tool-routing? true}
                 :messages [{:role "user"
                             :content "попробуем ещё раз. А отправь мне какой-нибудь документ."}]})]
    (is (= #{:telegram_send_document :respond} (:allowed-tools routed)))))

(deftest route-tools-keeps-read-and-telegram-for-russian-find-and-send-request-test
  (let [routed (tool-router/route-tools
                {:tools tools
                 :profile {:respond-tool? true
                           :tool-routing? true}
                 :messages [{:role "user"
                             :content "попробуем ещё раз. А отправь мне какой-нибудь документ. Ну типа найди в ~ и отправь"}]})]
    (is (= #{:fs_read :fs_list :http :telegram_send_document :respond} (:allowed-tools routed)))))

(deftest route-tools-fallback-all-test
  (let [routed (tool-router/route-tools
                {:tools tools
                 :profile {:respond-tool? true
                           :tool-routing? false}
                 :messages []})]
    (is (= #{:fs_read :fs_list :fs_write :shell :http :telegram_send_document :respond} (:allowed-tools routed)))))
