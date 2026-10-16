(ns agent.memory.core.test-support
  (:require
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defrecord NoteProvider [responses]
  llm-core/ILLMProvider
  (complete [_ _ _] "")
  (stream [_ _ _] nil)
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ _]
    {:role "assistant"
     :content (first (first (swap-vals! responses rest)))
     :tool-calls []
     :usage nil
     :raw nil})
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defrecord CapturingNoteProvider [responses requests]
  llm-core/ILLMProvider
  (complete [_ _ _] "")
  (stream [_ _ _] nil)
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ request]
    (swap! requests conj request)
    {:role "assistant"
     :content (first (first (swap-vals! responses rest)))
     :tool-calls []
     :usage nil
     :raw nil})
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defn deterministic-embedding [text]
  (let [text* (str/lower-case (or text ""))]
    (cond
      (re-find #"bike|bicycle|cycling|transport" text*) [1.0 0.0 0.0]
      (re-find #"espresso|coffee" text*) [0.0 1.0 0.0]
      :else [0.0 0.0 1.0])))

(defrecord EmbeddingProvider [requests]
  llm-core/ILLMProvider
  (complete [_ _ _] "")
  (stream [_ _ _] nil)
  (embed [_ text opts]
    (swap! requests conj {:text text :opts opts})
    (if (string? text)
      (deterministic-embedding text)
      (mapv deterministic-embedding text)))
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-embedding true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0}))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-memory-" ".db")))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-memory-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn write-approved-note! [root]
  (let [file (io/file root "preferences/answer-style.md")]
    (.mkdirs (.getParentFile file))
    (spit file
          (str "---\n"
               "id: mem_answer_style\n"
               "type: Preference\n"
               "title: Answer style\n"
               "description: User prefers detailed answers.\n"
               "tags: [\"preference\"]\n"
               "iris:\n"
               "  scope: global\n"
               "  status: approved\n"
               "  confidence: 0.95\n"
               "---\n\n"
               "# Answer style\n\n"
               "User prefers detailed answers.\n\n"
               "## Evidence\n\n"
               "### User\n\n> Explain in detail.\n"))
    file))

(defn test-service
  ([store cfg] (test-service store cfg {}))
  ([store cfg opts]
   (memory/create-memory-service
    (merge {:search {:default-limit 10}
            :notes {:extractor {:enabled false}
                    :default-scope :session}
            :vault {:paths []}}
           cfg)
    store
    opts)))
