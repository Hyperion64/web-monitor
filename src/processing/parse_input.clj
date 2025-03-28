(ns processing.parse-input
  (:require [clojure.string :as str]))

(defn- missing-setting-updates [monitor config-settings]
  (let [settings
        (seq {:active                         true
              :browser                        "none"
              :js-load-time-seconds           2
              :frequency                      "15m"
              :url-range-frequency            15
              :messengers                     #{"rss" "print"}
              :report-first-found             #{"rss"}
              :notify-if-element-removed      #{}
              :notify-if-element-rediscovered #{}
              :url                            []
              :url-range                      []
              :text-css-selectors             []
              :href-css-selectors             []})
        apply-settings
        (fn [reduced-monitor [setting-key setting-value]]
          (if (nil? (setting-key reduced-monitor))
            (if (nil? (setting-key config-settings))
              (assoc reduced-monitor setting-key setting-value)
              (assoc reduced-monitor setting-key (setting-key config-settings)))
            reduced-monitor))]
    (reduce apply-settings monitor settings)))

(def ^:private notification-selector-parameters
  [:report-first-found
   :notify-if-element-removed
   :notify-if-element-rediscovered])

(defn- bool-to-coll-updates [monitor]
  (reduce (fn [reduced-monitor param]
            (if (boolean? (param reduced-monitor))
              (assoc reduced-monitor param (:messengers monitor))
              reduced-monitor))
          monitor
          notification-selector-parameters))

(defn- string-or-map-to-vector-updates [monitor]
  (reduce (fn [reduced-monitor param]
            (if (or (string? (param reduced-monitor))
                    (map? (param reduced-monitor)))
              (assoc reduced-monitor param [(param reduced-monitor)])
              reduced-monitor))
          monitor
          [:url :text-css-selectors :href-css-selectors]))

(defn- string-vector-to-set-updates [monitor]
  (reduce (fn [reduced-monitor param]
            (cond
              (not (coll? (param reduced-monitor)))
              (assoc reduced-monitor param #{(param reduced-monitor)})
              (vector? (param reduced-monitor))
              (assoc reduced-monitor param (set (param reduced-monitor)))
              :else
              reduced-monitor))
          monitor
          [:messengers
           :report-first-found
           :notify-if-element-removed
           :notify-if-element-rediscovered]))

(defn- report-first-rss-updates [monitor]
  (if (contains? (:messengers monitor) "rss")
    (let [report-first-found (:report-first-found monitor)]
      (if-not (contains? report-first-found "rss")
        (assoc monitor :report-first-found (conj report-first-found "rss"))
        monitor))
    monitor))

(defn- remove-irrelevant-details-updates [monitor]
  (reduce
   (fn [reduced-monitor param]
     (assoc reduced-monitor param
            (set (remove nil?
                         (for [notification-selector-value (param monitor)]
                           (when (contains? (:messengers monitor)
                                            notification-selector-value)
                             notification-selector-value))))))
   monitor
   notification-selector-parameters))

(defn- add-infinite-url-range-limit [monitor]
  (let [url-range (:url-range monitor)
        infinity  Double/POSITIVE_INFINITY]
    (if-not (nil? url-range)
      (cond
        (= (count url-range) 2)
        (assoc monitor :url-range (conj url-range 1 5 infinity))
        (= (count url-range) 3)
        (assoc monitor :url-range (conj url-range 5 infinity))
        (= (count url-range) 4)
        (assoc monitor :url-range (conj url-range infinity))
        :else
        monitor)
      monitor)))

(defn- convert-frequency-string-to-ms [monitor]
  (let [time-vector
        (str/split (:frequency monitor) #"\s+")
        time-unit-map
        {:ms 1
         :s  1000
         :m  (* 1000 60)
         :h  (* 1000 60 60)
         :d  (* 1000 60 60 24)}
        sum-time-string
        (fn [sum time-string]
          (let [[_ amount time-unit]
                (re-matches #"(\d+)([a-zA-Z]+)" time-string)]
            (+ sum
               (* (Integer/parseInt amount)
                  ((keyword time-unit) time-unit-map)))))
        time-sum
        (reduce sum-time-string 0 time-vector)]
    (assoc monitor :frequency time-sum)))

(defn format-config-monitors [monitors config-settings]
  (map #(-> %
            (missing-setting-updates config-settings)
            bool-to-coll-updates
            string-or-map-to-vector-updates
            string-vector-to-set-updates
            report-first-rss-updates
            remove-irrelevant-details-updates
            add-infinite-url-range-limit
            convert-frequency-string-to-ms)
       monitors))
