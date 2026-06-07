(ns agent.api.handlers.public
  (:require
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io ByteArrayOutputStream)
   (java.net URLDecoder)
   (java.nio.charset StandardCharsets)
   (java.nio.file Files Paths)))

(defn- safe-relative-path [uri]
  (let [prefix "/public/"]
    (when (str/starts-with? uri prefix)
      (let [decoded (URLDecoder/decode (subs uri (count prefix)) StandardCharsets/UTF_8)
            path (Paths/get decoded (make-array String 0))
            normalized (.normalize path)
            normalized-str (str/replace (str normalized) "\\" "/")]
        (when (and (not (str/blank? normalized-str))
                   (not (str/includes? decoded "\u0000"))
                   (not (.isAbsolute path))
                   (not (str/starts-with? normalized-str "../"))
                   (not= ".." normalized-str))
          normalized-str)))))

(defn- resource-bytes [resource-path]
  (when-let [resource (io/resource resource-path)]
    (with-open [in (io/input-stream resource)
                out (ByteArrayOutputStream.)]
      (io/copy in out)
      (.toByteArray out))))

(defn- file-bytes [relative]
  (let [file (io/file "public" relative)
        canonical (.getCanonicalPath file)
        root (str (.getCanonicalPath (io/file "public"))
                  java.io.File/separator)]
    (when (and (.startsWith canonical root) (.isFile file))
      (Files/readAllBytes (.toPath file)))))

(defn- public-not-found-response []
  (responses/json-response 404 {:error "not_found"} {"Cache-Control" "no-cache"}))

(defn- cache-headers [relative]
  (if (re-find #"\.(?:css|js|woff2?|ttf|otf|png|jpe?g|gif|webp|svg)$" relative)
    {"Cache-Control" "public, max-age=3600"}
    {"Cache-Control" "no-cache"}))

(defn file-response
  "Ring-style handler for /public/* paths."
  [request]
  (if-let [relative (safe-relative-path (:uri request))]
    (if-let [bytes (or (resource-bytes (str "public/" relative))
                       (file-bytes relative))]
      (responses/bytes-response 200
                                (h/content-type-for-path relative)
                                bytes
                                (cache-headers relative))
      (public-not-found-response))
    (public-not-found-response)))
