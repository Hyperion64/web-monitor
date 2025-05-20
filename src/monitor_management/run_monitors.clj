(ns monitor-management.run-monitors
  (:require [monitor-management.async-monitor-states :as ams]
            [web.scrape-logic                        :as sl]
            [web.scraper                             :as s]
            [db.access-db-logic                      :as adl]
            [notifications.send-messages             :as sm]
            [utils.shared-functions                  :as sf]
            [utils.timestamps                        :as t]))

(defn- transform-to-output-format [all-web-contents monitor]
  (let [monitor-name (:name monitor)
        datetime (t/make-datetime)
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
         (sl/perform-extract-href-content
          (:new web-elements-list) monitor)
         :removed
         ((if (:notify-if-element-removed monitor)
            #(sl/perform-extract-href-content % monitor)
            identity)
          (:removed web-elements-list))
         :rediscovered
         ((if (:notify-if-element-rediscovered monitor)
            #(sl/perform-extract-href-content % monitor)
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
        (sl/manage-new-web-content-filtering new-web-contents monitor)
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

(defn- initialize-regular-monitor [monitor config-account-details]
  (-> monitor
      sl/manage-regular-scrape
      (manage-process-web-contents monitor config-account-details)))

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
    (if iterate-again
      (let [adjusted-monitor
            (assoc monitor :url (first (:url monitor)))
            {:keys [html html-is-new web-contents web-contents-are-new]}
            (sl/manage-continuous-scrape
             driver adjusted-monitor previous-html previous-web-contents)]
        (if html-is-new
          (do
            (when web-contents-are-new
              (manage-process-web-contents
               web-contents adjusted-monitor config-account-details))
            (Thread/sleep frequency)
            (recur monitor driver config-account-details frequency html
                   web-contents))
          (do
            (Thread/sleep frequency)
            (recur monitor driver config-account-details frequency html
                   previous-web-contents))))
      (s/quit-driver driver))))

(defn- initialize-continuous-monitor [monitor config-account-details]
  (let [frequency    (:frequency monitor)
        url          (first (:url monitor))
        js-load-time (:js-load-time-seconds monitor)
        browser      (:browser monitor)
        driver       (s/create-driver url js-load-time browser)]
    (future
      (continuous-monitor-loop
       monitor driver config-account-details frequency "" []))))

(defn initialize-monitors
  [regular-monitors continuous-monitors config-account-details]
  (doseq [continuous-monitor continuous-monitors]
    (initialize-continuous-monitor continuous-monitor config-account-details))
  (doseq [regular-monitor regular-monitors]
    (initialize-regular-monitor regular-monitor config-account-details)))
