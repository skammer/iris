(ns agent.system.lifecycle-test
  (:require [agent.api :as api]
            [agent.channels.core :as channels]
            [agent.chat :as chat]
            [agent.config :as config]
            [agent.cron.service :as cron]
            [agent.health :as health]
            [agent.logging :as logging]
            [agent.memory.idle :as idle]
            [agent.memory.magi-review :as review]
            [agent.persistence.sqlite :as sqlite]
            [agent.runtime.trace :as trace]
            [agent.skills :as skills]
            [agent.system :as system]
            [agent.system.components :as components]
            [agent.system.resources :as resources]
            [agent.telemetry.observer :as observer]
            [clojure.test :refer [deftest is]]))

(defn- base-system []
  (let [ref (atom nil)
        system {:config config/default-config :config-path "test.edn"
                :system-ref ref :reload-state (atom {})
                :health-registry (health/create-registry)}]
    (reset! ref system)
    system))

(deftest rollback-is-reverse-and-preserves-original-error
  (let [order (atom []) error (ex-info "start failed" {})]
    (is (identical? error
          (try (resources/with-rollback
                (fn [own!]
                  (own! :store #(swap! order conj %))
                  (own! :worker #(do (swap! order conj %) (throw (Exception. "close failed"))))
                  (throw error)))
               (catch Exception e e))))
    (is (= [:worker :store] @order))
    (is (= 1 (count (.getSuppressed error))))))

(deftest startup-and-soft-reload-keep-the-same-dependency-contract
  (let [observer-events (atom [])
        cfg (-> (config/load-config nil)
                (assoc-in [:tools :wasm-bundles]
                          {:enabled? true :dev-roots ["export/homeassistant-wasm-skill"]
                           :enabled [] :settings {}}))]
    (with-redefs [config/load-config (constantly cfg)
                  components/create-observer
                  (fn [& _] (reify observer/IObserver
                              (record-event! [this _] (swap! observer-events conj this))
                              (record-metric! [_ _] nil)
                              (flush! [_] nil)
                              (observer-name [_] "audit")))]
      (let [initial (system/create-system)]
        (try
          (doseq [reload? [false true]
                  :let [_ (when reload? (system/reload! initial))
                        current (system/current-system initial)]]
            (is (= (:tool-registry current) ((:tool-registry-fn (:magi-service current)))))
            (is (some #{"homeassistant"} (map :name (skills/skill-catalog (:skills-registry current)))))
            (let [observed (atom [])]
              (with-redefs [sqlite/log-event! (fn [_ event] event)
                            trace/record-event! (fn [value _] (swap! observed conj value))]
                (reset! observer-events [])
                ((:event-sink current) {:event-type :test :entity-type :system})
                (is (= [(:observer current)] @observer-events))
                (is (= [(:trace current)] @observed)))))
          (is (identical? (:chat-service initial) (:chat-service (system/current-system initial))))
          (finally (system/close-system! (system/current-system initial))))))))

(deftest component-construction-closes-acquired-resources
  (doseq [fail-at [:telemetry :tools]]
    (let [closed (atom []) system (base-system)]
      (with-redefs [config/load-config (constantly config/default-config)
                    logging/start! (constantly nil)
                    components/create-store (constantly ::store)
                    sqlite/close-store! #(swap! closed conj %)
                    components/create-telemetry (fn [_] (if (= fail-at :telemetry)
                                                          (throw (Exception. "telemetry")) nil))
                    chat/create-service (constantly ::chat)
                    chat/stop! #(swap! closed conj %)
                    components/build-tool-registry (fn [& _] (throw (Exception. "tools")))]
        (is (thrown? Exception (components/create-system-components
                               nil (:system-ref system) (:reload-state system)
                               (:health-registry system) nil)))
        (is (= (if (= fail-at :telemetry) [::store] [::chat ::store]) @closed))))))

(deftest api-start-rolls-back-partially-started-edges
  (doseq [fail-at [:telegram :cron]]
    (let [closed (atom []) system (assoc (base-system) :telegram-service ::telegram
                                       :memory-idle-service ::idle :memory-magi-review-service ::review
                                       :cron-service ::cron)
          previous @(:system-ref system)]
      (with-redefs [api/start-server! (constantly ::api)
                    api/stop-server! #(swap! closed conj %)
                    channels/start-adapter! (fn [value] (if (= fail-at :telegram)
                                                         (throw (Exception. "telegram")) value))
                    channels/stop-adapter! #(swap! closed conj %)
                    idle/start! identity idle/stop! #(swap! closed conj %)
                    review/start! identity review/stop! #(swap! closed conj %)
                    cron/start! (fn [_] (throw (Exception. "cron")))
                    cron/stop! #(swap! closed conj %)]
        (is (thrown? Exception (system/start-api! system)))
        (is (= (if (= fail-at :telegram) [::telegram ::api]
                   [::cron ::review ::idle ::telegram ::api]) @closed))
        (is (identical? previous @(:system-ref system)))))))

(deftest full-reload-restores-old-runtime-on-start-failure
  (let [old (assoc (base-system) :store ::old-store :chat-service ::old-chat :api-server ::old-api)
        ref (:system-ref old)
        closed (atom [])]
    (reset! ref old)
    (with-redefs [components/create-system-components (fn [& _] (assoc old :store ::new-store
                                                                        :chat-service ::new-chat :api-server nil))
                  api/stop-server! #(swap! closed conj %)
                  chat/stop! #(swap! closed conj %)
                  sqlite/close-store! #(swap! closed conj %)
                  logging/start! (constantly nil)
                  system/start-api! (fn [s] (if (= ::new-store (:store s))
                                            (throw (Exception. "new start failed"))
                                            (assoc s :api-server ::restored-api)))]
      (is (thrown-with-msg? Exception #"new start failed" (#'system/full-reload-now! old {})))
      (is (= ::old-store (:store @ref)))
      (is (= ::old-chat (:chat-service @ref)))
      (is (= ::restored-api (:api-server @ref)))
      (is (= [::old-api ::new-chat ::new-store] @closed)))))

(deftest soft-reload-restores-services-without-stopping-chat
  (let [old (assoc (base-system) :cron-service ::old-cron :chat-service ::chat)
        ref (:system-ref old) calls (atom [])]
    (reset! ref old)
    (with-redefs [config/load-config (constantly config/default-config)
                  components/rebuild-runtime-components (fn [s _] (assoc s :cron-service ::new-cron))
                  logging/start! (constantly nil)
                  cron/running? (constantly true)
                  cron/stop! #(swap! calls conj [:stop %])
                  cron/start! (fn [service]
                                (swap! calls conj [:start service])
                                (when (= service ::new-cron) (throw (Exception. "cron startup"))))
                  chat/stop! (fn [_] (throw (AssertionError. "Chat must survive soft reload")))]
      (is (thrown-with-msg? Exception #"cron startup" (system/reload! old)))
      (is (identical? old @ref))
      (is (= :failed (:status @(:reload-state old))))
      (is (= [[:stop ::old-cron] [:start ::new-cron] [:stop ::new-cron] [:start ::old-cron]] @calls)))))
