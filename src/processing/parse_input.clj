(ns processing.parse-input
  (:require [utils.shared-functions :as sf]
            [clojure.string :as str]))

(defn- missing-setting-updates [monitor config-settings]
  (let [settings
        (seq {:active                         true
              :browser                        "none"
              :js-load-time-seconds           2
              :frequency                      "15m"
              :url-range-frequency            15
              :continuous                     false
              :messengers                     #{"rss" "print"}
              :report-first-found             #{"rss"}
              :notify-if-element-removed      #{}
              :notify-if-element-rediscovered #{}
              :url                            []
              :url-range                      []
              :filters                        []
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

(defn- add-types-to-filters [monitor]
  (assoc monitor
         :filters
         (map (fn [filter-map]
                (cond
                  (contains? filter-map :text-css-selector)
                  (assoc filter-map :type "select")
                  (contains? filter-map :href-filters)
                  (assoc filter-map
                         :type
                         "href"
                         :href-filters
                         (add-types-to-filters
                          {:filters (:href-filters filter-map)}))
                  (contains? filter-map :script-name)
                  (assoc filter-map :type "custom")
                  :else
                  (assoc filter-map :type "general")))
              (:filters monitor))))

(defn- add-infinite-url-range-limit [monitor]
  (if-not (empty? (:url-range monitor))
    (let [url-range
          (:url-range monitor)
          infinity
          Double/POSITIVE_INFINITY
          url-range-tolerance
          (if-not (:tolerance url-range)
            (assoc url-range :tolerance 0)
            url-range)
          url-range-tolerance-max-scrapes
          (if (or (not (:max-scrapes url-range-tolerance))
                  (= (:max-scrapes url-range-tolerance) 0))
            (assoc url-range-tolerance :max-scrapes infinity)
            url-range-tolerance)]
      (assoc monitor :url-range url-range-tolerance-max-scrapes))
    monitor))

(defn- include-file-urls [monitor]
  (if-not (nil? (:url-file monitor))
    (let [file-path (sf/get-file-path
                     (str "resources/url_files/" (:url-file monitor)))
          urls (sf/read-in-file file-path line-seq)]
      (update monitor :url concat urls))
    monitor))

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
            add-types-to-filters
            add-infinite-url-range-limit
            include-file-urls
            convert-frequency-string-to-ms)
       monitors))
