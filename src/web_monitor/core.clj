(ns web-monitor.core
  (:gen-class)
  (:require [processing.manage-monitors :as mm]
            [processing.parse-input :as pi]
            [utils.shared-functions :as sf]
            [db.access-db-logic     :as adl]
            [notifications.rss-feed :as rf]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn- time-adjust-config-monitors [clock-maps monitors]
  (let [get-names
        (fn [name-map] (set (map #(:name %) name-map)))
        monitor-names
        (get-names monitors)
        clock-map-names
        (get-names clock-maps)
        partially-monitor-updated-clock-maps
        (filter #(contains? monitor-names (:name %)) clock-maps)
        min-clock
        (if (seq partially-monitor-updated-clock-maps)
          (apply min (map #(:clock %) partially-monitor-updated-clock-maps))
          0)
        time-updated-clock-maps
        (map #(update (update % :clock - min-clock) :url-range-clock dec)
             partially-monitor-updated-clock-maps)
        monitor-updated-clock-maps
        (reduce (fn [iterated-clock-maps monitor]
                  (if (contains? clock-map-names (:name monitor))
                    iterated-clock-maps
                    (conj iterated-clock-maps
                          {:name
                           (:name monitor)
                           :clock
                           0
                           :frequency
                           (:frequency monitor)
                           :url-range-clock
                           0
                           :url-range-frequency
                           (:url-range-frequency monitor)})))
                time-updated-clock-maps monitors)
        ready-monitor-clock-data
        (map (fn [clock-map]
               {:name (:name clock-map)
                :keep-url-range (zero? (:url-range-clock clock-map))})
             (filter #(zero? (:clock %)) monitor-updated-clock-maps))
        ready-monitors
        (keep (fn [monitor]
                (when-let [matching-clock-data
                           (first (filter #(= (:name monitor) (:name %))
                                          ready-monitor-clock-data))]
                  (if (:keep-url-range matching-clock-data)
                    monitor
                    (dissoc monitor :url-range))))
              monitors)
        updated-clock-maps
        (map #(let [clock
                    (if (= (:clock %) 0)
                      (:frequency %)
                      (:clock %))
                    range-clock
                    (if (= (:url-range-clock %) 0)
                      (:url-range-frequency %)
                      (:url-range-clock %))]
                (assoc % :clock clock :url-range-clock range-clock))
             monitor-updated-clock-maps)]
    [updated-clock-maps ready-monitors min-clock]))

(defn get-config []
  (let [config-path (sf/get-file-path "/config.json")]
    (with-open [reader (io/reader config-path)]
      (json/read reader :key-fn keyword))))

(defn- loop-monitoring-process [previous-clock-maps]
  (let [config
        (get-config)
        config-settings
        (:settings config)
        config-account-details
        (:account-details config)
        config-monitors
        (pi/format-config-monitors (:monitors config) config-settings)
        active-config-monitors
        (filter #(:active %) config-monitors)
        [updated-clock-maps ready-monitors
         wait-time]
        (time-adjust-config-monitors
         previous-clock-maps active-config-monitors)]
    (Thread/sleep wait-time)
    (adl/update-db-monitors config-monitors)
    (mm/organize-monitoring ready-monitors config-account-details)
    (recur updated-clock-maps)))

(defn -main []
  (adl/create-db)
  (rf/start-rss-server (:settings (get-config)))
  (loop-monitoring-process {}))
