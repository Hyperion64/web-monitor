(ns notifications.send-messages
  (:require [utils.shared-functions :as sf]
            [notifications.rss-feed :as rf]
            [clojure.data.json :as json]
            [clj-http.client :as client]
            [clojure.java.shell :refer [sh]]))

(defn- send-matrix-message
  [web-elements monitor-name matrix-account-details]
  (let [make-message-header
        (fn [web-element]
          (apply str (case (:type web-element)
                       "new"          ["New element found in "
                                       monitor-name]
                       "removed"      ["Element was removed from "
                                       monitor-name]
                       "rediscovered" ["Element was rediscovered in "
                                       monitor-name])))
        make-message
        (fn [web-element]
          (str (make-message-header web-element) ":\n"
               (:text web-element) "\n" (:hrefs web-element)
               "\n\n"))
        combined-messages (apply str (map #(make-message %) web-elements))
        homeserver (:homeserver matrix-account-details)
        username   (:username   matrix-account-details)
        password   (:password   matrix-account-details)
        room-id    (:room-id    matrix-account-details)
        login-url (str homeserver "/_matrix/client/r0/login")
        login-params {:body (json/write-str {:type "m.login.password"
                                             :user username
                                             :password password})
                      :content-type :json}
        login-response (client/post login-url login-params)
        response-body (json/read-str (:body login-response) :key-fn keyword)
        token (:access_token response-body)
        message-url (str homeserver "/_matrix/client/r0/rooms/"
                         room-id "/send/m.room.message")
        message-params {:body (json/write-str
                               {:msgtype "m.text" :body combined-messages}
                               :escape-slash false)
                        :headers {"Authorization" (str "Bearer " token)}
                        :content-type :json}]
    (client/post message-url message-params)))

(defn- make-json-string [web-elements]
  (json/write-str web-elements :escape-slash false))

(defn- send-custom-script-message [json-string message-script-name]
  (let [project-path
        (sf/get-file-path
         (str "/resources/notification_scripts/" message-script-name))]
    (sh project-path json-string)))

(defn notify-user [web-elements first-monitoring monitor account-details]
  (let [elements-type (:type (first web-elements))
        matrix-account-details (:matrix account-details)
        messengers (:messengers monitor)
        monitor-name (:name monitor)
        report-first-found (:report-first-found monitor)
        notify-if-element-removed (:notify-if-element-removed monitor)
        notify-if-element-rediscovered
        (:notify-if-element-rediscovered monitor)
        json-string (make-json-string web-elements)]
    (doseq [messenger messengers]
      (when
       (and (or (not first-monitoring)
                (contains? report-first-found messenger))
            (or (= elements-type "new")
                (and (= elements-type "removed")
                     (contains? notify-if-element-removed messenger))
                (and (= elements-type "rediscovered")
                     (contains? notify-if-element-rediscovered messenger))))
        (case messenger
          "rss"
          (rf/process-web-elements (reverse web-elements) monitor)
          "matrix"
          (send-matrix-message web-elements monitor-name matrix-account-details)
          "print"
          (println json-string)
          "notify-send"
          (doseq  [element-texts (map #(:text %) web-elements)]
            (sh "notify-send" element-texts)
            (Thread/sleep (* 1000 2)))
          (send-custom-script-message json-string messenger))))))
