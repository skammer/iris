(ns agent.runners.seatbelt
  "macOS Seatbelt-backed runner via sandbox-exec."
  (:require
   [agent.runners.core :as runners]
   [agent.runners.local-unsandboxed :as local-unsandboxed]
   [agent.runners.policy :as policy]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-readable-paths
  ["/System"
   "/usr"
   "/bin"
   "/sbin"
   "/dev"
   "/private/etc"])

(def default-writable-paths
  ["/tmp"
   "/var/tmp"])

(defn- normalize-command [command]
  (when-not (and (vector? command) (seq command) (every? string? command))
    (throw (ex-info "seatbelt command must be a non-empty vector of strings"
                    {:type :validation-failed
                     :command command})))
  command)

(defn- absolute-path [path]
  (.getAbsolutePath (io/file path)))

(defn- normalize-paths [paths]
  (->> paths
       (keep identity)
       (map absolute-path)
       distinct
       vec))

(defn- escape-profile-string [value]
  (-> (str value)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")))

(defn- subpath-form [path]
  (str "(subpath \"" (escape-profile-string path) "\")"))

(defn build-seatbelt-profile
  [{:keys [working-dir read-only-paths read-write-paths allow-network?]
    :or {allow-network? false}}]
  (let [readable-paths (normalize-paths
                        (concat default-readable-paths
                                [working-dir]
                                read-only-paths
                                read-write-paths))
        writable-paths (normalize-paths
                        (concat default-writable-paths
                                read-write-paths))]
    (str/join
     "\n"
     (concat
      ["(version 1)"
       "(deny default)"
       "(import \"system.sb\")"
       "(allow process*)"
       "(allow signal)"
       (str "(allow file-read* "
            (str/join " " (map subpath-form readable-paths))
            ")")]
      (when (seq writable-paths)
        [(str "(allow file-write* "
              (str/join " " (map subpath-form writable-paths))
              ")")])
      (if allow-network?
        ["(allow network*)"]
        ["(deny network*)"])))))

(defn build-seatbelt-argv
  [{:keys [sandbox-exec-binary profile-string profile-file profile-name command]
    :or {sandbox-exec-binary "/usr/bin/sandbox-exec"}}]
  (let [command* (normalize-command command)
        profile-args (cond
                       profile-file ["-f" profile-file]
                       profile-name ["-n" profile-name]
                       :else ["-p" profile-string])]
    (when-not (seq (second profile-args))
      (throw (ex-info "seatbelt profile must be provided"
                      {:type :validation-failed})))
    (vec (concat [sandbox-exec-binary] profile-args command*))))

(defrecord SeatbeltRunner [delegate sandbox-exec-binary]
  runners/IRunner
  (launch [_ run-spec]
    (let [run-spec (policy/validate-launch-spec run-spec)
          runner-options (:runner-options run-spec)
          host-working-dir (absolute-path (or (:host-working-dir runner-options)
                                              (:working-dir runner-options)
                                              "."))
          profile-string (build-seatbelt-profile
                          {:working-dir host-working-dir
                           :read-only-paths (:read-only-paths runner-options)
                           :read-write-paths (:read-write-paths runner-options)
                           :allow-network? (true? (:allow-network? runner-options))})
          argv (build-seatbelt-argv
                {:sandbox-exec-binary (or (:sandbox-exec-binary runner-options)
                                          sandbox-exec-binary)
                 :profile-string profile-string
                 :profile-file (:profile-file runner-options)
                 :profile-name (:profile-name runner-options)
                 :command (:command runner-options)})]
      (runners/launch delegate
                      (assoc run-spec
                             :runner-options {:command argv
                                              :env (:env runner-options)
                                              :working-dir host-working-dir}))))
  (signal [_ run-id command]
    (runners/signal delegate run-id command))
  (status [_ run-id]
    (runners/status delegate run-id))
  (stop [_ run-id]
    (runners/stop delegate run-id)))

(defn create-seatbelt-runner
  ([] (create-seatbelt-runner {}))
  ([{:keys [delegate sandbox-exec-binary]
     :or {sandbox-exec-binary "/usr/bin/sandbox-exec"}}]
   (->SeatbeltRunner (or delegate (local-unsandboxed/create-local-unsandboxed-runner))
                     sandbox-exec-binary)))
