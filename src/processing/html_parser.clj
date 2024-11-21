(ns processing.html-parser
  (:require [web.scraper :as s]
            [utils.shared-functions :as sf]
            [clojure.string :as str]
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
          (get-formatted-parsed-element [selector-key html-content]
            {:content (if-not (empty? (selector-key monitor))
                        (flatten
                         (map #(first (parse-html-content % html-content))
                              (selector-key monitor)))
                        [html-content])})
          (make-content [html-content content-type]
            (let [[separation-string parsed-html-content filter-function
                   extraction-function href-url-completer]
                  (case content-type
                    "text"
                    [" "
                     (get-formatted-parsed-element
                      :text-css-selectors html-content)
                     string?
                     str/trim
                     identity]
                    "hrefs"
                    [" \n"
                     (get-formatted-parsed-element
                      :href-css-selectors html-content)
                     #(not (nil? (:href (:attrs %))))
                     #(:href (:attrs %))
                     #(cond
                        (str/starts-with? % "/")
                        (let [split-url (str/split (:url monitor) #"/")]
                          (str (nth split-url 0) "//" (nth split-url 2) %))
                        (str/starts-with? % "./")
                        (str (:url monitor) (subs % 1))
                        (or (str/starts-with? % "#")
                            (str/starts-with? % "javascript"))
                        ""
                        :else
                        %)])]
              (if-not (nil? parsed-html-content)
                (str/join
                 separation-string
                 (map href-url-completer
                      (flatten (extract-content parsed-html-content
                                                filter-function
                                                extraction-function))))
                "")))
          (make-content-map [html-content]
            {:text (make-content html-content  "text")
             :hrefs (make-content html-content "hrefs")})]
    (map make-content-map html-contents)))

(defn- clean-and-validate-number [s number-format]
  (let [trimmed (str/trim s)
        pattern (case number-format
                  "eng" #"^\D*(-?\d{1,3}(,\d{3})*(\.\d*)?).*\D*$"
                  "eur" #"^\D*(-?\d{1,3}(\.\d{3})*(,\d*)?).*\D*$")
        matched (re-find pattern trimmed)]
    (if (and matched (= (count (re-seq pattern trimmed)) 1))
      (let [cleaned-number (case number-format
                             "eng"
                             (-> (second matched)
                                 (str/replace #"," ""))
                             "eur"
                             (-> (second matched)
                                 (str/replace #"\." "")
                                 (str/replace #"," ".")))]
        (try
          (Double/parseDouble cleaned-number)
          (catch NumberFormatException _
            nil)))
      nil)))

(defn- make-filter-function [filter-map]
  (let [compare-nums
        (fn [operator web-string filter-value number-format]
          (let [web-string-number
                (clean-and-validate-number web-string number-format)]
            (when (not (nil? web-string-number))
              (operator web-string-number filter-value)
              false)))
        operator-string
        (:operator filter-map)
        filter-value
        (:filter-value filter-map)
        number-format
        (if (nil? (:number-format filter-map))
          "none"
          (:number-format filter-map))
        operator
        (get
         {"contains"  str/includes?
          "!contains" (complement str/includes?)
          "="         =
          "!="        not=
          "<="        <=
          ">="        >=
          "<"         <
          ">"         >}
         operator-string)]
    (if (= number-format "none")
      (cond
        (contains? #{"contains" "!contains"} operator-string)
        (fn [web-string] (operator web-string filter-value))
        (number? filter-value)
        (fn [web-string] (operator (count web-string) filter-value))
        :else
        (fn [web-string] (operator (count web-string)
                                   (count filter-value))))
      (fn [web-string] (compare-nums operator web-string filter-value
                                     number-format)))))

(defn- perform-filtering [html-contents monitor filter-type]
  (if (nil? (:filters monitor))
    html-contents
    (let [all-filters
          (:filters monitor)
          [selected-filters get-json-filter get-text]
          (case filter-type
            "general"
            [(filter
              #(not (or (contains? % :text-css-selector)
                        (contains? % :href-css-selector)))
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
            (let [apply-filter
                  (fn [selected-filter]
                    ((make-filter-function
                      (get-json-filter selected-filter))
                     (get-text html-content selected-filter)))]
              (every? apply-filter selected-filters)))]
      (filter apply-all-filters html-contents))))

(defn perform-monitoring [monitor]
  (let [html-string
        (s/fetch-html-string monitor)
        html-contents
        (hickory/as-hickory (hickory/parse html-string))
        parsed-html-contents
        (parse-html-content (:css-selector monitor) html-contents)
        parsed-inner-html-contents
        (if-not (nil? (:inner-css-selector monitor))
          (flatten (map #(parse-html-content (:inner-css-selector monitor) %)
                        html-contents))
          parsed-html-contents)
        filtered-pre-extracted-contents
        (perform-filtering parsed-inner-html-contents monitor "selected")
        extracted-contents
        (perform-extraction filtered-pre-extracted-contents monitor)
        filtered-extracted-contents
        (perform-filtering extracted-contents monitor "general")]
    (distinct filtered-extracted-contents)))
