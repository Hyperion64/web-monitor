(ns monitor-management.run-monitors
  (:require [monitor-management.async-monitor-states :as ams]
            [web.scraper                             :as s]
            [web.html-parser                         :as hp]
            [db.access-db-logic                      :as adl]
            [notifications.send-messages             :as sm]
            [utils.shared-functions                  :as sf]))

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
                (let [first-url-part  (nth (:url-parts url-range) 0)
                      second-url-part (nth (:url-parts url-range) 1)
                      iterator        (nth (:url-parts url-range) 2)
                      tolerance   (:tolerance   url-range)
                      max-scrapes (:max-scrapes url-range)]
                  (iterate-monitoring-url-range
                   first-url-part second-url-part iterator
                   tolerance tolerance max-scrapes))
                [])))]
    (distinct (concat
               (flatten
                (map #(get-html-content (assoc monitor :url %))
                     (:url monitor)))
               (manage-iterate-monitoring-url-range)))))

(defn- transform-to-output-format [all-web-contents monitor]
  (let [monitor-name (:name monitor)
        datetime (sf/make-datetime)
        first-monitoring (empty? (:all-db all-web-contents))]
    (reduce
     (fn [reduced-contents [type-key web-contents]]
       (let [type (name type-key)]
         (assoc reduced-contents
                type-key
                (map (fn [web-content]
                       {:monitor-name     monitor-name
                        :text             (or (:text web-content) "")
                        :hrefs            (:hrefs web-content)
                        :datetime         datetime
                        :first-monitoring first-monitoring
                        :type             type})
                     web-contents))))
     {}
     (dissoc all-web-contents :all-db))))

(defn- manage-notify-user [web-elements-list monitor account-details]
  (let [web-elements-with-href-content
        {:new
         (hp/perform-extract-href-content
          (:new web-elements-list) monitor)
         :removed
         ((if (:notify-if-element-removed monitor)
            #(hp/perform-extract-href-content % monitor)
            identity)
          (:removed web-elements-list))
         :rediscovered
         ((if (:notify-if-element-rediscovered monitor)
            #(hp/perform-extract-href-content % monitor)
            identity)
          (:rediscovered web-elements-list))}
        web-elements-list-modified
        (reduce
         (fn [reduced-elements-list elements-type]
           (assoc reduced-elements-list
                  elements-type
                  (-> web-elements-with-href-content
                      elements-type
                      reverse
                      doall)))
         {}
         [:new :removed :rediscovered])]
    (doseq [[elements-type web-elements] web-elements-list-modified]
      (when-not (empty? web-elements)
        (sm/notify-user elements-type web-elements monitor account-details)))))

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
      (transform-to-output-format monitor)
      (doto
       (#(doseq [[type web-elements] %]
           (adl/save-web-elements type web-elements)))
        (manage-notify-user monitor config-account-details))))

(defn init-organize-regular-monitoring [config-monitors config-account-details]
  (doseq [monitor config-monitors]
    (-> monitor
        manage-perform-monitoring
        (manage-process-web-contents monitor config-account-details))))

(defn- continuous-monitor-loop [monitor driver config-account-details frequency
                                previous-html previous-web-contents]
  (let [[monitor-state-modification iterate-again]
        (case (ams/request-monitor-state monitor)
          "active"                        [nil               true]
          "awaiting-start-confirmation"   ["confirm-start"   true]
          "awaiting-stop-confirmation"    ["confirm-stop"    false]
          "awaiting-restart-confirmation" ["confirm-restart" false]
          [nil false])]
    (when monitor-state-modification
      (ams/modify-continuous-monitor-states monitor monitor-state-modification))
    (when iterate-again
      (let [html (s/fetch-driver driver)
            not-inside? (fn [skip html] (not (some #(= html %) skip)))]
        (if (not-inside? ["" previous-html] html)
          (let [adjusted-monitor (assoc monitor :url (first (:url monitor)))
                web-contents (hp/process-html-string html adjusted-monitor)]
            (when (not-inside? [[] previous-web-contents] web-contents)
              (manage-process-web-contents
               web-contents adjusted-monitor config-account-details))
            (Thread/sleep frequency)
            (recur monitor driver config-account-details frequency html
                   web-contents))
          (do
            (Thread/sleep frequency)
            (recur monitor driver config-account-details frequency html
                   previous-web-contents)))))))

(defn init-organize-continuous-monitoring
  [config-monitors config-account-details]
  (doseq [monitor config-monitors]
    (let [frequency    (:frequency monitor)
          url          (first (:url monitor))
          js-load-time (:js-load-time-seconds monitor)
          browser      (:browser monitor)
          driver       (s/create-driver url js-load-time browser)]
      (future
        (continuous-monitor-loop
         monitor driver config-account-details frequency "" [])))))
