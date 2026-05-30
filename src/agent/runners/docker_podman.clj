(ns agent.runners.docker-podman
  "Docker/Podman-backed runner."
  (:require
   [agent.runners.core :as runners]
   [agent.runners.local-unsandboxed :as local-unsandboxed]
   [agent.runners.policy :as policy]
   [clojure.string :as str]))

(def default-container-user "65532:65532")

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

(defn- normalize-name [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    :else (str value)))

(defn- mount-args [{:keys [source target mode]}]
  (let [suffix (if (= mode :ro) ":ro" "")]
    ["-v" (str source ":" target suffix)]))

(defn- env-args [env-map]
  (mapcat (fn [[k v]]
            ["-e" (str (name k) "=" v)])
          env-map))

(defn- container-env
  [run-spec runner-options]
  (merge
   {"AGENT_RUN_ID" (:run-id run-spec)
    "AGENT_AGENT_ID" (:agent-id run-spec)
    "AGENT_BOOTSTRAP_TOKEN" (or (:bootstrap-token run-spec) "")
    "AGENT_BOOTSTRAP_SPEC" (pr-str (:bootstrap-spec run-spec))}
   (or (:env runner-options) {})))

(defn- container-name [engine run-id]
  (str (name engine) "-run-"
       (-> run-id
           (str/lower-case)
           (str/replace #"[^a-z0-9_.-]+" "-"))))

(defn build-container-argv
  [{:keys [engine-binary run-id image working-dir command mounts env share-network? pull-policy user]
    :or {working-dir "/workspace"
         mounts []
         env {}
         share-network? false}}]
  (let [command* (normalize-command command)
        image* (normalize-image image)]
    (vec
     (concat
      [engine-binary "run" "--rm" "--name" (container-name (keyword engine-binary) run-id)]
      (when (some? pull-policy) ["--pull" (normalize-name pull-policy)])
      (when-not share-network? ["--network" "none"])
      ["--user" (or user default-container-user)]
      ["-w" working-dir]
      (mapcat mount-args mounts)
      (env-args env)
      [image*]
      command*))))

(defrecord DockerPodmanRunner [delegate engine-binary]
  runners/IRunner
  (launch [_ run-spec]
    (let [run-spec (policy/validate-launch-spec run-spec)
          runner-options (:runner-options run-spec)
          argv (build-container-argv
                {:engine-binary engine-binary
                 :run-id (:run-id run-spec)
                 :image (:image runner-options)
                 :working-dir (or (:container-working-dir runner-options)
                                  (:working-dir runner-options)
                                  "/workspace")
                 :pull-policy (:pull-policy runner-options)
                 :user (:user runner-options)
                 :command (:command runner-options)
                 :mounts (:mounts runner-options)
                 :env (container-env run-spec runner-options)
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
   (->DockerPodmanRunner (or delegate (local-unsandboxed/create-local-unsandboxed-runner))
                         engine-binary)))

(defn image-contract
  [runner-options]
  {:image-mode (normalize-name (or (:image-mode runner-options) :mounted-dev))
   :pull-policy (normalize-name (or (:pull-policy runner-options) :missing))
   :image (:image runner-options)
   :container-working-dir (or (:container-working-dir runner-options) "/workspace")
   :required-mounts ["/workspace"]
   :required-env ["AGENT_RUN_ID" "AGENT_AGENT_ID" "AGENT_BOOTSTRAP_TOKEN" "AGENT_BOOTSTRAP_SPEC" "AGENT_CONTROL_URL" "AGENT_CHILD_SQLITE_PATH" "AGENT_LOG_FILE"]
   :default-command ["clojure" "-M" "-m" "agent.runs.child"]})
