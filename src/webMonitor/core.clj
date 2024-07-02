(ns webMonitor.core
  (:require [applicationLayer.manage-monitors :as mm] 
            [applicationLayer.shared-functions :as sf] 
            [dataAccessLayer.access-monitor-data :as amd]
            [presentationLayer.rss-feed :as rf]))

(defn -main []
  (let [config-settings (:settings (sf/get-config))]
    (amd/create-db)
    (rf/start-rss-server config-settings)
    (mm/manage-monitoring)))
