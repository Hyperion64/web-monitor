(ns presentationLayer.send-messages
  (:require [presentationLayer.rss-feed :as rf] 
            [cheshire.core :as json]
            [clj-http.client :as client]))

(defn- matrix-login
  [homeserver-url username password]
  (let [url (str homeserver-url "/_matrix/client/r0/login")
        params {:body (json/generate-string {:type "m.login.password"
                                             :user username
                                             :password password})
                :content-type :json}]
    (client/post url params)))

(defn- send-matrix-message [msg config-matrix]
  (let [homeserver (:homeserver config-matrix)
        username (:username   config-matrix)
        password (:password   config-matrix)
        room-id (:room_id config-matrix)
        login-response (matrix-login homeserver
                                     username
                                     password)
        token (:access_token (json/parse-string
                              (:body login-response) true))
        url (str homeserver "/_matrix/client/r0/rooms/"
                 room-id "/send/m.room.message")
        params {:body (json/generate-string {:msgtype "m.text" :body msg})
                :headers {"Authorization" (str "Bearer " token)}
                :content-type :json}]
    (client/post url params)))

(defn notify-user [new-web-element monitor first-monitoring config-messengers]
  (let [date (:date new-web-element)
        message (:text new-web-element)
        messenger-text (str (:name new-web-element) " at " date ": \n" 
                            message)]
    (letfn [(is-messenger? [messenger] 
                                 (= messenger (:messenger monitor) )
                                 ;(some #(= messenger %) (:messenger monitor))
                                 )] 
              (when (is-messenger? "rss") 
                (rf/process-message message monitor date))
              (when-not first-monitoring 
                (when (is-messenger? "matrix")
                   (send-matrix-message 
                    messenger-text (:matrix config-messengers)))))))
