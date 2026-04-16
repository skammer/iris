(ns agent.runners.bubblewrap
  "Bubblewrap-backed runner."
  (:require
   [agent.runners.core :as runners]
   [agent.runners.local-process :as local-process]
   [clojure.string :as str]))

(defn build-bwrap-argv
  [{:keys [bwrap-binary binds share-network? working-dir command]
    :or {bwrap-binary "bwrap"
         binds []
         share-network? false
         working-dir "/"}}]
  (when-not (and (vector? command) (seq command) (every? string? command))
    (throw (ex-info "bubblewrap command must be a non-empty vector of strings"
                    {:type :validation-failed
                     :command command})))
  (vec
   (concat
    [bwrap-binary "--die-with-parent" "--new-session" "--proc" "/proc" "--dev" "/dev"]
    (when-not share-network? ["--unshare-net"])
    ["--chdir" working-dir]
    (mapcat (fn [{:keys [source target mode]}]
              (case mode
                :rw ["--bind" source target]
                :ro ["--ro-bind" source target]
                ["--ro-bind" source target]))
            binds)
    ["--"]
    command)))

(defrecord BubblewrapRunner [delegate bwrap-binary]
  runners/IRunner
  (launch [_ run-spec]
    (let [runner-options (:runner-options run-spec)
          argv (build-bwrap-argv {:bwrap-binary bwrap-binary
                                  :binds (:binds runner-options)
                                  :share-network? (true? (:share-network? runner-options))
                                  :working-dir (or (:working-dir runner-options) "/")
                                  :command (:command runner-options)})]
      (runners/launch delegate
                      (assoc run-spec :runner-options {:command argv
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
   (->BubblewrapRunner (or delegate (local-process/create-local-process-runner))
                       bwrap-binary)))
