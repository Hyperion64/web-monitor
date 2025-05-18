(ns web-monitor.core
  (:gen-class)
  (:require [monitor-management.run-monitors         :as rm]
            [monitor-management.schedule-monitors    :as sm]
            [monitor-management.async-monitor-states :as ams]
            [fs.access-files                         :as af]
            [db.access-db-logic                      :as adl]
            [notifications.rss-feed                  :as rf]))

(defn- loop-monitoring-process [previous-clock-maps]
  (let [{:keys [account-details
                wait-time
                updated-clock-maps
                parsed-config-monitors
                regular-monitors
                ready-regular-monitors
                continuous-monitors
                continuous-monitors-to-start
                continuous-monitors-to-stop
                continuous-monitors-to-restart]}
        (sm/prepare-monitor-schedule (af/get-config) previous-clock-maps)]
    (adl/update-db-monitors parsed-config-monitors)
    (ams/initialize-monitor-states continuous-monitors-to-start
                                   continuous-monitors-to-stop
                                   continuous-monitors-to-restart)
    (rm/initialize-monitors ready-regular-monitors
                            continuous-monitors-to-start
                            account-details)
    (sm/pause-core-loop wait-time
                        regular-monitors
                        continuous-monitors)
    (recur updated-clock-maps)))

(defn -main []
  (adl/create-db)
  (rf/start-rss-server (:settings (af/get-config)))
  (loop-monitoring-process {}))
