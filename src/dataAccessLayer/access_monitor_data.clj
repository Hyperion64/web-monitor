(ns dataAccessLayer.access-monitor-data 
  (:require [applicationLayer.shared-functions :as sf]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]))

(def db-spec {:dbtype "sqlite" 
              :dbname (sf/get-project-path "/data/db/web-monitor.db")})

(defn create-db []
    (jdbc/execute! db-spec
                   ["CREATE TABLE IF NOT EXISTS web_monitors
                     (name TEXT PRIMARY KEY, url TEXT, element TEXT, 
                     inner_element TEXT, javascript BOOLEAN, messenger TEXT)"])
    (jdbc/execute! db-spec
                   ["CREATE TABLE IF NOT EXISTS web_elements
                     (monitor_name TEXT, text TEXT, date DATE,
                     PRIMARY KEY (monitor_name, text)
                     FOREIGN KEY (monitor_name) 
                     REFERENCES web_monitors (name))"]))

(defn insert-web-monitor [monitor] 
  (sql/insert! db-spec :web_monitors 
                {:name          (:name          monitor)
                 :url           (:url           monitor) 
                 :element       (:element       monitor)
                 :inner_element (:inner_element monitor)
                 :javascript    (:javascript    monitor)
                 :messenger     (:messenger     monitor)}))

(defn insert-web-element [element]
  (sql/insert! db-spec :web_elements 
                {:monitor_name (:name element) 
                 :text         (:text element) 
                 :date         (:date element)}))

(defn update-web-monitor [monitor]
  (sql/update! db-spec :web_monitors
               {:url           (:url           monitor)
                :element       (:element       monitor)
                :inner_element (:inner_element monitor)
                :javascript    (:javascript    monitor)
                :messenger     (:messenger     monitor)}
               {:name          (:name          monitor)}))

(defn get-all-web-monitors []
  (sql/query db-spec ["SELECT * FROM web_monitors"]))


(defn get-web-elements [monitor]
  (sql/find-by-keys db-spec :web_elements 
                     {:monitor_name (:name monitor)}))

(defn delete-web-monitor [monitor]
  (sql/delete! db-spec :web_monitors
                {:name (:name monitor)}))

(defn delete-web-elements [monitor]
  (sql/delete! db-spec :web_elements 
                {:monitor_name (:name monitor)}))
