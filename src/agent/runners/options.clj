(ns agent.runners.options
  "Shared runner option preparation for API/system launch paths."
  (:require
   [agent.runners.docker-podman :as docker-podman]
   [agent.runtime.child :as runtime-child]
   [clojure.java.io :as io]))

(defn- default-child-env
  [system]
  {"AGENT_SQLITE_PATH" (-> system :config :storage :sqlite :path io/file .getAbsolutePath)
   "AGENT_SQLITE_JOURNAL_MODE" (or (get-in system [:config :storage :sqlite :journal-mode])
                                   "WAL")
   "AGENT_LOG_FILE" (-> (or (get-in system [:config :logging :file :path])
                            "logs/iris.log")
                        io/file
                        .getAbsolutePath)})

(defn- absolute-path [path]
  (.getAbsolutePath (io/file path)))

(defn host-default-substrate
  []
  (let [os-name (.toLowerCase (System/getProperty "os.name" ""))]
    (cond
      (.contains os-name "mac") :seatbelt
      (.contains os-name "linux") :bubblewrap
      :else (throw (ex-info "No safe default runner substrate for this OS"
                            {:type :runner-default-unavailable
                             :os-name os-name})))))

(defn default-substrate
  [system]
  (let [configured (get-in system [:config :runners :default-substrate] :auto)
        substrate (cond
                    (keyword? configured) configured
                    (string? configured) (keyword configured)
                    :else :auto)]
    (case substrate
      :auto (host-default-substrate)
      substrate)))

(defn- root-user? [user]
  (contains? #{"0" "0:0" "root" "root:root"} user))

(defn- ensure-mount [mounts source target mode]
  (if (some #(and (= source (:source %)) (= target (:target %))) mounts)
    mounts
    (conj (vec mounts) {:source source :target target :mode mode})))

(defn- ensure-mount-if-exists [mounts source target mode]
  (if (.exists (io/file source))
    (ensure-mount mounts source target mode)
    mounts))

(defn- container-control-url
  [system substrate runner-cfg runner-options]
  (or (:control-url runner-options)
      (:control-url runner-cfg)
      (get-in system [:config :api :control-url])
      (let [port (get-in system [:config :api :port])
            host (case substrate
                   :podman "host.containers.internal"
                   "host.docker.internal")]
        (str "http://" host ":" port))))

(defn- prepare-container-runner-options
  [system substrate runner-options]
  (let [runner-cfg (get-in system [:config :runners substrate] {})
        host-working-dir (absolute-path (or (:host-working-dir runner-options)
                                            (:working-dir runner-options)
                                            (:host-working-dir runner-cfg)
                                            "."))
        container-working-dir (or (:container-working-dir runner-options)
                                  (:container-working-dir runner-cfg)
                                  "/workspace")
        container-data-dir (or (:container-data-dir runner-options)
                               (:container-data-dir runner-cfg)
                               "/tmp/iris")
        user* (or (:user runner-options)
                  (:user runner-cfg)
                  docker-podman/default-container-user)
        requested-home-dir (or (:container-home-dir runner-options)
                               (:container-home-dir runner-cfg)
                               "/tmp/iris/home")
        container-home-dir (if (and (= "/root" requested-home-dir)
                                    (not (root-user? user*)))
                             "/tmp/iris/home"
                             requested-home-dir)
        host-m2-dir (absolute-path (str (System/getProperty "user.home") "/.m2"))
        control-url (container-control-url system substrate runner-cfg runner-options)
        child-sqlite-path (str container-data-dir "/child.db")
        child-log-path (str container-data-dir "/child.log")
        mounts* (cond-> (ensure-mount (vec (or (:mounts runner-options) []))
                                      host-working-dir
                                      container-working-dir
                                      :rw)
                  (root-user? user*)
                  (ensure-mount-if-exists host-m2-dir (str container-home-dir "/.m2") :rw))
        env* (merge {"AGENT_CONTROL_URL" control-url
                     "AGENT_CHILD_SQLITE_PATH" child-sqlite-path
                     "AGENT_LOG_FILE" child-log-path
                     "HOME" container-home-dir}
                    (or (:env runner-options) {}))]
    (cond-> (assoc runner-options
                   :image (or (:image runner-options)
                              (:image runner-cfg))
                   :mounts mounts*
                   :env env*
                   :host-working-dir host-working-dir
                   :container-working-dir container-working-dir
                   :container-home-dir container-home-dir
                   :container-data-dir container-data-dir
                   :user user*
                   :control-url control-url
                   :share-network? (cond
                                     (contains? runner-options :share-network?) (true? (:share-network? runner-options))
                                     (contains? runner-cfg :share-network?) (true? (:share-network? runner-cfg))
                                     :else true))
      (not (seq (:command runner-options)))
      (assoc :command (runtime-child/current-container-child-command)))))

(defn prepare-runner-options
  [system run]
  (let [runner-options (or (:runner-options run) {})]
    (cond-> runner-options
      (#{"local-unsandboxed" "bubblewrap" "seatbelt"} (:substrate run))
      ((fn [opts]
         (let [env* (merge (default-child-env system) (or (:env opts) {}))]
           (cond-> (assoc opts :env env*)
             (not (seq (:command opts)))
             (assoc :command (runtime-child/current-child-command)
                    :working-dir (or (:working-dir opts) "."))))))

      (#{"docker" "podman"} (:substrate run))
      ((fn [opts]
         (prepare-container-runner-options system (keyword (:substrate run)) opts))))))
