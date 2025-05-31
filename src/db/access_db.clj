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

(defn insert-web-monitor [monitor]
  (sql/insert!
   db-spec :web_monitors
   {:name                   (:name                   monitor)
    :first_defined          (:datetime               monitor)
    :active                 (:active                 monitor)
    :content_selectors_hash (:content_selectors_hash monitor)}))

(defn insert-web-element [element]
  (sql/insert!
   db-spec :web_elements
   {:monitor_name (:monitor-name element)
    :text         (:text         element)
    :hrefs        (:hrefs        element)
    :first_seen   (:datetime     element)
    :filtered_out (:filtered-out element)}))

(defn get-all-web-monitors []
  (sql/query
   db-spec
   ["SELECT * FROM web_monitors"]))

(defn get-web-elements [monitor-name]
  (sql/find-by-keys
   db-spec :web_elements
   {:monitor_name monitor-name}))

(defn update-web-monitor [monitor-update]
  (sql/update!
   db-spec :web_monitors
   {:active                 (:active                 monitor-update)
    :content_selectors_hash (:content_selectors_hash monitor-update)}
   {:name                   (:name                   monitor-update)}))

(defn update-not-seen-since [updated-element]
  (sql/update!
   db-spec :web_elements
   {:not_seen_since (:not-seen-since updated-element)}
   {:monitor_name   (:monitor-name   updated-element)
    :text           (:text           updated-element)
    :hrefs          (:hrefs          updated-element)}))

(defn delete-web-monitor [monitor-name]
  (sql/delete!
   db-spec :web_monitors
   {:name monitor-name}))

(defn delete-web-elements [monitor-name]
  (sql/delete!
   db-spec :web_elements
   {:monitor_name monitor-name}))

#_(defn delete-web-element [monitor-name element]
    (sql/delete!
     db-spec :web_elements
     {:monitor_name monitor-name
      :text         (:text  element)
      :hrefs        (:hrefs element)}))
