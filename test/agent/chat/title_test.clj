(ns agent.chat.title-test
  (:require [agent.chat.title :as title]
            [agent.llm.core :as llm]
            [agent.persistence.sqlite :as sqlite]
            [agent.sessions.service :as sessions]
            [clojure.test :refer [deftest is testing]]))

(defn- with-store [f]
  (let [store (sqlite/create-store {:path ":memory:"})]
    (try (f store) (finally (sqlite/close-store! store)))))

(defn- exchange! [store id]
  (sqlite/append-message! store id "user" "Как ускорить поиск по заметкам?")
  (sqlite/append-message! store id "assistant" "Добавить индекс и проверить план запроса."))

(defn- system-for [store]
  {:store store :event-sink #(sqlite/log-event! store %) :config {:llm {:provider :test :model "test"}}})

(deftest telegram-title-after-two-exchanges-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "Telegram: skammer")
            id (:id session)
            calls (atom [])
            system (system-for store)]
        (with-redefs [llm/invoke (fn [_ request]
                                  (swap! calls conj request)
                                  {:content "\"Ускорение поиска по заметкам\""})]
          (exchange! store id)
          (title/maybe-generate-title! system id "test")
          (is (empty? @calls))
          (is (= "Telegram: skammer" (:title (sqlite/get-session store id))))
          (exchange! store id)
          (title/maybe-generate-title! system id "test")
          (is (= "Ускорение поиска по заметкам" (:title (sqlite/get-session store id))))
          (is (= 1 (count @calls)))
          (is (true? (get-in (first @calls) [:metadata :chat-title])))
          (title/maybe-generate-title! system id "test")
          (is (= 1 (count @calls))))))))

(deftest preserve-explicit-title-and-reject-stale-generation-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "Telegram: skammer")
            id (:id session)
            system (system-for store)]
        (dotimes [_ 2] (exchange! store id))
        (with-redefs [llm/invoke (fn [_ _]
                                  (sessions/set-generated-title! system id "Telegram: skammer" "Моё название")
                                  {:content "Запоздалое название"})]
          (is (nil? (title/maybe-generate-title! system id "test")))
          (is (= "Моё название" (:title (sqlite/get-session store id)))))
        (with-redefs [llm/invoke (fn [& _] (throw (AssertionError. "Explicit title must not invoke LLM")))]
          (title/maybe-generate-title! system id "test"))
        (is (= 1 (count (filter #(= "session.title.updated" (:event-type %))
                               (sqlite/list-events store {:entity-type :session :entity-id id})))))))))

(deftest title-failure-is-best-effort-test
  (with-store
    (fn [store]
      (let [id (:id (sqlite/create-session! store "Telegram: skammer"))
            errors (atom [])
            system (assoc (system-for store) :event-sink #(swap! errors conj %))]
        (dotimes [_ 2] (exchange! store id))
        (testing "Empty output leaves placeholder available for retry"
          (with-redefs [llm/invoke (fn [& _] {:content ""})]
            (title/maybe-generate-title! system id "test"))
          (is (= "Telegram: skammer" (:title (sqlite/get-session store id)))))
        (testing "Provider failure does not fail the chat turn"
          (with-redefs [llm/invoke (fn [& _] (throw (ex-info "unavailable" {})))]
            (title/maybe-generate-title! system id "test"))
          (is (= :title-generation (get-in (first @errors) [:payload :operation]))))))))
