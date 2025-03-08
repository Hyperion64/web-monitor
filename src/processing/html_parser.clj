(ns processing.html-parser
  (:require [web.scraper :as s]
            [utils.shared-functions :as sf]
            [clojure.string :as str]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [hickory.core :as hickory]
            [hickory.select :as sel]))

(defn- parse-html-content [css-selector html-content]
  (if-not (or (nil? css-selector) (empty? css-selector))
    (let [hickory-select-functions
          (flatten (for [[k f]
                         {:tag     sel/tag
                          :classes #(sf/map-maybe-collection sel/class %)
                          :ids     #(sf/map-maybe-collection sel/id %)
                          :type    sel/node-type}
                         :let  [selector (k css-selector)]
                         :when (some? selector)]
                     (f selector)))]
      (sel/select (apply sel/and hickory-select-functions)
                  html-content))
    html-content))

(defn- perform-extraction [html-contents monitor]
  (letfn [(extract-content
            [parsed-html-content filter-function extraction-function]
            (for [html-element (:content parsed-html-content)]
              (if-not (nil? html-element)
                (if (filter-function html-element)
                  (extraction-function html-element)
                  (extract-content
                   html-element filter-function extraction-function))
                "")))
          (manage-extract-content
            [html-content selector-key filter-function extraction-function]
            (let [parsed-html-content
                  {:content (if-not (empty? (selector-key monitor))
                              (flatten
                               (map #(first (parse-html-content % html-content))
                                    (selector-key monitor)))
                              [html-content])}]
              (flatten (extract-content parsed-html-content
                                        filter-function
                                        extraction-function))))
          (make-text-content [html-content]
            (str/join " " (manage-extract-content
                           html-content :text-css-selectors string? str/trim)))
          (make-href-content [html-content]
            (letfn [(extraction-function [iterated-html-map]
                      (:href (:attrs iterated-html-map)))
                    (filter-function [iterated-html-map]
                      (not (nil? (:href (:attrs iterated-html-map)))))
                    (format-href-urls [extracted-content]
                      (cond
                        (str/starts-with? extracted-content "/")
                        (let [split-url (str/split (:url monitor) #"/")]
                          (str (nth split-url 0) "//" (nth split-url 2)
                               extracted-content))
                        (str/starts-with? extracted-content "./")
                        (str (:url monitor) (subs extracted-content 1))
                        (or (str/starts-with? extracted-content "#")
                            (str/starts-with? extracted-content "javascript"))
                        nil
                        :else
                        extracted-content))
                    (remove-multi-newlines [extracted-content]
                      (str/replace extracted-content #"\n+" "\n"))]
              (remove-multi-newlines
               (str/join "\n" (keep format-href-urls
                                    (manage-extract-content
                                     html-content
                                     :href-css-selectors
                                     filter-function
                                     extraction-function))))))
          (make-content-map [html-content]
            {:text (make-text-content html-content)
             :hrefs (make-href-content html-content)})]
    (map make-content-map html-contents)))

(defn- extract-number [web-string value-format]
  (let [regexped-filter-value
        (fn [] (first (re-seq #"^ds.ts.$" value-format)))
        [ds ts]
        (if (nil? value-format)
          ["." ","]
          [(str (nth (regexped-filter-value) 2))
           (str (nth (regexped-filter-value) 5))])
        pattern
        (re-pattern
         (format "-?\\d{1,3}((%s\\d{3})|\\d)*(\\%s\\d*)?" ts ds))
        extracted-number
        (first (re-find pattern web-string))]
    (Double/parseDouble
     (str/replace (if-not (= ts "")
                    (str/replace extracted-number ts "")
                    extracted-number)
                  ds "."))))

(defn- extract-date [web-string value-format]
  (let [date-format-regexp-map
        {:G    "(BC)|(BCE)|(AD)|(CE)"
         :C    "[0-9]{2}"
         :Y    "[0-9]"
         :YY   "[0-9]{2}"
         :YYYY "[0-9]{4}"
         :x    "-?[0-9]"
         :xx   "-?[0-9]{2}"
         :xxxx "-?[0-9]{4}"
         :w    "([1-4][0-9])|([0-9])|(5[0-3])"
         :ww   "([0-4][0-9])|(5[0-3])"
         :e    "[1-7]"
         :E    "(?i)MON|TUE|WED|THU|FRI|SAT|SUN"
         :EE   "(?i)MO|TU|WE|TH|FR|SA|SU"
         :EEEE (str "(?i)MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|"
                    "SATURDAY|SUNDAY")
         :y    "-?[0-9]"
         :yy   "-?[0-9]{2}"
         :yyyy "-?[0-9]{4}"
         :D    (str "([1-2][0-9][0-9])|(3[0-5][0-9])|(36[0-5])|"
                    "([0-9][0-9])|([0-9])")
         :DDD  "([0-2][0-9][0-9])|(3[0-5][0-9])|(36[0-5])"
         :M    "([1-9])|(1[0-2])"
         :MM   "(0[1-9])|(1[0-2])"
         :MMM  "(?i)JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC"
         :MMMM (str "(?i)JANUARY|FEBRUARY|MARCH|APRIL|MAY|JUNE|JULY|"
                    "AUGUST|SEPTEMBER|OCTOBER|NOVEMBER|DECEMBER")
         :d    "([1-9])|([1-2][0-9])|(3[0-1])"
         :dd   "([0-2][0-9])|(3[0-1])"
         :a    "(PM)|(AM)"
         :K    "([0-9])|(1[0-1])"
         :KK   "(0[0-9])|(1[0-1])"
         :h    "([1-9])|(1[0-2])"
         :hh   "(0[1-9])|(1[0-2])"
         :H    "([0-9])|(1[0-9])|(2[0-3])"
         :HH   "(0[0-9])|(1[0-9])|(2[0-3])"
         :k    "([1-9])|(1[0-9])|(2[0-4])"
         :kk   "(0[1-9])|(1[0-9])|(2[0-4])"
         :m    "([0-9])|([1-5][0-9])"
         :mm   "[0-5][0-9]"
         :s    "([0-9])|([1-5][0-9])"
         :ss   "[0-5][0-9]"
         :S    "([0-9])|([1-9][0-9])|([1-9][0-9]{2})"
         :SS   "([0-9]{2})|([1-9][0-9]{2})"
         :SSS  "[0-9]{3}"}
        regexp-date-format-parts
        (map #(subs % 0 (dec (count %)))
             (map #(apply str %) (re-seq #"(.)\1*" value-format)))
        date-regexp-string
        (str/join
         (map #(str "(" (or ((keyword %) date-format-regexp-map) %) ")")
              regexp-date-format-parts))
        maybe-extracted-date
        (re-find (re-pattern date-regexp-string) web-string)]
    (if-not (nil? maybe-extracted-date)
      (first maybe-extracted-date)
      nil)))

(defn- make-filter-function [filter-map]
  (let [operator-string
        (:operator filter-map)
        filter-value
        (:filter-value filter-map)
        value-format
        (:format  filter-map)
        value-format-type
        (cond
          (nil? value-format)
          (if (number? filter-value)
            "number"
            "string")
          (boolean (re-matches #"^ds.ts.$" value-format))
          "number"
          (try (f/formatter value-format) (catch Exception _ false))
          "date"
          :else
          "string")
        operator
        (get
         (if (= value-format-type "date")
           {"="         t/equal?
            "!="        (fn [d1 d2] (not (t/equal? d1 d2)))
            "<="        (fn [d1 d2] (or (t/before? d1 d2) (t/equal? d1 d2)))
            ">="        (fn [d1 d2] (or (t/after?  d1 d2) (t/equal? d1 d2)))
            "<"         t/before?
            ">"         t/after?}
           {"contains"  str/includes?
            "!contains" (complement str/includes?)
            "="         =
            "!="        not=
            "<="        <=
            ">="        >=
            "<"         <
            ">"         >})
         operator-string)

        select-filter-function
        (fn [web-string]
          (case value-format-type
            "string"
            (cond
              (contains? #{"contains" "!contains"} operator-string)
              (operator web-string filter-value)
              (number? filter-value)
              (operator (count web-string) filter-value)
              :else
              (operator (count web-string)
                        (count filter-value)))
            "number"
            (let [extracted-number (extract-number web-string value-format)]
              (if-not (nil? extracted-number)
                (operator extracted-number filter-value)
                false))
            "date"
            (let [formatter (f/formatter value-format)
                  extracted-date (extract-date web-string value-format)]
              (if-not (nil? extracted-date)
                (operator (f/parse formatter extracted-date)
                          (f/parse formatter filter-value))
                false))))]
    (fn [web-string]
      (if-not (or (= web-string "")
                  (nil? web-string))
        (select-filter-function web-string)
        false))))

(defn- perform-filtering-core [html-contents selected-filters apply-filter]
  (letfn [(apply-all-filters [html-content]
            (every? #(apply-filter html-content %) selected-filters))]
    (filter apply-all-filters html-contents)))

(defn- perform-general-filtering [html-contents monitor]
  (let [selected-filters
        (filter
         #(not (or (contains? % :text-css-selector)
                   (contains? % :href-filters)))
         (:filters monitor))
        apply-filter
        (fn [html-content selected-filter]
          ((make-filter-function selected-filter)
           (:text html-content)))]
    (perform-filtering-core html-contents selected-filters apply-filter)))

(defn- perform-selected-filtering [html-contents monitor]
  (let [css-selector-filters
        (filter #(contains? % :text-css-selector) (:filters monitor))
        selected-filters
        (sf/map-maybe-collection
         (fn [css-selector-filter]
           {:function
            (select-keys css-selector-filter [:operator :filter-value])
            :text-css-selector
            (:text-css-selector css-selector-filter)})
         css-selector-filters)
        get-text
        (fn [html-content selected-filter]
          (:text (first (perform-extraction
                         (parse-html-content
                          (:text-css-selector selected-filter)
                          html-content)
                         (assoc monitor
                                :text-css-selectors []
                                :href-css-selectors [])))))
        apply-filter
        (fn [html-content selected-filter]
          ((make-filter-function (:function selected-filter))
           (get-text html-content selected-filter)))]
    (perform-filtering-core html-contents selected-filters apply-filter)))

#_(defn- perform-filtering [html-contents monitor filter-type]
    (if (nil? (:filters monitor))
      html-contents
      (let [all-filters
            (:filters monitor)
            [selected-filters get-json-filter get-text]
            (case filter-type
              "general"
              [(filter
                #(not (or (contains? % :text-css-selector)
                          (contains? % :href-filters)))
                all-filters)
               identity
               (fn [html-content _]
                 (:text html-content))]
              "selected"
              [(let [css-selector-filters
                     (filter #(contains? % :text-css-selector) all-filters)]
                 (sf/map-maybe-collection
                  (fn [css-selector-filter]
                    {:function
                     (select-keys css-selector-filter [:operator :filter-value])
                     :text-css-selector
                     (:text-css-selector css-selector-filter)})
                  css-selector-filters))
               (fn [selected-filter]
                 (:function selected-filter))
               (fn [html-content selected-filter]
                 (:text (first (perform-extraction
                                (parse-html-content
                                 (:text-css-selector selected-filter)
                                 html-content)
                                (assoc monitor
                                       :text-css-selectors []
                                       :href-css-selectors [])))))])
            apply-all-filters
            (fn [html-content]
              (letfn [(apply-filter [selected-filter]
                        ((make-filter-function
                          (get-json-filter selected-filter))
                         (get-text html-content selected-filter)))]
                (every? apply-filter selected-filters)))]
        (filter apply-all-filters html-contents))))

(defn- scrape-html-contents [monitor]
  (hickory/as-hickory (hickory/parse (s/fetch-html-string monitor))))

(defn- manage-parse-html-contents [html-contents monitor]
  (let [parsed-html-contents
        (parse-html-content (:css-selector monitor) html-contents)
        parsed-inner-html-contents
        (if-not (nil? (:inner-css-selector monitor))
          (flatten (map #(parse-html-content (:inner-css-selector monitor) %)
                        parsed-html-contents))
          parsed-html-contents)]
    parsed-inner-html-contents))

(defn perform-href-filtering [html-contents monitor]
  (letfn [(core-perform-href-filtering [href-filters href-html-content]
            (let [{text-filters false
                   inner-href-filters true}
                  (group-by #(contains? % :href-filters) href-filters)
                  parsed-text-contents
                  (map (fn [text-filter]
                         {:extracted-contents
                          (perform-extraction
                           [{:content (manage-parse-html-contents
                                       href-html-content text-filter)}]
                           {:text-css-selectors []
                            :href-css-selectors []})
                          :operator        (:operator text-filter)
                          :filter-value    (:filter-value text-filter)})
                       text-filters)
                  filtered-parsed-text-contents
                  (map #(:text (first
                                (perform-general-filtering
                                 (:extracted-contents %)
                                 {:filters [(dissoc % :extracted-contents)]})))
                       parsed-text-contents)
                  extract-href-content
                  (fn [css-selector]
                    (let [scrape-url
                          (:hrefs (first (perform-extraction
                                          [{:content
                                            (manage-parse-html-contents
                                             href-html-content css-selector)}]
                                          {:url                (:url monitor)
                                           :text-css-selectors []
                                           :href-css-selectors []})))
                          get-deeper-href-content
                          (fn []
                            (scrape-html-contents
                             (assoc monitor :url scrape-url)))]
                      (if-not (= scrape-url "")
                        (core-perform-href-filtering
                         (:href-filters css-selector)
                         (get-deeper-href-content))
                        false)))]
              (if (or (empty? text-filters)
                      (every? identity
                              (map #(not (nil? %))
                                   filtered-parsed-text-contents)))
                (or (nil? inner-href-filters)
                    (every? identity (map extract-href-content
                                          (or inner-href-filters []))))
                false)))]
    (filter
     (fn [html-content]
       (if (some #(contains? % :href-filters) (:filters monitor))
         (if (and (not (str/includes? (:hrefs html-content) "\n"))
                  (not (= (:hrefs html-content) "")))
           (let [scrape-url
                 (:hrefs html-content)
                 href-filters
                 (:href-filters
                  (first (filter #(contains? % :href-filters)
                                 (:filters monitor))))
                 href-html-content
                 (scrape-html-contents (assoc monitor :url scrape-url))]
             (core-perform-href-filtering href-filters href-html-content))
           false)
         true))
     html-contents)))

(defn perform-monitoring [monitor]
  (let [html-contents
        (scrape-html-contents monitor)
        parsed-html-contents
        (manage-parse-html-contents html-contents monitor)
        filtered-pre-extracted-contents
        (perform-selected-filtering parsed-html-contents monitor)
        extracted-contents
        (perform-extraction filtered-pre-extracted-contents monitor)
        filtered-extracted-contents
        (perform-general-filtering extracted-contents monitor)]
    (distinct filtered-extracted-contents)))
