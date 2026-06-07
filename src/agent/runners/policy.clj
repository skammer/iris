(ns agent.runners.policy
  "Central runner launch validation."
  (:require
   [agent.runners.core :as runners]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private mount-modes
  {:ro :ro
   "ro" :ro
   :rw :rw
   "rw" :rw})

(defn- nonblank-string? [value]
  (and (string? value)
       (not (str/blank? value))
       (not (str/includes? value "\u0000"))))

(defn- valid-user? [user]
  (or (nil? user)
      (and (nonblank-string? user)
           (not (str/includes? user "/"))
           (re-matches #"[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+)?" user))))

(defn- zero-uid? [value]
  (try
    (zero? (Long/parseLong value))
    (catch Exception _ false)))

(defn- root-user? [user]
  (let [principal (some-> user
                          (str/split #":" 2)
                          first
                          str/lower-case)]
    (or (= "root" principal)
        (zero-uid? principal))))

(defn- validate-command! [substrate command]
  (when-not (runners/command-vector? command)
    (throw (ex-info "runner command must be a non-empty vector of strings"
                    {:type :validation-failed
                     :substrate substrate
                     :command command})))
  command)

(defn- validate-env! [substrate env]
  (when-not (or (nil? env) (map? env))
    (throw (ex-info "runner env must be a map"
                    {:type :validation-failed
                     :substrate substrate
                     :env env})))
  (into {}
        (map (fn [[k v]]
               (let [k* (cond
                          (keyword? k) (name k)
                          (string? k) k
                          :else (str k))]
                 (when-not (and (nonblank-string? k*)
                                (not (str/includes? k* "=")))
                   (throw (ex-info "runner env key must be a non-blank string without null bytes or ="
                                   {:type :validation-failed
                                    :substrate substrate
                                    :env-key k})))
                 (when (or (nil? v)
                           (str/includes? (str v) "\u0000"))
                   (throw (ex-info "runner env value must be non-nil and contain no null bytes"
                                   {:type :validation-failed
                                    :substrate substrate
                                    :env-key k*})))
                 [k* (str v)])))
        env))

(defn- validate-user! [substrate user]
  (when-not (valid-user? user)
    (throw (ex-info "runner user must be nil or a simple non-blank string"
                    {:type :validation-failed
                     :substrate substrate
                     :user user})))
  (when (and (contains? #{:docker :podman} substrate)
             (root-user? user))
    (throw (ex-info "container runner user must not be root"
                    {:type :validation-failed
                     :substrate substrate
                     :user user}))))

(defn- validate-existing-host-path! [substrate field path]
  (when-not (nonblank-string? path)
    (throw (ex-info "runner host path must be a non-blank string"
                    {:type :validation-failed
                     :substrate substrate
                     :field field
                     :path path})))
  (when-not (.exists (io/file path))
    (throw (ex-info "runner host path must exist"
                    {:type :validation-failed
                     :substrate substrate
                     :field field
                     :path path}))))

(defn- normalize-mount-mode! [substrate mode]
  (or (get mount-modes mode)
      (throw (ex-info "runner mount mode must be :ro or :rw"
                      {:type :validation-failed
                       :substrate substrate
                       :mode mode}))))

(defn- normalize-bind!
  [substrate {:keys [source target mode] :as bind}]
  (validate-existing-host-path! substrate :source source)
  (when-not (and (nonblank-string? target)
                 (str/starts-with? target "/"))
    (throw (ex-info "bubblewrap bind target must be an absolute path"
                    {:type :validation-failed
                     :substrate substrate
                     :bind bind})))
  (assoc bind :mode (normalize-mount-mode! substrate mode)))

(defn- normalize-container-mount!
  [substrate {:keys [source target mode] :as mount}]
  (validate-existing-host-path! substrate :source source)
  (when-not (and (nonblank-string? target)
                 (str/starts-with? target "/"))
    (throw (ex-info "container mount target must be an absolute path"
                    {:type :validation-failed
                     :substrate substrate
                     :mount mount})))
  (assoc mount :mode (normalize-mount-mode! substrate (or mode :rw))))

(defn- normalize-mounts! [substrate mounts]
  (when-not (sequential? mounts)
    (throw (ex-info "runner mounts must be sequential"
                    {:type :validation-failed
                     :substrate substrate
                     :mounts mounts})))
  (mapv #(normalize-container-mount! substrate %) mounts))

(defn- normalize-binds! [substrate binds]
  (when-not (sequential? binds)
    (throw (ex-info "bubblewrap binds must be sequential"
                    {:type :validation-failed
                     :substrate substrate
                     :binds binds})))
  (mapv #(normalize-bind! substrate %) binds))

(defn- validate-seatbelt-profile! [runner-options]
  (when (or (:profile-string runner-options) (:profile-file runner-options) (:profile-name runner-options))
    (throw (ex-info "seatbelt launch must use generated immutable profile"
                    {:type :validation-failed
                     :profile-string? (some? (:profile-string runner-options))
                     :profile-file (:profile-file runner-options)
                     :profile-name (:profile-name runner-options)}))))

(defn- validate-seatbelt-paths! [runner-options]
  (validate-existing-host-path! :seatbelt
                                :working-dir
                                (or (:host-working-dir runner-options)
                                    (:working-dir runner-options)
                                    "."))
  (doseq [field [:read-only-paths :read-write-paths]]
    (when-let [paths (get runner-options field)]
      (when-not (sequential? paths)
        (throw (ex-info "seatbelt paths must be sequential"
                        {:type :validation-failed
                         :field field
                         :paths paths})))
      (doseq [path paths]
        (validate-existing-host-path! :seatbelt field path)))))

(defn- validate-container-image! [substrate image]
  (when-not (nonblank-string? image)
    (throw (ex-info "container image must be a non-blank string"
                    {:type :validation-failed
                     :substrate substrate
                     :image image}))))

(defn- validate-host-working-dir! [substrate runner-options]
  (validate-existing-host-path! substrate
                                :working-dir
                                (or (:host-working-dir runner-options)
                                    (:working-dir runner-options)
                                    ".")))

(defn validate-launch-spec [run-spec]
  (let [substrate (runners/normalize-substrate (:substrate run-spec))
        runner-options (or (:runner-options run-spec) {})
        runner-options* (assoc runner-options
                               :command (validate-command! substrate (:command runner-options))
                               :env (validate-env! substrate (:env runner-options)))]
    (validate-user! substrate (:user runner-options*))
    (assoc run-spec
           :substrate substrate
           :runner-options
           (case substrate
             :local-unsandboxed (do
                                  (validate-host-working-dir! substrate runner-options*)
                                  runner-options*)
             :bubblewrap (do
                           (validate-host-working-dir! substrate runner-options*)
                           (assoc runner-options*
                                  :binds (normalize-binds! substrate (or (:binds runner-options*) []))))
             :seatbelt (do
                         (validate-seatbelt-profile! runner-options)
                         (validate-seatbelt-paths! runner-options*)
                         runner-options*)
             (:docker :podman) (do
                                 (validate-container-image! substrate (:image runner-options*))
                                 (validate-host-working-dir! substrate runner-options*)
                                 (assoc runner-options*
                                        :mounts (normalize-mounts! substrate
                                                                  (or (:mounts runner-options*) []))))))))
