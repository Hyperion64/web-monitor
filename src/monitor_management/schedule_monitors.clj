(ns monitor-management.schedule-monitors
  (:require [db.access-db-logic                      :as adl]
            [monitor-management.parse-monitors       :as pm]
            [monitor-management.async-monitor-states :as ams]
            [clojure.set :as set]))

(defn- get-names [name-map]
  (set (map #(:name %) name-map)))

(def ^:private ten-minutes-ms (* 1000 60 10))

(defn- apply-time-advance-update [clock-maps monitors]
  (let [monitor-names
        (get-names monitors)
        clock-maps-without-inactive-clocks
        (filter #(contains? monitor-names (:name %)) clock-maps)
        min-clock
        (if (seq clock-maps-without-inactive-clocks)
          (apply min (map #(:clock     %) clock-maps-without-inactive-clocks))
          (apply min (map #(:frequency %) monitors)))
        time-advance
        (if (> min-clock ten-minutes-ms)
          ten-minutes-ms
          min-clock)
        clock-maps-time-advance-update
        (map #(update (update % :clock - time-advance) :url-range-clock dec)
             clock-maps-without-inactive-clocks)]
    [time-advance clock-maps-time-advance-update]))

(defn- apply-new-monitor-update [clock-maps-time-advance-update monitors]
  (reduce (fn [iterated-clock-maps monitor]
            (if (contains? (get-names clock-maps-time-advance-update)
                           (:name monitor))
              iterated-clock-maps
              (conj
               iterated-clock-maps
               {:name                (:name monitor)
                :clock               0
                :frequency           (:frequency monitor)
                :url-range-clock     0
                :url-range-frequency (:url-range-frequency monitor)})))
          clock-maps-time-advance-update monitors))

(defn- get-ready-monitors [clock-maps-new-monitor-update monitors]
  (let [ready-monitor-clock-data
        (map (fn [clock-map]
               {:name (:name clock-map)
                :keep-url-range (zero? (:url-range-clock clock-map))})
             (filter #(zero? (:clock %)) clock-maps-new-monitor-update))]
    (keep (fn [monitor]
            (when-let [matching-clock-data
                       (some #(when (= (:name monitor) (:name %)) %)
                             ready-monitor-clock-data)]
              (if (:keep-url-range matching-clock-data)
                monitor
                (dissoc monitor :url-range))))
          monitors)))

(defn- apply-countdown-reset-update [clock-maps-new-monitor-update]
  (map #(let [clock
              (if (= (:clock %) 0)
                (:frequency %)
                (:clock %))
              range-clock
              (if (= (:url-range-clock %) 0)
                (:url-range-frequency %)
                (:url-range-clock %))]
          (assoc % :clock clock :url-range-clock range-clock))
       clock-maps-new-monitor-update))

(defn- time-adjust-config-monitors [clock-maps monitors]
  (let [[time-advance clock-maps-time-advance-update]
        (apply-time-advance-update clock-maps monitors)
        clock-maps-new-monitor-update
        (apply-new-monitor-update clock-maps-time-advance-update monitors)
        clock-maps-countdown-reset-update
        (apply-countdown-reset-update clock-maps-new-monitor-update)
        ready-monitors
        (get-ready-monitors clock-maps-new-monitor-update monitors)]
    [clock-maps-countdown-reset-update ready-monitors time-advance]))

(defn- get-active-config-monitors [config]
  (let [{:keys [monitors settings]}
        config
        config-monitors
        (pm/format-config-monitors monitors settings)
        active-config-monitors
        (filter #(:active %) config-monitors)]
    [active-config-monitors config-monitors]))

(defn get-monitor-once-monitors [config]
  (first (get-active-config-monitors config)))

(defn- prepare-regular-monitor-schedule [config previous-clock-maps]
  (let [[active-config-monitors config-monitors]
        (get-active-config-monitors config)
        {continuous-monitors true, regular-monitors false}
        (group-by #(:continuous %) active-config-monitors)
        [updated-clock-maps ready-regular-monitors wait-time]
        (if (seq regular-monitors)
          (time-adjust-config-monitors previous-clock-maps regular-monitors)
          [[] [] nil])]
    {:wait-time              wait-time
     :updated-clock-maps     updated-clock-maps
     :parsed-config-monitors config-monitors
     :regular-monitors       regular-monitors
     :ready-regular-monitors ready-regular-monitors
     :continuous-monitors    continuous-monitors}))

(defn- prepare-continuous-monitor-schedule [continuous-monitors]
  (let [[monitors-to-start monitors-to-stop]
        (ams/manage-continuous-active-monitors continuous-monitors)
        running-continuous-monitors
        (vec (set/difference (set continuous-monitors) (set monitors-to-start)))
        monitors-to-restart
        (adl/find-modified-monitors running-continuous-monitors)]
    {:continuous-monitors-to-start   monitors-to-start
     :continuous-monitors-to-stop    monitors-to-stop
     :continuous-monitors-to-restart monitors-to-restart}))

(defn prepare-monitor-schedule [config previous-clock-maps]
  (let [{:keys [account-details regular-monitors-asynchronous]}
        config
        {:keys [wait-time
                updated-clock-maps
                parsed-config-monitors
                continuous-monitors
                ready-regular-monitors
                regular-monitors]}
        (prepare-regular-monitor-schedule config previous-clock-maps)
        {:keys [continuous-monitors-to-start
                continuous-monitors-to-stop
                continuous-monitors-to-restart]}
        (prepare-continuous-monitor-schedule continuous-monitors)]
    {:account-details                account-details
     :regular-monitors-asynchronous  regular-monitors-asynchronous
     :wait-time                      wait-time
     :updated-clock-maps             updated-clock-maps
     :parsed-config-monitors         parsed-config-monitors
     :regular-monitors               regular-monitors
     :ready-regular-monitors         ready-regular-monitors
     :continuous-monitors            continuous-monitors
     :continuous-monitors-to-start   continuous-monitors-to-start
     :continuous-monitors-to-stop    continuous-monitors-to-stop
     :continuous-monitors-to-restart continuous-monitors-to-restart}))

(defn pause-core-loop [wait-time regular-monitors continuous-monitors]
  (Thread/sleep
   (letfn [(no-regular-monitor-wait-time [continuous-monitors]
             (let [frequencies
                   (map #(:frequency %) continuous-monitors)
                   minimum-frequency
                   (- (apply min frequencies) 1000)]
               (if (<= minimum-frequency ten-minutes-ms)
                 minimum-frequency
                 ten-minutes-ms)))]
     (cond regular-monitors
           wait-time
           continuous-monitors
           (no-regular-monitor-wait-time continuous-monitors)
           :else
           ten-minutes-ms))))
