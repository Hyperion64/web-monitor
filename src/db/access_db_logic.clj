(ns db.access-db-logic
  (:require [db.access-db :as ad]
            [notifications.rss-feed :as rf]
            [utils.shared-functions :as sf]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]
            [db.access-db-logic :as adl]))

(defn create-db []
  (ad/create-db))

(defn- remove-namespaces [db-monitors]
  (map (fn [db-monitor]
         (into {} (for [[k v] db-monitor]
                    [(keyword (name k)) v])))
       db-monitors))

(defn- get-content-selectors-hash [monitor]
  (let [content-selector-keys
        [:javascript :url :url-range :css-selector :inner-css-selector :filters
         :text-css-selector :href-css-selector]
        selector-vector
        (filter #(not-empty %)
                (for [k content-selector-keys]
                  (if (not (nil? (k monitor)))
                    [k (k monitor)]
                    [])))
        selector-map
        (if (not-empty selector-vector)
          (into {} selector-vector)
          {})]
    (letfn [(canonicalize [data]
              (cond
                (map? data)
                (into (sorted-map)
                      (into {} (map (fn [[k v]] [k (canonicalize v)]) data)))
                (coll? data)
                (map canonicalize data)
                :else
                data))]
      (-> selector-map
          canonicalize
          pr-str
          hash/md5
          codecs/bytes->hex
          (subs 0 16)))))

(defn- select-monitor-by-name [monitor-maps monitor-name]
  (first (filter #(= (:name %) monitor-name) monitor-maps)))

(defn- get-updated-monitors [monitor-names-in-both config-monitors db-monitors]
  (letfn [(format-db-monitor
            [monitor]
            (assoc monitor :active (= 1 (:active monitor))))
          (get-activity-updates
            [config-monitor db-monitor]
            (cond
              (and (not (:active config-monitor))
                   (:active db-monitor))
              [:active 0]
              (and (:active config-monitor)
                   (not (:active db-monitor)))
              [:active 1]
              :else
              []))
          (get-content-selector-updates
            [config-monitor db-monitor]
            (let [content-selectors-hash
                  (get-content-selectors-hash config-monitor)]
              (if-not (= content-selectors-hash
                         (:content_selectors_hash db-monitor))
                [:content_selectors_hash content-selectors-hash]
                [])))]
    (filter #(not-empty %)
            (for [monitor-name monitor-names-in-both]
              (let [selected-config-monitor
                    (select-monitor-by-name config-monitors monitor-name)
                    selected-db-monitor
                    (format-db-monitor
                     (select-monitor-by-name db-monitors monitor-name))
                    activity-updates
                    (get-activity-updates selected-config-monitor
                                          selected-db-monitor)
                    content-selector-updates
                    (get-content-selector-updates selected-config-monitor
                                                  selected-db-monitor)
                    combined-updates
                    (flatten [activity-updates content-selector-updates])]
                (if-not (empty? combined-updates)
                  (apply assoc {:name monitor-name} combined-updates)
                  {}))))))

(defn update-db-monitors [config-monitors]
  (let [db-monitors
        (remove-namespaces (ad/get-all-web-monitors))
        config-monitor-names
        (map #(:name %) config-monitors)
        db-monitor-names
        (map #(:name %) db-monitors)
        new-monitor-names
        (sf/ordered-complement config-monitor-names db-monitor-names)
        deleted-monitor-names
        (sf/ordered-complement db-monitor-names config-monitor-names)
        existing-monitor-names
        (sf/ordered-complement
         (sf/ordered-conjunction config-monitor-names db-monitor-names)
         deleted-monitor-names)
        new-monitors
        (map #(select-monitor-by-name config-monitors %) new-monitor-names)
        monitor-updates
        (get-updated-monitors existing-monitor-names config-monitors
                              db-monitors)]
    (doseq [n new-monitors]
      (ad/insert-web-monitor
       {:name                   (:name n)
        :active                 (if (:active n) 1 0)
        :datetime               (sf/make-datetime)
        :content_selectors_hash (get-content-selectors-hash n)}))
    (doseq [u monitor-updates]
      (let [db-monitor
            (select-monitor-by-name db-monitors (:name u))
            [u-added-content-selectors
             has-new-content-selectors]
            (if (nil? (:content_selectors_hash u))
              [(assoc u
                      :content_selectors_hash
                      (:content_selectors_hash db-monitor))
               false]
              [u
               true])
            u-added-active-status
            (if (nil? (:active u))
              (assoc u-added-content-selectors :active (:active db-monitor))
              u-added-content-selectors)]
        (when has-new-content-selectors
          (ad/delete-web-elements (:name u))
          (rf/delete-rss-feed     (:name u)))
        (ad/update-web-monitor u-added-active-status)))
    (doseq [d deleted-monitor-names]
      ((ad/delete-web-monitor  d)
       (ad/delete-web-elements d)
       (rf/delete-rss-feed     d)))))

(defn get-web-contents-db [monitor]
  (let [web-elements
        (remove-namespaces (ad/get-web-elements (:name monitor)))
        converted-web-elements
        (map #(dissoc
               (assoc % :not-seen-since (:not_seen_since %))
               :not_seen_since)
             web-elements)
        converted-web-elements-without-filtered
        (filter #(= (:filtered_out %) 0) converted-web-elements)
        {existing-web-elements true
         removed-web-elements false}
        (group-by #(nil? (:not-seen-since %))
                  converted-web-elements-without-filtered)
        web-elements-text-and-hrefs
        (map (fn [web-elements]
               (map #(select-keys % [:text :hrefs]) web-elements))
             [converted-web-elements
              existing-web-elements
              removed-web-elements])]
    {:all      (nth web-elements-text-and-hrefs 0)
     :existing (nth web-elements-text-and-hrefs 1)
     :removed  (nth web-elements-text-and-hrefs 2)}))

(defn save-web-elements [elements-type web-elements]
  (let [elements-datetime
        (:datetime (first web-elements))
        not-seen-since
        (elements-type {:removed elements-datetime})
        filtered-by-href-filter
        (elements-type {:new 0 :filtered-out 1})
        make-new-db-web-element
        (fn [web-element]
          (assoc
           (select-keys web-element [:monitor-name :text :hrefs :datetime])
           :filtered-out filtered-by-href-filter))
        make-update-db-web-element
        (fn [web-element]
          (assoc
           (select-keys web-element [:monitor-name :text :hrefs])
           :not-seen-since not-seen-since))
        [db-function make-web-element-function]
        (cond
          (elements-type {:new true :filtered-out true})
          [ad/insert-web-element make-new-db-web-element]
          (elements-type {:removed true :rediscovered true})
          [ad/update-not-seen-since make-update-db-web-element])]
    (doseq [web-element web-elements]
      (db-function (make-web-element-function web-element)))))
