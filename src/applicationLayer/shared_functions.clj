(ns applicationLayer.shared-functions
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn get-project-path [path]
  (str (System/getProperty "user.dir") path))

(defn get-config []
  (let [config-path (get-project-path "/config.json")]
    (with-open [reader (io/reader config-path)]
      (json/read reader :key-fn keyword))))
