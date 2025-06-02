(ns db.access-db
  (:require [fs.access-files :as af]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(def db-spec {:dbtype "sqlite"
              :dbname (af/get-db-file-path)})

(defn create-db []
  (jdbc/execute!
   db-spec
   ["CREATE TABLE IF NOT EXISTS web_monitors
     (name TEXT PRIMARY KEY, first_defined DATE, active INTEGER,
     content_selectors_hash TEXT)"])
  (jdbc/execute!
   db-spec
   ["CREATE TABLE IF NOT EXISTS web_elements
     (monitor_name TEXT, text TEXT, hrefs TEXT, first_seen DATE,
     not_seen_since DATE, filtered_out INTEGER,
     PRIMARY KEY (monitor_name, text, hrefs)
     FOREIGN KEY (monitor_name) REFERENCES web_monitors (name))"])
  (jdbc/execute!
   db-spec
   ["PRAGMA journal_mode=WAL"]))

(defn insert-web-monitor [monitor-map]
  (sql/insert! db-spec :web_monitors monitor-map))

(defn insert-web-element [element-map]
  (sql/insert! db-spec :web_elements element-map))

(defn get-all-web-monitors []
  (sql/query db-spec ["SELECT * FROM web_monitors"]))

(defn get-web-elements [monitor-name-map]
  (sql/find-by-keys db-spec :web_elements monitor-name-map))

(defn update-web-monitor [[monitor-update-map where-monitor-name]]
  (sql/update! db-spec :web_monitors monitor-update-map where-monitor-name))

(defn update-not-seen-since [[not-seen-since-map where-element-map]]
  (sql/update! db-spec :web_elements not-seen-since-map where-element-map))

(defn delete-web-monitor [monitor-name-map]
  (sql/delete! db-spec :web_monitors monitor-name-map))

(defn delete-web-elements [monitor-name-map]
  (sql/delete! db-spec :web_elements monitor-name-map))

#_(defn delete-web-element [monitor-name element]
    (sql/delete!
     db-spec :web_elements
     {:monitor_name monitor-name
      :text         (:text  element)
      :hrefs        (:hrefs element)}))
