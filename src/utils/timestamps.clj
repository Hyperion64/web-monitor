(ns utils.timestamps
  (:require [java-time.api :as jt])
  (:import [java.time LocalDateTime ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

(defn timestamp-to-rss-format [date-str]
  (let [input-formatter
        (DateTimeFormatter/ofPattern "dd.MM.yyyy HH:mm:ss")
        rfc822-formatter
        (DateTimeFormatter/ofPattern
         "EEE, dd MMM yyyy HH:mm:ss Z" java.util.Locale/ENGLISH)
        local-dt
        (LocalDateTime/parse date-str input-formatter)
        zoned-dt
        (ZonedDateTime/of local-dt (ZoneId/systemDefault))]
    (.format zoned-dt rfc822-formatter)))

(defn- format-time [format-string]
  (jt/format (jt/formatter format-string) (jt/zoned-date-time)))

(defn make-datetime []
  (format-time "dd.MM.yyyy HH:mm:ss"))

(defn make-log-id-timestamp []
  (format-time "yyyyMMdd-HHmmss"))

(defn make-log-element-timestamp []
  (format-time "yyyy-MM-dd HH:mm:ss"))
