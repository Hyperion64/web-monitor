(ns notifications.rss-feed
  (:require [clojure.string :as str]
            [fs.access-files :as af]
            [compojure.core :refer [routes GET]]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response])
  (:import [java.time LocalDateTime ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

(defn to-rss-date [date-str]
  (let [input-formatter
        (DateTimeFormatter/ofPattern "dd.MM.yyyy HH:mm:ss")
        rfc822-formatter
        (DateTimeFormatter/ofPattern
         "EEE, dd MMM yyyy HH:mm:ss Z" java.util.Locale/ENGLISH)
        local-dt
        (LocalDateTime/parse date-str input-formatter)
        zoned-dt
        (ZonedDateTime/of local-dt (ZoneId/systemDefault))]
    (.format zoned-dt rfc822-formatter)))

(defn- make-rss-feed-item [web-element monitor-name]
  (let [web-text
        (:text web-element)
        hrefs
        (:hrefs web-element)
        datetime
        (to-rss-date (:datetime web-element))
        sanitize-text
        (fn [text]
          (-> text
              (str/replace "&"  "&amp;")
              (str/replace "<"  "&lt;")
              (str/replace ">"  "&gt;")
              (str/replace "\"" "&quot;")))
        split-web-text
        (str/split web-text #"\n\n\n")
        [title content]
        (map sanitize-text
             (if (= (count split-web-text) 1)
               (conj split-web-text "")
               split-web-text))
        paragraphed-content
        (str "<p>" (str/join "</p><p>" (str/split content #"\n{2,}")) "</p>")
        link-element
        (if-not (nil? hrefs) (first (str/split hrefs #"\n")) "")]
    (str "<item>"
         "<title>" title "</title>"
         "<link>" link-element "</link>"
         "<description>Found at " datetime "</description>"
         "<guid>" (str monitor-name "-" (java.util.UUID/randomUUID)) "</guid>"
         "<pubDate>" datetime "</pubDate>"
         "<content:encoded><![CDATA[<p>"
         paragraphed-content
         "</p>]]></content:encoded>"
         "</item>")))

(defn- create-new-rss-feed [monitor]
  (let [name (:name monitor)
        url (if (not-empty (:url monitor)) (first (:url monitor)) "")
        new-rss-file
        (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
             "<rss version=\"2.0\" "
             "xmlns:content=\"http://purl.org/rss/1.0/modules/content/\">"
             "<channel>"
             "<title>" name "</title>"
             "<link>" url "</link>"
             "<description></description></channel></rss>")]
    (af/save-rss-feed name new-rss-file)))

(defn process-web-elements [web-elements monitor]
  (let [feed-exists (af/rss-feed-exists? (:name monitor))]
    (when-not feed-exists (create-new-rss-feed monitor)))
  (let [monitor-name (:name monitor)
        rss-web-elements-string
        (apply str (map #(make-rss-feed-item % monitor-name) web-elements))
        feed-parts
        (let [existing-feed (af/read-rss-feed monitor-name)]
          (str/split existing-feed #"</description>" 2))
        new-rss-feed
        (str/join "" [(first feed-parts)
                      (str "</description>" rss-web-elements-string)
                      (second feed-parts)])]
    (af/save-rss-feed monitor-name new-rss-feed)))

(defn- handler []
  (routes
   (GET "/:monitor-name.xml" [monitor-name]
     (let [feed-file-path (af/get-rss-feed-path monitor-name)]
       (when (af/rss-feed-exists? monitor-name)
         (response/file-response
          feed-file-path {:content-type "application/rss+xml"}))))))

(defn start-rss-server [config-settings]
  (jetty/run-jetty (handler) {:port (:rss-port config-settings)
                              :join? false}))
