(ns agent.runners.policy
  "Central runner launch validation."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- command-vector? [command]
  (and (vector? command) (seq command) (every? string? command)))

(defn- valid-user? [user]
  (or (nil? user)
      (and (string? user)
           (not (str/blank? user))
           (not (str/includes? user "/"))
           (not (str/includes? user "\u0000")))))

(defn- validate-command! [substrate command]
  (when-not (command-vector? command)
    (throw (ex-info "runner command must be a non-empty vector of strings"
                    {:type :validation-failed
                     :substrate substrate
                     :command command}))))

(defn- validate-user! [substrate user]
  (when-not (valid-user? user)
    (throw (ex-info "runner user must be nil or a simple non-blank string"
                    {:type :validation-failed
                     :substrate substrate
                     :user user}))))

(defn- validate-bubblewrap-bind! [{:keys [source target mode] :as bind}]
  (when-not (and (string? source) (not (str/blank? source)) (.exists (io/file source)))
    (throw (ex-info "bubblewrap bind source must exist"
                    {:type :validation-failed
                     :bind bind})))
  (when-not (and (string? target) (str/starts-with? target "/") (not (str/includes? target "\u0000")))
    (throw (ex-info "bubblewrap bind target must be an absolute path"
                    {:type :validation-failed
                     :bind bind})))
  (when-not (contains? #{:ro :rw "ro" "rw"} mode)
    (throw (ex-info "bubblewrap bind mode must be :ro or :rw"
                    {:type :validation-failed
                     :bind bind}))))

(defn- validate-seatbelt-profile! [runner-options]
  (when (or (:profile-string runner-options) (:profile-file runner-options) (:profile-name runner-options))
    (throw (ex-info "seatbelt launch must use generated immutable profile"
                    {:type :validation-failed
                     :profile-string? (some? (:profile-string runner-options))
                     :profile-file (:profile-file runner-options)
                     :profile-name (:profile-name runner-options)}))))

(defn validate-launch-spec [run-spec]
  (let [substrate (:substrate run-spec)
        runner-options (:runner-options run-spec)]
    (validate-command! substrate (:command runner-options))
    (validate-user! substrate (:user runner-options))
    (case substrate
      :bubblewrap (doseq [bind (or (:binds runner-options) [])]
                    (validate-bubblewrap-bind! bind))
      :seatbelt (validate-seatbelt-profile! runner-options)
      nil)
    run-spec))
