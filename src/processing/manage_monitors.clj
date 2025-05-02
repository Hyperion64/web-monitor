(ns processing.manage-monitors
  (:require [web.scraper                 :as s]
            [processing.html-parser      :as hp]
            [db.access-db-logic          :as adl]
            [notifications.send-messages :as sm]
            [utils.shared-functions      :as sf]))

(defn- manage-perform-monitoring [monitor]
  (letfn [(get-html-content [monitor]
            (-> monitor
                s/fetch-html-string
                (hp/process-html-string monitor)))
          (iterate-monitoring-url-range
            [first-url-part second-url-part iterator
             tolerance max-tolerance max-scrapes]
            (let [iterated-url
                  (str first-url-part iterator second-url-part)
                  monitor-with-iterated-url
                  (assoc monitor :url iterated-url)
                  iterated-html-content
                  (get-html-content monitor-with-iterated-url)]
              (concat iterated-html-content
                      (cond
                        (= max-scrapes 0)
                        []
                        (not-empty iterated-html-content)
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
               (flatten
                (map #(get-html-content (assoc monitor :url %))
                     (:url monitor)))
               (manage-iterate-monitoring-url-range)))))

(defn- manage-create-output [all-web-contents monitor account-details]
  (let [create-web-elements
        (fn [web-contents web-content-type]
          (map (fn [web-content]
                 {:monitor-name     (:name monitor)
                  :text             (or (:text web-content) "")
                  :hrefs            (:hrefs web-content)
                  :datetime         (sf/make-datetime)
                  :first-monitoring (empty? (:all-db all-web-contents))
                  :type             web-content-type})
               web-contents))
        web-elements-list
        (map (fn [type-key]
               (create-web-elements (type-key all-web-contents)
                                    (name type-key)))
             [:new :removed :rediscovered :filtered-out])]
    (doseq [web-elements web-elements-list]
      (when (not-empty web-elements)
        (adl/save-web-elements web-elements)
        (when-not (= (:type (first web-elements)) :filtered-out)
          (sm/notify-user (reverse web-elements) monitor account-details))))))

(defn- sort-web-contents [web-contents-db web-contents]
  (let [new-web-contents
        (sf/ordered-complement
         web-contents (:all web-contents-db))
        removed-web-contents
        (sf/ordered-complement
         (:existing web-contents-db) web-contents)
        rediscovered-web-contents
        (sf/ordered-conjunction
         web-contents (:removed web-contents-db))]
    {:new          new-web-contents
     :removed      removed-web-contents
     :rediscovered rediscovered-web-contents
     :all-db       (:all web-contents-db)}))

(defn- manage-post-processing-filtering [sorted-web-contents monitor]
  (let [new-web-contents
        (:new sorted-web-contents)
        filtered-new-web-contents
        (hp/manage-new-web-content-filtering new-web-contents monitor)
        filtered-out-new-web-contents
        (sf/ordered-complement new-web-contents filtered-new-web-contents)]
    (assoc sorted-web-contents
           :new          filtered-new-web-contents
           :filtered-out filtered-out-new-web-contents)))

(defn- manage-process-web-contents [web-contents monitor config-account-details]
  (-> monitor
      adl/get-web-contents-db
      (sort-web-contents web-contents)
      (manage-post-processing-filtering monitor)
      (manage-create-output monitor config-account-details)))

(defn init-organize-regular-monitoring [config-monitors config-account-details]
  (doseq [monitor config-monitors]
    (-> monitor
        manage-perform-monitoring
        (manage-process-web-contents monitor config-account-details))))

(defn init-organize-continuous-monitoring
  [config-monitors config-account-details]
  (doseq [monitor config-monitors]
    (let [frequency    (:frequency            monitor)
          url          (first (:url           monitor))
          js-load-time (:js-load-time-seconds monitor)
          browser      (:browser              monitor)
          driver (s/create-driver url js-load-time browser)
          not-inside? (fn [skip-elements html-string]
                        (not (some #(= html-string %) skip-elements)))]
      (future
        (loop [previous-html         ""
               previous-web-contents []]
          (let [html (s/fetch-driver driver)]
            (if (not-inside? ["" previous-html] html)
              (let [adjusted-monitor (assoc monitor :url url)
                    web-contents (hp/process-html-string html adjusted-monitor)]
                (when (not-inside? [[] previous-web-contents] web-contents)
                  (manage-process-web-contents
                   web-contents adjusted-monitor config-account-details))
                (Thread/sleep frequency)
                (recur html web-contents))
              (do (Thread/sleep frequency)
                  (recur html previous-web-contents)))))))))
