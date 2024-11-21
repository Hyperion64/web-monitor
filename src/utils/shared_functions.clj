(ns utils.shared-functions
  (:require [clojure.string :as str]
            [java-time.api :as jt])
  (:import  [java.nio.file Paths]))

(defn make-datetime []
  (jt/format (jt/formatter "dd.MM.yyyy HH:mm:ss") (jt/zoned-date-time)))

(defn ordered-complement [coll-1 coll-2]
  (filter (complement (set coll-2)) coll-1))

(defn ordered-conjunction [coll-1 coll-2]
  (filter (set coll-2) coll-1))

(defn map-maybe-collection [f maybe-map]
  (if (coll? maybe-map)
    (map f maybe-map)
    [(f maybe-map)]))

(defn- get-relative-dir-root-path []
  (let [classpath-elements
        (str/split (System/getProperty "java.class.path") #":")
        get-base-path
        (fn [full-directory]
          (str (subs full-directory 0
                     (str/last-index-of full-directory "/")) "/"))
        get-jar-path
        (fn [& [ns]]
          (-> (or ns (class *ns*))
              .getProtectionDomain
              .getCodeSource
              .getLocation
              .toURI
              .getPath))]
    (get-base-path
     (if (= (count classpath-elements) 1)
       (get-jar-path)
       (first classpath-elements)))))

#_(defn- get-home-dir-root-path []
    (let [home-dir (System/getProperty "user.home")
          web-monitor-home-config-dir (str home-dir "/.config/web-monitor/")]
      (Paths/get web-monitor-home-config-dir)))

(defonce ^:private root-path (get-relative-dir-root-path))

(defn get-file-path [path-end]
  (str root-path path-end))
