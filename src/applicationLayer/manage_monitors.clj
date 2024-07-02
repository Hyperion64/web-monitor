(ns applicationLayer.manage-monitors
  (:require [applicationLayer.html-parser :as hp]
            [dataAccessLayer.access-monitor-data :as amd] 
            [presentationLayer.send-messages :as sm] 
            [applicationLayer.shared-functions :as sf] 
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.set :refer [difference]]))

(defn- manage-remove-namespaces [db-monitors]
  (letfn [(remove-namespaces [db-monitor]
                             (into {} (for [[k v] db-monitor]
                                        [(keyword (name k)) v])))]
    (set (map remove-namespaces db-monitors))))

(defn- monitor-convert-js [monitor json-db?]
  (let [[test-val result-true result-false]
        (case json-db?
          "json" [true 1 0]
          "db"   [1 true false])] 
    (assoc monitor :javascript
           (if (= (:javascript monitor) test-val)
             result-true 
             result-false))))

(defn- manage-save-web-monitor [db-function monitor]
  (let [converted-monitor (monitor-convert-js monitor "json")]
    (db-function converted-monitor)))

(defn- manage-get-web-monitors []
  (let [monitors (manage-remove-namespaces (amd/get-all-web-monitors))] 
    (set (map #(monitor-convert-js % "db") monitors))))

(defn- manage-get-web-elements-text [monitor]
  (let [web-elements (manage-remove-namespaces (amd/get-web-elements monitor))]
    (set (map :text web-elements))))

(defn- compare-db-config-monitors [config-monitors]
  (letfn [(get-difference [set-1 set-2] 
                          (difference set-1 set-2))
          (get-monitor-differences [db-monitors config-monitors]
             {:in-config-only (get-difference config-monitors db-monitors)
              :in-db-only     (get-difference db-monitors config-monitors)})
          (filter-remove-same-names [f vector-1 vector-2]
                        (f #(some (fn [map] 
                                    (= (% :name) (map :name)))
                                       vector-1)
                                vector-2))
          (get-modified-monitors [monitor-differences]
                                 (filter-remove-same-names 
                                  filter 
                                  (:in-db-only     monitor-differences)
                                  (:in-config-only monitor-differences)))
          (get-compared-monitors [monitor-differences monitor-modified] 
                                 {:new      (filter-remove-same-names
                                             remove 
                                             monitor-modified
                                             (:in-config-only 
                                              monitor-differences))
                                  :deleted  (filter-remove-same-names 
                                             remove
                                             monitor-modified
                                             (:in-db-only 
                                              monitor-differences))
                                  :modified monitor-modified})]
    (let [config-monitors-set (set config-monitors)
          db-monitors  (manage-get-web-monitors)
          monitor-differences (get-monitor-differences 
                               db-monitors config-monitors-set)
          modified-monitors   (get-modified-monitors monitor-differences)] 
      (get-compared-monitors monitor-differences modified-monitors))))

(defn- update-db-monitors [config-monitors]
  (let [compared-monitors 
        (compare-db-config-monitors config-monitors)
        {:keys [new deleted modified]} compared-monitors] 
    (doseq [n new]      (manage-save-web-monitor amd/insert-web-monitor n))
    (doseq [d deleted]  ((amd/delete-web-monitor d)
                         (amd/delete-web-elements d)))
    (doseq [m modified] ((manage-save-web-monitor amd/update-web-monitor m)
                         (amd/delete-web-elements m)))))

(defn- create-web-element [web-element-text monitor]
  (let [now (time/now)
        formatter (time-format/formatter "dd.MM.yyyy HH:mm") 
        date (time-format/unparse formatter now)] 
    {:name (:name monitor) :text web-element-text :date date}))

(defn- ordered-difference [coll1 coll2]
  (let [set-coll2 (set coll2)]
    (filter (complement set-coll2) coll1)))

(defn- perform-monitoring [db-monitors config-settings config-messengers]
  (doseq [monitor db-monitors]
    (let [web-elements-text
          (hp/perform-monitoring monitor config-settings)
          web-elements-text-db
          (manage-get-web-elements-text monitor)
          new-web-elements-text
          (reverse
           (ordered-difference web-elements-text web-elements-text-db))
          first-monitoring (empty? web-elements-text-db)]
      (doseq [new-web-element-text new-web-elements-text]
        (let [new-web-element 
              (create-web-element new-web-element-text monitor)]
          (amd/insert-web-element new-web-element)
          (sm/notify-user
           new-web-element monitor first-monitoring config-messengers))))))

(defn manage-monitoring []
  (let [config (sf/get-config)
        config-monitors (:monitors config)
        config-settings (:settings config)
        config-messengers (:messengers config)]
    (update-db-monitors config-monitors)
    (let [db-monitors (manage-get-web-monitors)] 
      (perform-monitoring db-monitors config-settings config-messengers))
    (Thread/sleep (* 1000 60 (:frequency_minutes config-settings)))
    (manage-monitoring)))
