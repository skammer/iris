(ns agent.runners.bubblewrap
  "Bubblewrap-backed runner."
  (:require
   [agent.runners.core :as runners]
   [agent.runners.local-unsandboxed :as local-unsandboxed]
   [agent.runners.policy :as policy]))

(defn- normalize-mode [mode]
  (case mode
    (:ro "ro") :ro
    (:rw "rw" nil) :rw
    (throw (ex-info "bubblewrap bind mode must be :ro or :rw"
                    {:type :validation-failed
                     :mode mode}))))

(defn build-bwrap-argv
  [{:keys [bwrap-binary binds share-network? working-dir command env]
    :or {bwrap-binary "bwrap"
         binds []
         share-network? false
         working-dir "/"
         env {}}}]
  (when-not (and (vector? command) (seq command) (every? string? command))
    (throw (ex-info "bubblewrap command must be a non-empty vector of strings"
                    {:type :validation-failed
                     :command command})))
  (vec
   (concat
    [bwrap-binary
     "--die-with-parent"
     "--new-session"
     "--unshare-user"
     "--unshare-ipc"
     "--unshare-pid"
     "--unshare-uts"
     "--unshare-cgroup-try"
     "--proc" "/proc"
     "--dev" "/dev"
     "--tmpfs" "/tmp"
     "--clearenv"]
    (when-not share-network? ["--unshare-net"])
    (mapcat (fn [[k v]] ["--setenv" (name k) (str v)]) env)
    ["--chdir" working-dir]
    (mapcat (fn [{:keys [source target mode]}]
              (case (normalize-mode mode)
                :rw ["--bind" source target]
                :ro ["--ro-bind" source target]))
            binds)
    ["--"]
    command)))

(defrecord BubblewrapRunner [delegate bwrap-binary]
  runners/IRunner
  (launch [_ run-spec]
    (let [run-spec (policy/validate-launch-spec run-spec)
          runner-options (:runner-options run-spec)
          argv (build-bwrap-argv {:bwrap-binary bwrap-binary
                                  :binds (:binds runner-options)
                                  :share-network? (true? (:share-network? runner-options))
                                  :working-dir (or (:working-dir runner-options) "/")
                                  :env (:env runner-options)
                                  :command (:command runner-options)})]
      (runners/launch delegate
                      (assoc run-spec :runner-options {:command argv
                                                       :env (:env runner-options)
                                                       :working-dir (or (:host-working-dir runner-options) ".")}))))
  (signal [_ run-id command]
    (runners/signal delegate run-id command))
  (status [_ run-id]
    (runners/status delegate run-id))
  (stop [_ run-id]
    (runners/stop delegate run-id)))

(defn create-bubblewrap-runner
  ([] (create-bubblewrap-runner {}))
  ([{:keys [delegate bwrap-binary]
     :or {bwrap-binary "bwrap"}}]
   (->BubblewrapRunner (or delegate (local-unsandboxed/create-local-unsandboxed-runner))
                       bwrap-binary)))
