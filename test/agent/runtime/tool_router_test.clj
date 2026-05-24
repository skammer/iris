(ns agent.runtime.tool-router-test
  (:require
   [agent.runtime.tool-router :as tool-router]
   [clojure.test :refer :all]))

(def tools
  [{:name :fs :category :system}
   {:name :shell :category :system}
   {:name :http :category :system}])

(deftest route-tools-adds-respond-and-shrinks-schema-test
  (let [routed (tool-router/route-tools
                {:tools tools
                 :profile {:respond-tool? true
                           :tool-routing? true}
                 :messages [{:role "user" :content "read file"}]})]
    (is (= #{:fs :http :respond} (:allowed-tools routed)))))

(deftest route-tools-fallback-all-test
  (let [routed (tool-router/route-tools
                {:tools tools
                 :profile {:respond-tool? true
                           :tool-routing? false}
                 :messages []})]
    (is (= #{:fs :shell :http :respond} (:allowed-tools routed)))))
