(ns utils.shared-functions
  (:require [clojure.data.json :as json]
            [java-time.api :as jt]))

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

(defn get-maps-with-values [maps-to-filter filter-key filter-values]
  (filter #(contains? (set filter-values) (filter-key %)) maps-to-filter))

(defn make-json-string [web-elements]
  (json/write-str web-elements :escape-slash false))
