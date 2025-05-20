(ns fs.access-files
  (:require [clojure.java.io   :as io]
            [clojure.string    :as str]
            [clojure.data.json :as json])
  (:import [java.io File]))

;private
(defn- get-relative-dir-root-path []
  (let [classpath-elements
        (str/split (System/getProperty "java.class.path") #":")
        get-base-path
        (fn [full-directory]
          (str (subs full-directory 0
                     (str/last-index-of full-directory "/")) "/"))
        get-jar-path
        (fn [& [ns]]
          (-> (or ns (class *ns*))
              .getProtectionDomain
              .getCodeSource
              .getLocation
              .toURI
              .getPath))]
    (get-base-path
     (if (= (count classpath-elements) 1)
       (get-jar-path)
       (first classpath-elements)))))

;in the future when the configuration will be in ~/.config
#_(defn- get-home-dir-root-path []
    (let [home-dir (System/getProperty "user.home")
          web-monitor-home-config-dir (str home-dir "/.config/web_monitor/")]
      (Paths/get web-monitor-home-config-dir)))

(defonce ^:private root-path (get-relative-dir-root-path))

;general
(defn get-file-path [path-end]
  (str root-path path-end))

;config
(defn get-config []
  (let [config-path (get-file-path "/config.json")]
    (with-open [reader (io/reader config-path)]
      (json/read reader :key-fn keyword))))

;rss
(defn get-rss-feed-path [monitor-name]
  (get-file-path (str "/data/rss_feeds/" monitor-name ".xml")))

(defn delete-rss-feed [monitor-name]
  (.delete (io/file (get-rss-feed-path monitor-name))))

(defn save-rss-feed [monitor-name xml-data]
  (spit (get-rss-feed-path monitor-name) xml-data))

(defn read-rss-feed [monitor-name]
  (slurp (get-rss-feed-path monitor-name)))

(defn rss-feed-exists? [monitor-name]
  (.exists (File. (get-rss-feed-path monitor-name))))

;db
(defn get-db-file-path []
  (get-file-path "/data/db/web-monitor.db"))

;resources
(defn get-notification-script-path [message-script-name]
  (get-file-path (str "/resources/notification_scripts/" message-script-name)))

(defn get-filter-script-path [filter-script-name]
  (get-file-path (str "/resources/filter_scripts/" filter-script-name)))

(defn- get-url-files-path [url-file-name]
  (get-file-path (str "/resources/url_files/" url-file-name)))

(defn read-in-url-file [url-file-name]
  (with-open [reader (io/reader (get-url-files-path url-file-name))]
    (doall (line-seq reader))))

;log-files
(defn append-log-element [log-string log-file-id]
  (spit (get-file-path (str "/data/log_files/" log-file-id ".log"))
        (str log-string "\n")
        :append true))
