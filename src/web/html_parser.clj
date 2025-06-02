(ns web.html-parser
  (:require [utils.shared-functions :as sf]
            [fs.access-files        :as af]
            [clojure.java.shell     :refer [sh]]
            [clojure.string         :as str]
            [clj-time.format        :as f]
            [clj-time.core          :as t]
            [hickory.core           :as hickory]
            [hickory.select         :as sel]))

(defn- parse-html-content [css-selector html-content]
  (let [hickory-select-functions
        (mapcat
         (fn [[k f]]
           (when-let [selector (k css-selector)]
             (let [result (f selector)]
               (if (#{:tag :type} k)
                 [result]
                 result))))
         {:tag     sel/tag
          :classes #(map sel/class %)
          :ids     #(map sel/id %)
          :type    sel/node-type})]
    (sel/select (apply sel/and hickory-select-functions) html-content)))

(defn- wrap-and-select-html-content [html-content monitor selector-key]
  (let [selectors (selector-key monitor)]
    {:content (if (seq selectors)
                (mapv #(first (parse-html-content % html-content)) selectors)
                [html-content])}))

(defn- text-extraction [html-content monitor]
  (letfn [(extract-content [parsed-html-content]
            (->> parsed-html-content
                 :content
                 (keep
                  (fn [html-element]
                    (let [inner-extracted-content
                          (if (string? html-element)
                            (str/trim html-element)
                            (extract-content html-element))]
                      (when-not (str/blank? inner-extracted-content)
                        (if (= (:tag html-element) :p)
                          (str "\n\n" inner-extracted-content)
                          inner-extracted-content)))))
                 (str/join " ")))
          (trim-newlines [extracted-content]
            (str/replace extracted-content #"^\n+|\n+$" ""))]
    (let [parsed-content (wrap-and-select-html-content
                          html-content monitor :text-css-selectors)
          extracted-content (extract-content parsed-content)]
      (trim-newlines extracted-content))))

(defn- href-extraction [html-content monitor]
  (letfn [(format-href-url [extracted-content]
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
          (extract-content [parsed-html-content]
            (->> parsed-html-content
                 :content
                 (keep
                  (fn [html-element]
                    (when html-element
                      (if (:href (:attrs html-element))
                        (:href (:attrs html-element))
                        (extract-content html-element)))))
                 (keep format-href-url)
                 (str/join "\n")))
          (remove-multi-newlines [extracted-content]
            (str/replace extracted-content #"\n+" "\n"))]
    (let [parsed-content (wrap-and-select-html-content
                          html-content monitor :href-css-selectors)
          extracted-content (extract-content parsed-content)]
      (remove-multi-newlines extracted-content))))

(defn initialize-extraction [html-contents monitor]
  (map (fn [html-content]
         {:text  (text-extraction html-content monitor)
          :hrefs (href-extraction  html-content monitor)})
       html-contents))

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

(defn- perform-filtering-core [html-contents selected-filters get-text]
  (letfn [(apply-all-filters [html-content]
            (every? #((:function %) (get-text html-content %))
                    selected-filters))]
    (filter apply-all-filters html-contents)))

(defn perform-general-filtering [html-contents filters]
  (let [selected-filter-data
        (filter #(= (:type %) "general") filters)
        selected-filter-functions
        (map (fn [filter-data-element]
               {:function (make-filter-function filter-data-element)})
             selected-filter-data)
        get-text
        (fn [html-content _]
          (:text html-content))]
    (perform-filtering-core html-contents selected-filter-functions get-text)))

(defn perform-selected-filtering [html-contents monitor]
  (let [css-selector-filters
        (filter #(= (:type %) "select") (:filters monitor))
        selected-filter-data
        (map (fn [css-selector-filter]
               {:function-params
                (select-keys css-selector-filter [:operator :filter-value])
                :text-css-selector
                (:text-css-selector css-selector-filter)})
             css-selector-filters)
        selected-filter-functions
        (map #(assoc % :function (make-filter-function (:function-params %)))
             selected-filter-data)
        get-text
        (fn [html-content selected-filter]
          (:text (first (initialize-extraction
                         (parse-html-content
                          (:text-css-selector selected-filter)
                          html-content)
                         (assoc monitor
                                :text-css-selectors []
                                :href-css-selectors [])))))]
    (perform-filtering-core
     html-contents selected-filter-functions get-text)))

(defn hickory-parse-html-contents [href-string]
  (hickory/as-hickory (hickory/parse href-string)))

(defn initialize-parse-html-contents [html-contents monitor]
  (let [parsed-html-contents
        (parse-html-content (:css-selector monitor) html-contents)
        parsed-inner-html-contents
        (if-not (nil? (:inner-css-selector monitor))
          (flatten (map #(parse-html-content (:inner-css-selector monitor) %)
                        parsed-html-contents))
          parsed-html-contents)]
    parsed-inner-html-contents))

(defn perform-custom-filtering [web-contents filters]
  (let [custom-filter-data
        (filter #(= (:type %) "custom") filters)
        filter-paths
        (map #(af/get-filter-script-path (:script-name %))
             custom-filter-data)
        parse-script-output
        (fn [script-output]
          (= (str/lower-case (str/trim (:out script-output))) "true"))
        filter-functions
        (map (fn [filter-path]
               {:function
                (fn [content-json-string]
                  (parse-script-output (sh filter-path content-json-string)))})
             filter-paths)
        get-text
        (fn [html-content _]
          (sf/make-json-string (:text html-content)))]
    (perform-filtering-core web-contents filter-functions get-text)))
