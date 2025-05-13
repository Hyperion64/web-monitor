(ns notifications.send-messages
  (:require [utils.shared-functions :as sf]
            [fs.access-files        :as af]
            [notifications.rss-feed :as rf]
            [clojure.data.json :as json]
            [clj-http.client :as client]
            [clojure.java.shell :refer [sh]]))

(defn- send-matrix-message [web-elements monitor-name matrix-account-details]
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

(defn- send-custom-script-message [json-string message-script-name]
  (let [project-path
        (af/get-notification-script-path message-script-name)]
    (sh project-path json-string)))

(defn notify-user [elements-type web-elements monitor account-details]
  (let [first-monitoring   (:first-monitoring (first web-elements))
        messengers         (:messengers monitor)
        report-first-found (:report-first-found monitor)]
    (doseq [messenger messengers]
      (when (and (or (not first-monitoring)
                     (contains? report-first-found messenger))
                 (elements-type
                  {:new
                   true
                   :removed
                   ((:notify-if-element-removed monitor) messenger)
                   :rediscovered
                   ((:notify-if-element-rediscovered monitor) messenger)}))
        (case messenger
          "rss"
          (rf/process-web-elements (reverse web-elements) monitor)
          "matrix"
          (send-matrix-message
           web-elements (:name monitor) (:matrix account-details))
          "print"
          (doseq [web-element web-elements]
            (println (sf/make-json-string web-element)))
          "notify-send"
          (doseq  [element-texts (map #(:text %) web-elements)]
            (sh "notify-send" element-texts)
            (Thread/sleep (* 1000 2)))
          (send-custom-script-message
           (sf/make-json-string web-elements) messenger))))))
