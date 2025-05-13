(ns monitor-management.schedule-monitors
  (:require [db.access-db-logic                      :as adl]
            [monitor-management.parse-monitors       :as pm]
            [monitor-management.async-monitor-states :as ams]
            [clojure.set :as set]))

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

(defn prepare-regular-monitor-schedule
  [monitors settings account-details previous-clock-maps]
  (let [config-monitors
        (pm/format-config-monitors monitors settings)
        active-config-monitors
        (filter #(:active %) config-monitors)
        {continuous-monitors true, regular-monitors false}
        (group-by #(:continuous %) active-config-monitors)
        [updated-clock-maps ready-regular-monitors wait-time]
        (time-adjust-config-monitors
         previous-clock-maps (or regular-monitors []))]
    {:monitors
     {:config-monitors        config-monitors
      :ready-regular-monitors ready-regular-monitors
      :regular-monitors       regular-monitors
      :continuous-monitors    continuous-monitors}
     :timing
     {:updated-clock-maps updated-clock-maps
      :wait-time          wait-time}}))

(defn prepare-continuous-monitor-schedule [continuous-monitors]
  (let [[monitors-to-start monitors-to-stop]
        (ams/manage-active-monitors continuous-monitors)
        running-continuous-monitors
        (vec (set/difference (set continuous-monitors)
                             (set monitors-to-start)))
        monitors-to-restart
        (adl/find-modified-monitors running-continuous-monitors)]
    {:continuous-monitors-to-start   monitors-to-start
     :continuous-monitors-to-stop    monitors-to-stop
     :continuous-monitors-to-restart monitors-to-restart}))

(defn find-minimum-continuous-monitor-frequency [continuous-monitors]
  (let [frequencies (map #(:frequency %) continuous-monitors)
        minimum-frequency (- (apply min frequencies) 1000)]
    (if (>= minimum-frequency (* 1000 60))
      minimum-frequency
      (* 1000 60))))
