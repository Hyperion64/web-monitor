(ns presentationLayer.rss-feed
  (:require [applicationLayer.shared-functions :as sf]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [compojure.core :refer [routes GET]]
            [ring.adapter.jetty :as jetty] 
            [ring.util.response :as response])
  (:import [java.io File]))

(defn- get-rss-feed-path [monitor-name]
  (sf/get-project-path (str "/data/rss_feeds/" monitor-name ".xml")))

(defn- save-xml-data [file-name xml-data new]
  (let [destination-path (get-rss-feed-path file-name)]
    (if new
      (spit destination-path xml-data :append true)
      (spit destination-path xml-data))))

(defn- get-existing-feed [name]
  (let [path (get-rss-feed-path name)
        parsed-xml (xml/parse (java.io.FileInputStream. path))]
    (xml/emit-str parsed-xml)))

(defn- insert-item [original new-str]
  (let [parts (str/split original #"</description>" 2)]
    (str/join "" [(first parts) (str "</description>" new-str) 
                  (second parts)])))

(defn- add-to-rss-feed [monitor message date]
  (let [name (:name monitor)
        new-item (str "<item>"
                      "<title>" message "</title>"
                      "<link>" (:url monitor) "</link>"
                      "<description>Found at " date "</description>"
                      "<guid>" (str name "-" 
                                    (java.util.UUID/randomUUID)) "</guid>"
                      "<pubDate>" date "</pubDate>"
                      "</item>")
        existing-feed (get-existing-feed name)
        combined-feed (insert-item existing-feed new-item)]
    (save-xml-data name combined-feed false)))

(defn- create-new-rss-feed [monitor]
  (let [name (:name monitor)
        new-rss-file (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                          "<rss version=\"2.0\">"
                          "<channel>"
                          "<title>" name "</title>"
                          "<link>" (:url monitor) "</link>"
                          "<description>"
                          "</description></channel>
                          </rss>")]
    (save-xml-data name new-rss-file true)))

(defn- get-all-feed-names []
  (let [folder (File. (sf/get-project-path "/data/rss_feeds/"))
        files-array (.listFiles folder)]
    (set (map #(.getName %) files-array))))

(defn process-message [message monitor date]
  (let [feed-names (get-all-feed-names)
        monitor-file-name (str (:name monitor) ".xml")
        feed-exists (contains? feed-names monitor-file-name)
        sanitized-message (-> message
                              (str/replace "&" "&amp;")
                              (str/replace "<" "&lt;")
                              (str/replace ">" "&gt;")
                              (str/replace "\"" "&quot;")
                              (str/replace "'" "&apos;"))]
    (when-not feed-exists
      (create-new-rss-feed monitor))
    (add-to-rss-feed monitor sanitized-message date)))

(defn- handler []
  (routes
   (GET "/:monitor-name.xml" [monitor-name]
     (let [feed-file-path (get-rss-feed-path monitor-name)]
       (when (.exists (File. feed-file-path))
         (response/file-response feed-file-path 
                                 {:content-type "application/rss+xml"}))))))

(defn start-rss-server [config-settings]
  (jetty/run-jetty (handler) {:port (:rss_port config-settings) 
                                      :join? false}))
