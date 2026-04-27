(ns agent.api.handlers.public
  (:require
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [clojure.java.io :as io])
  (:import
   (java.nio.file Files)))

(defn file-response
  "Ring-style handler for /public/* paths."
  [request]
  (let [path (:uri request)
        relative (subs path (count "/public/"))
        file (io/file "public" relative)
        canonical (.getCanonicalPath file)
        root (.getCanonicalPath (io/file "public"))]
    (if (and (.startsWith canonical root) (.isFile file))
      (responses/bytes-response 200
                                (h/content-type-for-path canonical)
                                (Files/readAllBytes (.toPath file)))
      (responses/not-found-response))))
