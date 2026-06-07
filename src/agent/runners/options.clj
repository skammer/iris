(ns agent.runners.options
  "Shared runner option preparation for API/system launch paths."
  (:require
   [agent.runners.docker-podman :as docker-podman]
   [agent.runs.child :as runtime-child]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io File)))

(defn- default-child-env
  [system]
  {"AGENT_SQLITE_PATH" (-> (or (get-in system [:config :storage :sqlite :path])
                                "data/agent.db")
                            io/file
                            .getAbsolutePath)
   "AGENT_SQLITE_JOURNAL_MODE" (or (get-in system [:config :storage :sqlite :journal-mode])
                                   "WAL")
   "AGENT_LOG_FILE" (-> (or (get-in system [:config :logging :file :path])
                            "logs/iris.log")
                        io/file
                        .getAbsolutePath)})

(defn- absolute-path [path]
  (.getAbsolutePath (io/file path)))

(defn- canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn- existing-path? [path]
  (and path (.exists (io/file path))))

(defn- parent-path [path]
  (some-> (io/file path) .getAbsoluteFile .getParentFile .getAbsolutePath))

(defn- user-path [relative]
  (absolute-path (str (System/getProperty "user.home") File/separator relative)))

(defn- path-under? [parent child]
  (let [parent* (canonical-path parent)
        child* (canonical-path child)]
    (or (= parent* child*)
        (str/starts-with? child*
                          (str parent* File/separator)))))

(defn- unique-existing-paths [paths]
  (->> paths
       (keep identity)
       (filter existing-path?)
       (map canonical-path)
       distinct
       vec))

(defn- classpath-entries []
  (->> (str/split (System/getProperty "java.class.path" "")
                  (re-pattern java.io.File/pathSeparator))
       (remove str/blank?)
       (map io/file)
       (filter #(.isAbsolute ^File %))
       (map #(.getPath ^File %))))

(defn- runtime-read-paths [host-working-dir]
  (let [roots (unique-existing-paths [(System/getProperty "java.home")
                                      (user-path ".m2")
                                      (user-path ".gitlibs")])
        working-dir* (canonical-path host-working-dir)]
    (unique-existing-paths
     (concat roots
             (remove (fn [entry]
                       (or (path-under? working-dir* entry)
                           (some #(path-under? % entry) roots)))
                     (classpath-entries))))))

(defn- runtime-write-paths [system host-working-dir]
  (let [env (default-child-env system)]
    (unique-existing-paths [host-working-dir
                            (parent-path (get env "AGENT_SQLITE_PATH"))
                            (parent-path (get env "AGENT_LOG_FILE"))])))

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
  (let [principal (some-> user str (str/split #":" 2) first str/lower-case)]
    (or (= "root" principal)
        (try
          (zero? (Long/parseLong principal))
          (catch Exception _ false)))))

(defn- ensure-mount [mounts source target mode]
  (if (some #(and (= source (:source %)) (= target (:target %))) mounts)
    mounts
    (conj (vec mounts) {:source source :target target :mode mode})))

(defn- ensure-mount-if-exists [mounts source target mode]
  (if (.exists (io/file source))
    (ensure-mount mounts source target mode)
    mounts))

(defn- ensure-bind [binds source target mode]
  (if (some #(and (= source (:source %)) (= target (:target %))) binds)
    binds
    (conj (vec binds) {:source source :target target :mode mode})))

(defn- ensure-bind-if-exists [binds source target mode]
  (if (existing-path? source)
    (ensure-bind binds source target mode)
    binds))

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
        host-m2-dir (user-path ".m2")
        control-url (container-control-url system substrate runner-cfg runner-options)
        child-sqlite-path (str container-data-dir "/child.db")
        child-log-path (str container-data-dir "/child.log")
        mounts* (cond-> (ensure-mount (vec (or (:mounts runner-options) []))
                                      host-working-dir
                                      container-working-dir
                                      :rw)
                  true
                  (ensure-mount-if-exists host-m2-dir (str container-home-dir "/.m2") :ro))
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
                                     :else false))
      (not (seq (:command runner-options)))
      (assoc :command (runtime-child/current-container-child-command)))))

(defn- prepare-local-runner-options
  [system runner-options]
  (let [env* (merge (default-child-env system) (or (:env runner-options) {}))]
    (cond-> (assoc runner-options :env env*)
      (not (seq (:command runner-options)))
      (assoc :command (runtime-child/current-child-command)
             :working-dir (or (:working-dir runner-options) ".")))))

(defn- prepare-seatbelt-runner-options
  [system runner-options]
  (let [opts (prepare-local-runner-options system runner-options)
        host-working-dir (canonical-path (or (:host-working-dir opts)
                                             (:working-dir opts)
                                             "."))
        read-paths (runtime-read-paths host-working-dir)
        write-paths (runtime-write-paths system host-working-dir)]
    (-> opts
        (assoc :host-working-dir host-working-dir
               :working-dir host-working-dir)
        (update :read-only-paths #(vec (concat (or % []) read-paths)))
        (update :read-write-paths #(vec (concat (or % []) write-paths))))))

(defn- prepare-bubblewrap-runner-options
  [system runner-options]
  (let [opts (prepare-local-runner-options system runner-options)
        host-working-dir (canonical-path (or (:host-working-dir opts)
                                             (:working-dir opts)
                                             "."))
        read-paths (runtime-read-paths host-working-dir)
        write-paths (runtime-write-paths system host-working-dir)
        binds (reduce (fn [acc path]
                        (ensure-bind-if-exists acc path path :ro))
                      (vec (or (:binds opts) []))
                      read-paths)
        binds* (reduce (fn [acc path]
                         (ensure-bind-if-exists acc path path :rw))
                       binds
                       write-paths)]
    (assoc opts
           :host-working-dir host-working-dir
           :working-dir host-working-dir
           :binds binds*)))

(defn prepare-runner-options
  [system run]
  (let [runner-options (or (:runner-options run) {})]
    (case (keyword (:substrate run))
      :local-unsandboxed (prepare-local-runner-options system runner-options)
      :seatbelt (prepare-seatbelt-runner-options system runner-options)
      :bubblewrap (prepare-bubblewrap-runner-options system runner-options)
      (:docker :podman) (prepare-container-runner-options system (keyword (:substrate run)) runner-options)
      runner-options)))
