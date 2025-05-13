(ns web-monitor.core
  (:gen-class)
  (:require [monitor-management.run-monitors         :as rm]
            [monitor-management.schedule-monitors    :as sm]
            [monitor-management.async-monitor-states :as ams]
            [fs.access-files                         :as af]
            [db.access-db-logic                      :as adl]
            [notifications.rss-feed                  :as rf]))

(defn- loop-monitoring-process [previous-clock-maps]
  (let [config
        (af/get-config)
        config-monitors
        (:monitors config)
        settings
        (:settings config)
        account-details
        (:account-details config)
        {:keys [monitors
                timing]}
        (sm/prepare-regular-monitor-schedule
         config-monitors settings account-details previous-clock-maps)
        {:keys [config-monitors
                continuous-monitors
                ready-regular-monitors
                regular-monitors]}
        monitors
        {:keys [updated-clock-maps
                wait-time]}
        timing
        {:keys [continuous-monitors-to-start
                continuous-monitors-to-stop
                continuous-monitors-to-restart]}
        (sm/prepare-continuous-monitor-schedule continuous-monitors)]
    (adl/update-db-monitors config-monitors)
    (ams/initialize-monitors continuous-monitors-to-start
                             continuous-monitors-to-stop
                             continuous-monitors-to-restart)
    (rm/init-organize-continuous-monitoring
     continuous-monitors-to-start account-details)
    (Thread/sleep wait-time)
    (rm/init-organize-regular-monitoring
     ready-regular-monitors account-details)
    (when-not regular-monitors
      (Thread/sleep
       (sm/find-minimum-continuous-monitor-frequency continuous-monitors)))
    (recur updated-clock-maps)))

(defn -main []
  (adl/create-db)
  (rf/start-rss-server (:settings (af/get-config)))
  (loop-monitoring-process {}))
