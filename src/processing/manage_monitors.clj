(ns processing.manage-monitors
  (:require [processing.html-parser      :as hp]
            [db.access-db-logic          :as adl]
            [notifications.send-messages :as sm]
            [utils.shared-functions      :as sf]))

(defn- manage-perform-monitoring [monitor]
  (letfn [(iterate-monitoring-url-range
            [first-url-part second-url-part iterator
             tolerance max-tolerance max-scrapes]
            (let [iterated-url
                  (str first-url-part iterator second-url-part)
                  iterated-url-content
                  (hp/perform-monitoring (assoc monitor :url iterated-url))]
              (concat iterated-url-content
                      (cond
                        (= max-scrapes 0)
                        []
                        (not-empty iterated-url-content)
                        (iterate-monitoring-url-range
                         first-url-part second-url-part (+ iterator 1)
                         max-tolerance max-tolerance (- max-scrapes 1))
                        (not (= tolerance -1))
                        (iterate-monitoring-url-range
                         first-url-part second-url-part (+ iterator 1)
                         (- tolerance 1) max-tolerance max-scrapes)
                        :else
                        []))))
          (manage-iterate-monitoring-url-range []
            (let [url-range (:url-range monitor)]
              (if-not (empty? url-range)
                (let [first-url-part  (nth    url-range 0)
                      second-url-part (nth    url-range 1)
                      iterator        (nth    url-range 2)
                      tolerance       (nth    url-range 3)
                      max-scrapes     (nth    url-range 4)]
                  (iterate-monitoring-url-range
                   first-url-part second-url-part iterator
                   tolerance tolerance max-scrapes))
                [])))]
    (distinct (concat
               (flatten (map #(hp/perform-monitoring
                               (assoc monitor :url %)) (:url monitor)))
               (manage-iterate-monitoring-url-range)))))

(defn- process-web-contents
  [monitor account-details first-monitoring
   new-web-contents removed-web-contents rediscovered-web-contents]
  (letfn [(create-web-elements [web-contents type]
            (map (fn [web-content]
                   {:monitor-name     (:name monitor)
                    :text             (:text web-content)
                    :hrefs            (:hrefs web-content)
                    :datetime         (sf/make-datetime)
                    :first-monitoring first-monitoring
                    :type             type})
                 web-contents))]
    (let [web-elements-list
          (map #(apply create-web-elements %)
               [[new-web-contents          "new"]
                [removed-web-contents      "removed"]
                [rediscovered-web-contents "rediscovered"]])]
      (doseq [web-elements web-elements-list]
        (when (not-empty web-elements)
          (adl/save-web-elements web-elements)
          (sm/notify-user
           (reverse web-elements) first-monitoring monitor account-details))))))

(defn organize-monitoring [config-monitors config-account-details]
  (doseq [monitor config-monitors]
    (let [extracted-web-contents
          (manage-perform-monitoring monitor)
          [all-web-contents-db
           existing-web-contents-db
           removed-web-contents-db]
          (adl/get-web-contents-db monitor)
          new-web-contents
          (sf/ordered-complement extracted-web-contents
                                 all-web-contents-db)
          removed-web-contents
          (sf/ordered-complement existing-web-contents-db
                                 extracted-web-contents)
          rediscovered-web-contents
          (sf/ordered-conjunction extracted-web-contents
                                  removed-web-contents-db)
          first-monitoring
          (empty? all-web-contents-db)]
      (process-web-contents
       monitor config-account-details first-monitoring
       new-web-contents removed-web-contents rediscovered-web-contents))))
