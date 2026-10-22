(ns agent.persistence.sqlite.sessions.queries
  "HugSQL statements shared by session records, messages, branches and channels."
  (:require [hugsql.core :as hugsql]))

(declare create-session-sqlvec
         list-sessions-sqlvec
         session-kind-counts-sqlvec
         session-project-ids-sqlvec
         get-session-sqlvec
         update-session-active-mode-sqlvec
         compare-and-set-session-title-sqlvec
         update-session-title-sqlvec
         update-session-metadata-sqlvec
         session-exists-sqlvec
         insert-message-sqlvec
         list-messages-sqlvec
         get-message-sqlvec
         get-tool-result-sqlvec
         count-messages-sqlvec
         list-recent-messages-sqlvec
         message-usage-stats-sqlvec
         latest-message-usage-sqlvec
         message-tool-counts-sqlvec
         list-messages-after-sqlvec
         update-message-runtime-flags-sqlvec
         update-message-entry-runtime-flags-sqlvec
         update-message-entry-parent-sqlvec
         get-message-entry-by-message-id-sqlvec
         search-messages-like-sqlvec
         search-messages-fts-sqlvec
         get-search-message-by-id-sqlvec
         last-insert-row-id-sqlvec
         insert-completion-sqlvec
         get-channel-session-mapping-sqlvec
         list-channel-session-mappings-sqlvec
         upsert-channel-session-mapping-sqlvec
         insert-session-ignore-sqlvec
         insert-channel-session-mapping-ignore-sqlvec
         touch-channel-session-mapping-sqlvec
         get-channel-offset-sqlvec
         upsert-channel-offset-sqlvec
         upsert-channel-inbox-sqlvec
         update-channel-inbox-status-sqlvec
         get-channel-inbox-update-sqlvec
         insert-session-entry-sqlvec
         get-session-entry-sqlvec
         list-session-entries-sqlvec
         session-ancestor-entries-sqlvec
         latest-session-entry-sqlvec
         get-session-leaf-selection-sqlvec
         upsert-session-leaf-selection-sqlvec
         count-sessions-sqlvec
         list-message-page-before-sqlvec
         list-message-page-after-sqlvec)

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/sessions.sql")
