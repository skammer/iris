(ns agent.cron.store
  "SQLite cron job/run ledger and atomic claims."
  (:require
   [agent.persistence.sqlite.common :as db]
   [clojure.string :as str])
  (:import (java.sql SQLException)))

(def job-statuses #{:active :paused :completed :deleted})
(def active-run-statuses #{:claimed :running})

(defn- kw [value] (some-> value keyword))
(defn- json [value] (db/json-string value))
(defn- parse-json [value] (db/parse-json-string value))

(defn row->job [row]
  (when row
    {:id (:id row)
     :name (:name row)
     :prompt (:prompt row)
     :schedule (parse-json (:schedule_json row))
     :timezone (:timezone row)
     :status (kw (:status row))
     :notification (parse-json (:notification_json row))
     :provider (some-> (:provider row) keyword)
     :model (:model row)
     :tool-profile (some-> (:tool_profile row) keyword)
     :next-run-at (:next_run_at row)
     :last-run-at (:last_run_at row)
     :last-run-status (kw (:last_run_status row))
     :run-count (long (or (:run_count row) 0))
     :failure-count (long (or (:failure_count row) 0))
     :occurrence-count (long (or (:occurrence_count row) 0))
     :max-occurrences (some-> (:max_occurrences row) long)
     :revision (long (:revision row))
     :created-by (:created_by row)
     :origin (parse-json (:origin_json row))
     :created-at (:created_at row)
     :updated-at (:updated_at row)
     :deleted-at (:deleted_at row)}))

(defn row->run [row]
  (when row
    {:id (:id row)
     :job-id (:job_id row)
     :job-revision (long (:job_revision row))
     :trigger (kw (:trigger row))
     :scheduled-for (:scheduled_for row)
     :status (kw (:status row))
     :notification-status (kw (:notification_status row))
     :request-id (:request_id row)
     :session-id (:session_id row)
     :owner-id (:owner_id row)
     :snapshot (parse-json (:snapshot_json row))
     :output (:output row)
     :error (:error row)
     :usage (parse-json (:usage_json row))
     :notification (parse-json (:notification_json row))
     :claimed-at (:claimed_at row)
     :started-at (:started_at row)
     :finished-at (:finished_at row)
     :created-at (:created_at row)}))

(defn- select-job [conn sql params]
  (some-> (db/select-one conn (into [sql] params) identity) row->job))

(defn get-job [store id-or-name]
  (db/with-connection store
    #(select-job %
                 "SELECT * FROM cron_jobs WHERE deleted_at IS NULL AND (id = ? OR lower(name) = lower(?)) LIMIT 1"
                 [id-or-name id-or-name])))

(defn list-jobs
  ([store] (list-jobs store {}))
  ([store {:keys [status include-deleted? limit] :or {limit 200}}]
   (let [clauses (cond-> []
                   (not include-deleted?) (conj "deleted_at IS NULL")
                   status (conj "status = ?"))
         sql (str "SELECT * FROM cron_jobs"
                  (when (seq clauses) (str " WHERE " (str/join " AND " clauses)))
                  " ORDER BY created_at DESC LIMIT ?")
         params (cond-> [] status (conj (name status)) true (conj (long limit)))]
     (db/with-connection store
       #(mapv row->job (db/select-many % (into [sql] params) identity))))))

(defn create-job! [store job]
  (let [now (db/now-str)
        row (merge {:id (db/uuid-str) :status :active :revision 1
                    :run-count 0 :failure-count 0 :occurrence-count 0
                    :created-at now :updated-at now}
                   job)]
    (try
      (db/with-connection store
        #(db/execute! %
                      ["INSERT INTO cron_jobs
                      (id,name,prompt,schedule_json,timezone,status,notification_json,provider,model,tool_profile,
                       next_run_at,run_count,failure_count,occurrence_count,max_occurrences,revision,created_by,
                       origin_json,created_at,updated_at)
                      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                     (:id row) (:name row) (:prompt row) (json (:schedule row)) (:timezone row)
                     (name (:status row)) (json (:notification row)) (some-> (:provider row) name)
                     (:model row) (some-> (:tool-profile row) name) (:next-run-at row)
                     (:run-count row) (:failure-count row) (:occurrence-count row) (:max-occurrences row)
                       (:revision row) (:created-by row) (json (:origin row)) (:created-at row) (:updated-at row)]))
      (catch SQLException e
        (if (re-find #"cron_jobs.*name|UNIQUE constraint failed: index 'cron_jobs_name_unique'"
                     (or (.getMessage e) ""))
          (throw (ex-info "cron job name already exists" {:type :name-conflict :name (:name row)} e))
          (throw e))))
    (get-job store (:id row))))

(defn update-job! [store id expected-revision job]
  (let [now (db/now-str)
        changed (db/with-connection store
                  #(db/execute! %
                    ["UPDATE cron_jobs SET name=?,prompt=?,schedule_json=?,timezone=?,status=?,notification_json=?,
                       provider=?,model=?,tool_profile=?,next_run_at=?,max_occurrences=?,revision=revision+1,updated_at=?
                      WHERE id=? AND revision=? AND deleted_at IS NULL"
                     (:name job) (:prompt job) (json (:schedule job)) (:timezone job) (name (:status job))
                     (json (:notification job)) (some-> (:provider job) name) (:model job)
                     (some-> (:tool-profile job) name) (:next-run-at job) (:max-occurrences job) now id expected-revision]))]
    (when (zero? changed)
      (throw (ex-info "cron job revision conflict"
                      {:type :revision-conflict :id id :expected-revision expected-revision})))
    (get-job store id)))

(defn set-job-status! [store id expected-revision status next-run-at]
  (let [now (db/now-str)
        deleted-at (when (= :deleted status) now)
        changed (db/with-connection store
                  #(db/execute! %
                    ["UPDATE cron_jobs SET status=?,next_run_at=?,deleted_at=?,revision=revision+1,updated_at=?
                      WHERE id=? AND revision=? AND deleted_at IS NULL"
                     (name status) next-run-at deleted-at now id expected-revision]))]
    (when (zero? changed)
      (throw (ex-info "cron job revision conflict"
                      {:type :revision-conflict :id id :expected-revision expected-revision})))
    (if (= :deleted status)
      (assoc (get-job store id) :id id :status :deleted)
      (get-job store id))))

(defn due-jobs [store now limit]
  (db/with-connection store
    #(mapv row->job
           (db/select-many %
             ["SELECT * FROM cron_jobs
               WHERE status='active' AND next_run_at IS NOT NULL AND next_run_at <= ?
               ORDER BY next_run_at ASC LIMIT ?" now (long limit)] identity))))

(defn- session-title [job scheduled-for]
  (str "Cron: " (:name job) " · " scheduled-for))

(defn- insert-session! [conn session-id job run-id trigger scheduled-for now]
  (db/execute! conn
    ["INSERT INTO sessions (id,title,kind,metadata_json,created_at) VALUES (?,?,?,?,?)"
     session-id (session-title job scheduled-for) "cron"
     (json {:cron-job-id (:id job) :cron-run-id run-id
            :trigger trigger :scheduled-for scheduled-for}) now]))

(defn- insert-run! [conn run]
  (db/execute! conn
    ["INSERT INTO cron_runs
      (id,job_id,job_revision,trigger,scheduled_for,status,notification_status,request_id,session_id,
       owner_id,snapshot_json,claimed_at,created_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
     (:id run) (:job-id run) (:job-revision run) (name (:trigger run)) (:scheduled-for run)
     (name (:status run)) (name (:notification-status run)) (:request-id run) (:session-id run)
     (:owner-id run) (json (:snapshot run)) (:claimed-at run) (:created-at run)]))

(defn claim-scheduled!
  [store job {:keys [owner-id snapshot next-run-at terminal-status]}]
  (let [run-id (db/uuid-str) session-id (db/uuid-str) now (db/now-str)
        scheduled-for (:next-run-at job)
        run-base {:id run-id :job-id (:id job) :job-revision (:revision job)
             :trigger :scheduled :scheduled-for scheduled-for :status :claimed
             :notification-status :not-requested :request-id run-id :session-id session-id
             :owner-id owner-id :snapshot snapshot :claimed-at now :created-at now}]
    (try
      (db/with-transaction store
        (fn [conn]
          (let [overlap? (pos? (long (or (db/select-value conn
                                             ["SELECT count(*) FROM cron_runs WHERE job_id=? AND status IN ('claimed','running')"
                                              (:id job)]) 0)))
                run (assoc run-base
                           :status (if overlap? :skipped :claimed)
                           :notification-status (if overlap? :suppressed :not-requested))]
          (insert-session! conn session-id job run-id :scheduled scheduled-for now)
          (insert-run! conn run)
          (let [changed (db/execute! conn
                          ["UPDATE cron_jobs SET next_run_at=?,status=?,occurrence_count=occurrence_count+1,
                             revision=revision,updated_at=? WHERE id=? AND revision=? AND next_run_at=? AND status='active'"
                           next-run-at (name (or terminal-status :active)) now (:id job) (:revision job) scheduled-for])]
            (when (zero? changed)
              (throw (ex-info "cron occurrence was already claimed" {:type :claim-conflict :job-id (:id job)}))))
          run)))
      (catch SQLException e
        (if (re-find #"UNIQUE constraint failed" (or (.getMessage e) ""))
          nil
          (throw e))))))

(defn claim-manual! [store job {:keys [owner-id snapshot]}]
  (let [run-id (db/uuid-str) session-id (db/uuid-str) now (db/now-str)
        run {:id run-id :job-id (:id job) :job-revision (:revision job)
             :trigger :manual :scheduled-for now :status :claimed
             :notification-status :not-requested :request-id run-id :session-id session-id
             :owner-id owner-id :snapshot snapshot :claimed-at now :created-at now}]
    (try
      (db/with-transaction store
        (fn [conn]
          (insert-session! conn session-id job run-id :manual now now)
          (insert-run! conn run)
          run))
      (catch SQLException e
        (if (re-find #"cron_runs_one_active_per_job|UNIQUE constraint failed: cron_runs.job_id" (or (.getMessage e) ""))
          (throw (ex-info "cron job already has an active run" {:type :active-overlap :job-id (:id job)} e))
          (throw e))))))

(defn get-run [store run-id]
  (db/with-connection store
    #(some-> (db/select-one % ["SELECT * FROM cron_runs WHERE id=?" run-id] identity) row->run)))

(defn list-runs
  ([store] (list-runs store nil 100))
  ([store job-id limit]
   (db/with-connection store
     #(mapv row->run
            (if job-id
              (db/select-many % ["SELECT * FROM cron_runs WHERE job_id=? ORDER BY created_at DESC LIMIT ?"
                                 job-id (long limit)] identity)
              (db/select-many % ["SELECT * FROM cron_runs ORDER BY created_at DESC LIMIT ?" (long limit)] identity))))))

(defn queued-runs [store limit]
  (db/with-connection store
    #(mapv row->run
           (db/select-many %
             ["SELECT * FROM cron_runs WHERE status='claimed' AND owner_id IS NULL ORDER BY created_at ASC LIMIT ?"
              (long limit)] identity))))

(defn adopt-run! [store run-id owner-id]
  (let [changed (db/with-connection store
                  #(db/execute! %
                    ["UPDATE cron_runs SET owner_id=? WHERE id=? AND status='claimed' AND owner_id IS NULL"
                     owner-id run-id]))]
    (when (pos? changed) (get-run store run-id))))

(defn mark-run-started! [store run-id]
  (let [now (db/now-str)]
    (db/with-connection store
      #(db/execute! % ["UPDATE cron_runs SET status='running',started_at=? WHERE id=? AND status='claimed'" now run-id]))
    (get-run store run-id)))

(defn finish-run! [store run-id status {:keys [output error usage notification-status]}]
  (let [now (db/now-str)
        changed (db/with-transaction store
                  (fn [conn]
                    (let [run (some-> (db/select-one conn ["SELECT * FROM cron_runs WHERE id=?" run-id] identity) row->run)
                          changed (db/execute! conn
                                    ["UPDATE cron_runs SET status=?,notification_status=?,output=?,error=?,usage_json=?,finished_at=?
                                      WHERE id=? AND status IN ('claimed','running')"
                                     (name status) (name (or notification-status (:notification-status run)))
                                     output error (json usage) now run-id])]
                      (when (pos? changed)
                        (db/execute! conn
                          ["UPDATE cron_jobs SET last_run_at=?,last_run_status=?,run_count=run_count+1,
                             failure_count=failure_count+?,updated_at=? WHERE id=?"
                           now (name status) (if (= :failed status) 1 0) now (:job-id run)]))
                      changed)))]
    (when (zero? changed)
      (throw (ex-info "cron run is not active" {:type :run-not-active :run-id run-id})))
    (get-run store run-id)))

(defn update-notification! [store run-id status notification]
  (db/with-connection store
    #(db/execute! % ["UPDATE cron_runs SET notification_status=?,notification_json=? WHERE id=?"
                     (name status) (json notification) run-id]))
  (get-run store run-id))

(defn abandon-active! [store owner-id]
  (let [now (db/now-str)]
    (db/with-connection store
      #(db/execute! %
        ["UPDATE cron_runs SET status='abandoned',error='scheduler stopped before completion',finished_at=?
          WHERE status IN ('claimed','running')
            AND ((? IS NULL AND owner_id IS NOT NULL) OR owner_id=?)" now owner-id owner-id]))))

(defn abandon-stale! [store cutoff]
  (let [now (db/now-str)]
    (db/with-connection store
      #(db/execute! %
        ["UPDATE cron_runs SET status='abandoned',error='stale run recovered at scheduler startup',finished_at=?
          WHERE status IN ('claimed','running') AND owner_id IS NOT NULL
            AND coalesce(started_at,claimed_at,created_at) < ?" now cutoff]))))

(defn counts [store]
  (db/with-connection store
    (fn [conn]
      {:active-jobs (long (or (db/select-value conn ["SELECT count(*) FROM cron_jobs WHERE status='active' AND deleted_at IS NULL"]) 0))
       :running-runs (long (or (db/select-value conn ["SELECT count(*) FROM cron_runs WHERE status IN ('claimed','running')"]) 0))
       :recent-failures (long (or (db/select-value conn ["SELECT count(*) FROM cron_runs WHERE status='failed' AND created_at >= datetime('now','-24 hours')"]) 0))})))

(defn oldest-due-at [store now]
  (db/with-connection store
    #(db/select-value % ["SELECT min(next_run_at) FROM cron_jobs WHERE status='active' AND next_run_at <= ?" now])))
