(ns applicationLayer.html-parser
  (:require [applicationLayer.selenium-scraper :as sc]
            [clj-http.client :as client]
            [clojure.string :as string]
            [hickory.core :as hickory]
            [hickory.select :as s]))

(defn- parse-html-content [html-element html-content]
  (let [parsed-html-element
        (hickory/as-hickory (hickory/parse html-element))
        extracted-html-element
        (first (:content 
                 (second (:content 
                           (first (:content parsed-html-element))))))]
    (letfn [(string-to-vector
              [string]
              (string/split string #" "))
            (map-attr
              [value s-function key]
              (map s-function (string-to-vector (key value))))
            (map-hickory-class-id
              [value]
              (let [contains-class (contains? value :class)
                    contains-id    (contains? value :id)]
                (cond
                  (and contains-class contains-id)
                  (concat (map-attr value s/class :class)
                          (map-attr value s/id    :id))
                  contains-class
                  (map-attr value s/class :class)
                  contains-id
                  (map-attr value s/id :id)
                  :else [])))
            (hickory-select-elements
              [[selector-type selector-value]]
              (case selector-type
                :type (s/node-type selector-value)
                :attrs (map-hickory-class-id selector-value)
                :tag (s/tag selector-value)
                :content []))]
      (s/select (apply s/and (flatten (map hickory-select-elements  
                                           extracted-html-element)))
                html-content))))

(defn- perform-extraction [html-content]
  (letfn [(maybe-execute [f1 f2 html-element]
            (if (instance? java.lang.String html-element)
              (f1 html-element)
              (f2 html-element)))
          (extract-strings [parsed-html-content]
            (for [html-element (:content parsed-html-content)]
              (maybe-execute string/trim extract-strings html-element))) 
          (combine-text [text-elements]
            (string/join " " text-elements))] 
      (map #(maybe-execute identity combine-text %) 
           (map #(maybe-execute identity flatten %) 
                (map extract-strings 
                     html-content)))))

(defn perform-monitoring [monitor config-settings]
  (let [javascript?
        (case (:javascript config-settings)
          "all" true
          "manual" (:javascript monitor)
          "none" false 
          (throw (Exception. 
                  "In settings set javascript to all, none or manual."))) 
        html-string (if javascript? 
                      (sc/fetch-page-content (:url monitor) config-settings) 
                      (:body (client/get (:url monitor))))
        html-content
        (hickory/as-hickory (hickory/parse html-string))
        parsed-html-content
        (parse-html-content (:element monitor) html-content)
        parsed-inner-html-content
        (if-not (empty? (:inner_element monitor))
          (parse-html-content 
           (:inner_element monitor) (first parsed-html-content))
          parsed-html-content)
        extracted-strings
        (perform-extraction parsed-inner-html-content)]
    (doall (distinct extracted-strings))))
