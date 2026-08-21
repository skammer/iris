(ns agent.sessions.service
  "Session store facade with system event logging."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.prompts :as prompts]
   [agent.runtime.compaction :as compaction]
   [agent.system.events :as events]
   [clojure.string :as str]))

(def ^:private project-id-pattern #"[a-z0-9][a-z0-9._-]{0,63}")

(defn normalize-project-id [value]
  (let [project-id (some-> value str str/trim str/lower-case not-empty)]
    (when (and project-id (not (re-matches project-id-pattern project-id)))
      (throw (ex-info "project-id must be 1-64 lowercase letters, digits, dot, underscore, or hyphen"
                      {:type :invalid-project-id :project-id value})))
    project-id))

(defn create-session!
  ([system] (create-session! system nil))
  ([system title]
   (create-session! system title {}))
  ([system title {:keys [project-id]}]
   (let [project-id* (normalize-project-id project-id)
         metadata (cond-> {} project-id* (assoc :project-id project-id*))
         session (sqlite/create-session! (:store system) title {:metadata metadata})]
     (events/log-event! system
                        {:event-type :session.created
                         :entity-type :session
                         :entity-id (:id session)
                         :payload {:title title :project-id project-id*}})
     session)))

(defn list-sessions
  ([system] (sqlite/list-sessions (:store system)))
  ([system opts] (sqlite/list-sessions (:store system) opts)))

(defn get-session
  [system session-id]
  (sqlite/get-session (:store system) session-id))

(defn set-mode!
  [system session-id mode]
  (when (and mode (not (some #{mode} (prompts/list-modes))))
    (throw (ex-info "Unknown prompt mode"
                    {:type :unknown-prompt-mode
                     :mode mode
                     :available-modes (prompts/list-modes)})))
  (sqlite/set-session-active-mode! (:store system) session-id mode))

(defn set-title-if-blank!
  [system session-id title]
  (let [session (sqlite/set-session-title-if-blank! (:store system) session-id title)]
    (when (= title (:title session))
      (events/log-event! system
                         {:event-type :session.title.updated
                          :entity-type :session
                          :entity-id session-id
                          :payload {:title title}}))
    session))

(defn set-project!
  [system session-id project-id]
  (let [session (or (sqlite/get-session (:store system) session-id)
                    (throw (ex-info "Session not found" {:type :not-found :session-id session-id})))
        project-id* (normalize-project-id project-id)
        metadata (cond-> (or (:metadata session) {})
                   project-id* (assoc :project-id project-id*)
                   (nil? project-id*) (dissoc :project-id))
        updated (sqlite/update-session-metadata! (:store system) session-id metadata)]
    (events/log-event! system
                       {:event-type :session.project.updated
                        :entity-type :session
                        :entity-id session-id
                        :payload {:project-id project-id*}})
    updated))

(defn session-exists?
  [system session-id]
  (sqlite/session-exists? (:store system) session-id))

(defn list-messages
  [system session-id]
  (sqlite/list-messages (:store system) session-id))

(defn append-entry!
  [system session-id {:keys [id parent-id type payload select-leaf?]}]
  (sqlite/append-entry! (:store system)
                        session-id
                        {:id id
                         :parent-id parent-id
                         :type type
                         :payload (or payload {})
                         :select-leaf? (not (false? select-leaf?))}))

(defn list-entries
  [system session-id]
  (sqlite/list-entries (:store system) session-id))

(defn current-path
  [system session-id]
  (sqlite/branch-path (:store system) session-id))

(defn tree
  [system session-id]
  (sqlite/session-tree (:store system) session-id))

(defn select-leaf!
  [system session-id entry-id {:keys [branch-summary?]}]
  (let [store (:store system)
        old-leaf (some-> (sqlite/leaf-entry store session-id) :id)
        summary (when (and branch-summary? old-leaf entry-id (not= old-leaf entry-id))
                  (compaction/store-branch-summary! store session-id old-leaf entry-id))]
    (cond-> {:entry (sqlite/select-leaf! store session-id entry-id)}
      summary (assoc :branch-summary summary))))

(defn compact!
  [system session-id]
  (compaction/compact-session! (:store system)
                               session-id
                               (get-in system [:config :chat :compaction])))
