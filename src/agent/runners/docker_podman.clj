(ns agent.runners.docker-podman
  "Docker/Podman-backed runner."
  (:require
   [agent.runners.core :as runners]
   [agent.runners.local-process :as local-process]
   [clojure.string :as str]))

(defn- normalize-command [command]
  (when-not (and (vector? command) (seq command) (every? string? command))
    (throw (ex-info "container command must be a non-empty vector of strings"
                    {:type :validation-failed
                     :command command})))
  command)

(defn- normalize-image [image]
  (when-not (and (string? image) (not (str/blank? image)))
    (throw (ex-info "container image must be a non-blank string"
                    {:type :validation-failed
                     :image image})))
  image)

(defn- mount-args [{:keys [source target mode]}]
  (let [suffix (if (= mode :ro) ":ro" "")]
    ["-v" (str source ":" target suffix)]))

(defn- env-args [env-map]
  (mapcat (fn [[k v]]
            ["-e" (str (name k) "=" v)])
          env-map))

(defn- container-name [engine run-id]
  (str (name engine) "-run-"
       (-> run-id
           (str/lower-case)
           (str/replace #"[^a-z0-9_.-]+" "-"))))

(defn build-container-argv
  [{:keys [engine-binary run-id image working-dir command mounts env share-network?]
    :or {working-dir "/workspace"
         mounts []
         env {}
         share-network? false}}]
  (let [command* (normalize-command command)
        image* (normalize-image image)]
    (vec
     (concat
      [engine-binary "run" "--rm" "--name" (container-name (keyword engine-binary) run-id)]
      (when-not share-network? ["--network" "none"])
      ["-w" working-dir]
      (mapcat mount-args mounts)
      (env-args env)
      [image*]
      command*))))

(defrecord DockerPodmanRunner [delegate engine-binary]
  runners/IRunner
  (launch [_ run-spec]
    (let [runner-options (:runner-options run-spec)
          argv (build-container-argv
                {:engine-binary engine-binary
                 :run-id (:run-id run-spec)
                 :image (:image runner-options)
                 :working-dir (or (:container-working-dir runner-options)
                                  (:working-dir runner-options)
                                  "/workspace")
                 :command (:command runner-options)
                 :mounts (:mounts runner-options)
                 :env (:env runner-options)
                 :share-network? (true? (:share-network? runner-options))})]
      (runners/launch delegate
                      (assoc run-spec
                             :runner-options {:command argv
                                              :working-dir (or (:host-working-dir runner-options) ".")}))))
  (signal [_ run-id command]
    (runners/signal delegate run-id command))
  (status [_ run-id]
    (runners/status delegate run-id))
  (stop [_ run-id]
    (runners/stop delegate run-id)))

(defn create-docker-podman-runner
  ([] (create-docker-podman-runner {}))
  ([{:keys [delegate engine-binary]
     :or {engine-binary "docker"}}]
   (->DockerPodmanRunner (or delegate (local-process/create-local-process-runner))
                         engine-binary)))
