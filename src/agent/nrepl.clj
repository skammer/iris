(ns agent.nrepl
  "Embedded nREPL server for live runtime inspection."
  (:require
   [agent.logging :as logging]
   [clojure.java.io :as io]
   [nrepl.server :as nrepl-server]))

(defonce current-system (atom nil))

(defn- server-port [server]
  (or (:port server)
      (some-> (:server-socket server) .getLocalPort)))

(defn- write-port-file! [path port]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (str port "\n"))))

(defn start!
  [system {:keys [enabled bind port port-file]
           :or {enabled true
                bind "127.0.0.1"
                port 0
                port-file ".nrepl-port"}}]
  (when enabled
    (reset! current-system system)
    (let [server (nrepl-server/start-server :bind bind :port (long port))
          actual-port (server-port server)]
      (write-port-file! port-file actual-port)
      (logging/log! :agent.nrepl/started {:bind bind
                                          :port actual-port
                                          :port-file port-file})
      {:server server
       :bind bind
       :port actual-port
       :port-file port-file})))

(defn stop!
  [{:keys [server port-file]}]
  (when server
    (nrepl-server/stop-server server))
  (when port-file
    (io/delete-file port-file true))
  (reset! current-system nil)
  nil)
