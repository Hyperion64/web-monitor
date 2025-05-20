(ns web.scrape-logic
  (:require [notifications.create-logs :as cl]
            [web.scraper               :as s]
            [web.html-parser           :as hp]
            [clojure.string            :as str]))

(defn- manage-scrape [scrape-data monitor]
  (let [monitor-name (:name monitor)
        scrape-data-type (:type scrape-data)
        scrape-data-content (:content scrape-data)]
    (case scrape-data-type
      "page-source"
      scrape-data-content
      "error"
      (do (cl/record-log-element scrape-data monitor-name)
          ""))))

(defn- initialize-regular-scrape [monitor]
  (manage-scrape (s/regular-scrape monitor) monitor))

(defn- initialize-continuous-scrape [driver monitor]
  (manage-scrape (s/fetch-driver driver) monitor))

(defn- process-html-string [scrape-content monitor]
  (let [parsed-html
        (-> scrape-content
            hp/hickory-parse-html-contents
            (hp/initialize-parse-html-contents monitor))]
    (if-not (empty? parsed-html)
      (-> parsed-html
          (hp/perform-selected-filtering monitor)
          (hp/initialize-extraction monitor)
          (hp/perform-general-filtering (:filters monitor))
          distinct)
      (do (cl/record-log-element
           {:type
            "warning"
            :content
            (str "Selectors did not find any elements in " (:name monitor))})
          []))))

(defn manage-regular-scrape [monitor]
  (letfn [(get-html-content [monitor]
            (-> monitor
                initialize-regular-scrape
                (process-html-string monitor)))
          (iterate-monitoring-url-range
            [first-url-part second-url-part iterator
             tolerance max-tolerance max-scrapes]
            (let [iterated-url
                  (str first-url-part iterator second-url-part)
                  monitor-with-iterated-url
                  (assoc monitor :url iterated-url)
                  iterated-html-content
                  (get-html-content monitor-with-iterated-url)]
              (concat iterated-html-content
                      (cond
                        (= max-scrapes 0)
                        []
                        (not-empty iterated-html-content)
                        (iterate-monitoring-url-range
                         first-url-part second-url-part (+ iterator 1)
                         max-tolerance max-tolerance (- max-scrapes 1))
                        (not (= tolerance -1))
                        (iterate-monitoring-url-range
                         first-url-part second-url-part (+ iterator 1)
                         (- tolerance 1) max-tolerance max-scrapes)
                        :else
                        []))))
          (manage-iterate-monitoring-url-range []
            (let [url-range (:url-range monitor)]
              (if-not (empty? url-range)
                (let [first-url-part  (nth (:url-parts url-range) 0)
                      second-url-part (nth (:url-parts url-range) 1)
                      iterator        (nth (:url-parts url-range) 2)
                      tolerance   (:tolerance   url-range)
                      max-scrapes (:max-scrapes url-range)]
                  (iterate-monitoring-url-range
                   first-url-part second-url-part iterator
                   tolerance tolerance max-scrapes))
                [])))]
    (distinct (concat
               (flatten
                (map #(get-html-content (assoc monitor :url %))
                     (:url monitor)))
               (manage-iterate-monitoring-url-range)))))

(defn manage-continuous-scrape
  [driver monitor previous-html previous-web-contents]
  (let [html
        (initialize-continuous-scrape driver monitor)
        html-is-new
        (not (or (= html previous-html)
                 (= html "")))
        web-contents
        (if html-is-new
          (process-html-string html monitor)
          [])
        web-contents-are-new
        (not (or (= previous-web-contents web-contents)
                 (empty? web-contents)))]
    {:html                html
     :html-is-new         html-is-new
     :web-contents        web-contents
     :web-contents-are-new web-contents-are-new}))

(defn- valid-hrefs-for-accessing? [html-content]
  (let [hrefs (:hrefs html-content)]
    (not (or (str/includes? hrefs "\n")
             (str/blank?    hrefs)
             (nil?          hrefs)))))

(defn- scrape-html-contents [monitor]
  (-> monitor
      initialize-regular-scrape
      hp/hickory-parse-html-contents))

(defn perform-extract-href-content [html-contents monitor]
  (let [href-addition (:href-addition monitor)]
    (if-not (empty? href-addition)
      (map (fn [html-content]
             (if (valid-hrefs-for-accessing? html-content)
               (let [href-monitor
                     {:url                  (:hrefs html-content)
                      :css-selector         href-addition
                      :js-load-time-seconds (:js-load-time-seconds monitor)
                      :browser              (:browser monitor)}
                     href-html-content
                     (first (-> href-monitor
                                scrape-html-contents
                                (hp/initialize-parse-html-contents href-monitor)
                                (hp/initialize-extraction href-monitor)))]
                 (assoc html-content
                        :text
                        (str (:text html-content)
                             "\n\n\n"
                             (:text href-html-content))))
               html-content))
           html-contents)
      html-contents)))

(defn- perform-href-filtering [href-filters href-html-content monitor]
  (let [{text-filters false
         inner-href-filters true}
        (group-by #(= (:type %) "href") href-filters)
        parsed-text-contents
        (map (fn [text-filter]
               {:extracted-contents
                (hp/initialize-extraction
                 [{:content (hp/initialize-parse-html-contents
                             href-html-content text-filter)}]
                 {:text-css-selectors []
                  :href-css-selectors []})
                :operator        (:operator text-filter)
                :filter-value    (:filter-value text-filter)})
             text-filters)
        filtered-parsed-text-contents
        (map #(:text (first
                      (hp/perform-general-filtering
                       (:extracted-contents %)
                       [(dissoc % :extracted-contents)])))
             parsed-text-contents)
        extract-href-content
        (fn [css-selector]
          (let [scrape-url
                (:hrefs (first (hp/initialize-extraction
                                [{:content
                                  (hp/initialize-parse-html-contents
                                   href-html-content css-selector)}]
                                {:url                (:url monitor)
                                 :text-css-selectors []
                                 :href-css-selectors []})))
                get-deeper-href-content
                (fn []
                  (-> monitor
                      (assoc :url scrape-url)
                      scrape-html-contents))]
            (if-not (= scrape-url "")
              (perform-href-filtering
               (:href-filters css-selector)
               (get-deeper-href-content) monitor)
              false)))]
    (if (or (empty? text-filters)
            (every? identity
                    (map #(not (nil? %))
                         filtered-parsed-text-contents)))
      (or (nil? inner-href-filters)
          (every? identity (map extract-href-content
                                (or inner-href-filters []))))
      false)))

(defn- initialize-href-filtering [html-contents monitor]
  (filter
   (fn [html-content]
     (if (some #(= (:type %) "href") (:filters monitor))
       (if (valid-hrefs-for-accessing? html-content)
         (let [scrape-url
               (:hrefs html-content)
               href-filters
               (:href-filters
                (first (filter #(= (:type %) "href") (:filters monitor))))
               href-html-content
               (scrape-html-contents (assoc monitor :url scrape-url))]
           (perform-href-filtering href-filters href-html-content monitor))
         false)
       true))
   html-contents))

(defn manage-new-web-content-filtering [new-web-contents monitor]
  (-> new-web-contents
      (initialize-href-filtering
       (assoc monitor :url (first (:url monitor))))
      (hp/perform-custom-filtering
       (:filters monitor))))
