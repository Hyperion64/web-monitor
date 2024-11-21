(ns notifications.rss-feed
  (:require [utils.shared-functions :as sf]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [compojure.core :refer [routes GET]]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response])
  (:import [java.io File]))

(defn- get-rss-feed-path [monitor-name]
  (sf/get-file-path (str "/data/rss_feeds/" monitor-name ".xml")))

(defn delete-rss-feed [monitor-name]
  (.delete (io/file (get-rss-feed-path monitor-name))))

(defn- save-rss-feed [file-name new xml-data]
  (let [destination-path (get-rss-feed-path file-name)]
    (if new
      (spit destination-path xml-data :append true)
      (spit destination-path xml-data))))

(defn- make-rss-feed-part [web-element monitor-name]
  (let [text      (:text      web-element)
        hrefs     (:hrefs     web-element)
        datetime  (:datetime  web-element)
        sanitized-text
        (-> text
            (str/replace "&"  "&amp;")
            (str/replace "<"  "&lt;")
            (str/replace ">"  "&gt;")
            (str/replace "\"" "&quot;"))
        link-element (str "<link>"
                          (if-not (nil? hrefs)
                            (first (str/split hrefs #"\n"))
                            "")
                          "</link>")]
    (str "<item>"
         "<title>" sanitized-text "</title>"
         link-element
         "<description>Found at " datetime "</description>"
         "<guid>" (str monitor-name "-" (java.util.UUID/randomUUID)) "</guid>"
         "<pubDate>" datetime "</pubDate>"
         "</item>")))

(defn- create-new-rss-feed [monitor]
  (let [name (:name monitor)
        url (if (not-empty (:url monitor)) (first (:url monitor)) "")
        new-rss-file (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                          "<rss version=\"2.0\">"
                          "<channel>"
                          "<title>" name "</title>"
                          "<link>" url "</link>"
                          "<description></description>"
                          "</channel>
                          </rss>")]
    (save-rss-feed name true new-rss-file)))

(defn process-web-elements [web-elements monitor]
  (let [folder (File. (sf/get-file-path "/data/rss_feeds/"))
        files-array (.listFiles folder)
        feed-names (set (map #(.getName %) files-array))
        feed-exists (contains? feed-names (:name monitor))]
    (when-not feed-exists (create-new-rss-feed monitor)))
  (let [monitor-name (:name monitor)
        rss-web-elements-string
        (apply str (map #(make-rss-feed-part % monitor-name) web-elements))
        feed-parts
        (let [path          (get-rss-feed-path monitor-name)
              parsed-xml    (xml/parse (java.io.FileInputStream. path))
              existing-feed (xml/emit-str parsed-xml)]
          (str/split existing-feed #"</description>" 2))
        new-rss-feed
        (str/join "" [(first feed-parts)
                      (str "</description>" rss-web-elements-string)
                      (second feed-parts)])]
    (save-rss-feed monitor-name false new-rss-feed)))

(defn- handler []
  (routes
   (GET "/:monitor-name.xml" [monitor-name]
     (let [feed-file-path (get-rss-feed-path monitor-name)]
       (when (.exists (File. feed-file-path))
         (response/file-response
          feed-file-path {:content-type "application/rss+xml"}))))))

(defn start-rss-server [config-settings]
  (jetty/run-jetty (handler) {:port (:rss-port config-settings)
                              :join? false}))
