(ns agent.ui.test-support)

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-ui-" ".db")))
