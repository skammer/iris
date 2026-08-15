(ns agent.memory.user-profile-test
  (:require
   [agent.llm.core :as llm]
   [agent.memory.user-profile :as user-profile]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defrecord ProfileProvider [responses requests]
  llm/ILLMProviderInvoke
  (invoke [_ request]
    (swap! requests conj request)
    (let [[before _] (swap-vals! responses #(vec (rest %)))]
      {:content (or (first before) (json/generate-string {:operations []}))}))
  (generate [this messages opts]
    (llm/invoke this (assoc opts :messages messages))))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-user-profile-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest learns-bounded-managed-profile-and-preserves-user-content-test
  (let [root (temp-dir)
        file (io/file root "USER.md")
        responses (atom
                   [(json/generate-string
                     {:operations
                      [{:operation "upsert"
                        :old nil
                        :value "Prefers concise answers in Russian."
                        :confidence 0.97
                        :evidence "Explicit request"}
                       {:operation "upsert"
                        :old nil
                        :value "API token: sk-secret"
                        :confidence 0.99
                        :evidence "Must never persist"}
                       {:operation "upsert"
                        :old nil
                        :value "Might enjoy long essays."
                        :confidence 0.5
                        :evidence "Weak inference"}]})
                    (json/generate-string
                     {:operations
                      [{:operation "upsert"
                        :old "Prefers concise answers in Russian."
                        :value "Prefers very concise answers in Russian."
                        :confidence 0.95
                        :evidence "Explicit correction"}]})])
        requests (atom [])
        refreshed (atom 0)
        provider (->ProfileProvider responses requests)
        service (user-profile/create-service
                 {:config {:enabled true}
                  :config-dir (.getAbsolutePath root)
                  :model "profile-model"
                  :provider provider
                  :on-update #(swap! refreshed inc)})]
    (try
      (spit file "# USER\nname: Test User\ntimezone: UTC\n")
      (let [result (user-profile/learn-from-transcript!
                    service
                    {:session-id "session-1"
                     :transcript "User explicitly requested concise Russian answers."})
            content (slurp file)
            request (first @requests)
            input (json/parse-string (get-in request [:messages 1 :content]) true)]
        (is (= :updated (:status result)))
        (is (= 1 (:fact-count result)))
        (is (str/includes? content "name: Test User"))
        (is (str/includes? content "<!-- iris:user-profile:start -->"))
        (is (str/includes? content "Prefers concise answers in Russian."))
        (is (not (str/includes? content "sk-secret")))
        (is (not (str/includes? content "long essays")))
        (is (= "profile-model" (:model request)))
        (is (str/includes? (:transcript input) "concise Russian")))
      (let [result (user-profile/learn-from-transcript!
                    service
                    {:session-id "session-2"
                     :transcript "User explicitly asked for even shorter replies."})
            content (slurp file)]
        (is (true? (:updated? result)))
        (is (str/includes? content "Prefers very concise answers in Russian."))
        (is (not (str/includes? content "Prefers concise answers in Russian.")))
        (is (= 2 @refreshed)))
      (finally
        (io/delete-file file true)
        (io/delete-file root true)))))

(deftest ignores-replacement-when-old-fact-is-not-present-test
  (let [root (temp-dir)
        file (io/file root "USER.md")
        provider (->ProfileProvider
                  (atom [(json/generate-string
                          {:operations
                           [{:operation "upsert"
                             :old "Nonexistent fact."
                             :value "Invented replacement."
                             :confidence 0.99
                             :evidence "Bad replacement"}]})])
                  (atom []))
        service (user-profile/create-service
                 {:config {:enabled true}
                  :config-dir (.getAbsolutePath root)
                  :provider provider})]
    (try
      (spit file "# USER\nname: Test User\n")
      (is (= :unchanged
             (:status (user-profile/learn-from-transcript!
                       service {:session-id "session" :transcript "Nothing new"}))))
      (is (= "# USER\nname: Test User\n" (slurp file)))
      (finally
        (io/delete-file file true)
        (io/delete-file root true)))))
