(ns web-monitor.core
  (:gen-class)
  (:require [processing.manage-monitors :as mm]
            [utils.shared-functions :as sf]
            [db.access-db-logic     :as adl]
            [notifications.rss-feed :as rf]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn- format-config-monitors [monitors config-settings]
  (let [missing-setting-updates
        (fn [monitor]
          (for [[setting-key setting-value]
                {:active                         true
                 :javascript                     false
                 :js-load-time-seconds           2
                 :messengers                     #{"rss" "print"}
                 :report-first-found             #{"rss"}
                 :notify-if-element-removed      #{}
                 :notify-if-element-rediscovered #{}
                 :url                            []
                 :url-range                      []
                 :text-css-selectors             []
                 :href-css-selectors             []}]
            (if (nil? (setting-key monitor))
              (if (nil? (setting-key config-settings))
                [setting-key setting-value]
                [setting-key (setting-key config-settings)])
              [])))
        notification-selector-parameters
        [:report-first-found
         :notify-if-element-removed
         :notify-if-element-rediscovered]
        bool-to-coll-updates
        (fn [monitor]
          (for [k notification-selector-parameters]
            (if (boolean? (k monitor))
              (if (k monitor)
                [k (:messengers monitor)]
                [k #{}])
              [])))
        string-or-map-to-vector-updates
        (fn [monitor]
          (for [k
                [:url
                 :text-css-selectors
                 :href-css-selectors]]
            (if (or (string? (k monitor)) (map? (k monitor)))
              [k [(k monitor)]]
              [])))
        string-vector-to-set-updates
        (fn [monitor]
          (for [k
                [:messengers
                 :report-first-found
                 :notify-if-element-removed
                 :notify-if-element-rediscovered]]
            (cond
              (not (coll? (k monitor))) [k #{(k monitor)}]
              (vector?    (k monitor))  [k (set (k monitor))]
              :else                     [])))
        report-first-rss-updates
        (fn [monitor]
          (if (contains? (:messengers monitor) "rss")
            (let [report-first-found (:report-first-found monitor)]
              (list (if-not (contains? report-first-found "rss")
                      [:report-first-found (conj report-first-found "rss")]
                      [])))
            []))
        remove-irrelevant-details-updates
        (fn [monitor]
          (for [notification-selector-parameter
                notification-selector-parameters]
            [notification-selector-parameter
             (set (remove nil?
                          (for [notification-selector-value
                                (notification-selector-parameter monitor)]
                            (when (contains? (:messengers monitor)
                                             notification-selector-value)
                              notification-selector-value))))]))
        add-infinite-url-range-limit
        (fn [monitor]
          (let [url-range (:url-range monitor)
                infinity  Double/POSITIVE_INFINITY]
            (if-not (nil? url-range)
              (list (cond
                      (= (count url-range) 2)
                      [:url-range (conj url-range 1 5 infinity)]
                      (= (count url-range) 3)
                      [:url-range (conj url-range 5 infinity)]
                      (= (count url-range) 4)
                      [:url-range (conj url-range infinity)]
                      :else
                      []))
              [])))
        manage-apply-settings
        (fn [monitor]
          (letfn [(flatten-only-first-depth [coll]
                    (mapcat identity coll))
                  (apply-settings [monitor update-fn]
                    (let [flattened-params
                          (flatten-only-first-depth (update-fn monitor))]
                      (if (seq flattened-params)
                        (apply assoc monitor flattened-params)
                        monitor)))]
            (reduce apply-settings
                    monitor
                    [missing-setting-updates
                     bool-to-coll-updates
                     string-or-map-to-vector-updates
                     string-vector-to-set-updates
                     report-first-rss-updates
                     remove-irrelevant-details-updates
                     add-infinite-url-range-limit])))]
    (map manage-apply-settings monitors)))

(defn get-config []
  (let [config-path (sf/get-file-path "/config.json")]
    (with-open [reader (io/reader config-path)]
      (json/read reader :key-fn keyword))))

(defn- loop-monitoring-process []
  (let [config                 (get-config)
        config-settings        (:settings config)
        frequency-minutes      (:frequency-minutes config-settings)
        config-account-details (:account-details config)
        config-monitors (format-config-monitors
                         (:monitors config) config-settings)
        active-config-monitors (filter #(:active %) config-monitors)]
    (adl/update-db-monitors config-monitors)
    (mm/organize-monitoring active-config-monitors config-account-details)
    (Thread/sleep (* 1000 60 frequency-minutes)))
  (recur))

(defn -main []
  (adl/create-db)
  (rf/start-rss-server (:settings (get-config)))
  (loop-monitoring-process))
