(ns web-monitor.core
  (:gen-class)
  (:require [cli.arg-parser                          :as ap]
            [monitor-management.run-monitors         :as rm]
            [monitor-management.schedule-monitors    :as sm]
            [monitor-management.async-monitor-states :as ams]
            [fs.access-files                         :as af]
            [db.access-db-logic                      :as adl]
            [notifications.rss-feed                  :as rf]))

(defn- run-monitoring-process-once [config]
  (let [active-config-monitors (sm/get-monitor-once-monitors config)
        {:keys [account-details regular-monitors-asynchronous]} config]
    (rm/initialize-monitors
     active-config-monitors
     []
     account-details
     regular-monitors-asynchronous
     "once")))

(defn- loop-monitoring-process [previous-clock-maps]
  (let [{:keys [account-details
                regular-monitors-asynchronous
                wait-time
                updated-clock-maps
                parsed-config-monitors
                regular-monitors
                ready-regular-monitors
                continuous-monitors
                continuous-monitors-to-start
                continuous-monitors-to-stop
                continuous-monitors-to-restart]}
        (sm/prepare-monitor-schedule
         (af/get-regular-config) previous-clock-maps)]
    (adl/update-db-monitors parsed-config-monitors)
    (ams/initialize-continuous-monitor-states continuous-monitors-to-start
                                              continuous-monitors-to-stop
                                              continuous-monitors-to-restart)
    (rm/initialize-monitors ready-regular-monitors
                            continuous-monitors-to-start
                            account-details
                            regular-monitors-asynchronous
                            "looping")
    (sm/pause-core-loop wait-time
                        regular-monitors
                        continuous-monitors)
    (recur updated-clock-maps)))

(defn- initiate-monitoring [args]
  (let [{:keys [mode content-type content]}
        (ap/parse-args args)]
    (case mode
      "looping"
      (loop-monitoring-process {})
      "once"
      (let [config (case content-type
                     "path"     (af/get-config-from-path content)
                     "json"     content
                     "standard" (af/get-regular-config))]
        (run-monitoring-process-once config))
      (println content))))

(defn- start-db-and-server [args]
  (when (empty? args)
    (adl/create-db)
    (rf/start-rss-server (:settings (af/get-regular-config)))))

(defn -main [& args]
  (start-db-and-server args)
  (initiate-monitoring args))
