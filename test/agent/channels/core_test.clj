(ns agent.channels.core-test
  (:require
   [agent.channels.core :as channels]
   [clojure.test :refer :all]))

(deftest registry-registers-adapters-test
  (let [adapter (channels/create-adapter
                 {:description (channels/create-adapter-description
                                :telegram
                                "Telegram"
                                :polling
                                #{:supports-outbound :supports-streaming})
                  :health-fn (fn [] {:healthy true
                                     :enabled false})})
        registry (-> (channels/create-registry)
                     (channels/register-adapter adapter))
        listed (channels/list-adapters registry)
        health (channels/registry-health registry)]
    (is (= [:telegram] (mapv :name listed)))
    (is (= 1 (:count health)))
    (is (true? (:healthy health)))))
