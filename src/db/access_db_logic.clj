(ns db.access-db-logic
  (:require [db.access-db :as ad]
            [fs.access-files :as af]
            [utils.shared-functions :as sf]
            [utils.timestamps :as t]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]
            [db.access-db-logic :as adl]))

(defn create-db []
  (ad/create-db))

(defn- remove-namespaces [db-monitors]
  (mapv
   (fn [db-monitor]
     (reduce-kv (fn [m k v]
                  (assoc m (keyword (name k)) v))
                {}
                db-monitor))
   db-monitors))

(defn- get-content-selectors-hash [monitor]
  (-> monitor
      (select-keys [:javascript
                    :url
                    :url-range
                    :url-file
                    :css-selector
                    :inner-css-selector
                    :web-operations
                    :filters
                    :text-css-selectors
                    :href-css-selectors])
      pr-str
      hash/md5
      codecs/bytes->hex
      (subs 0 16)))

(defn find-modified-monitors [config-monitors]
  (let [db-monitors (remove-namespaces (ad/get-all-web-monitors))]
    (filter
     #(let [matching-db-monitor
            (first (sf/get-maps-with-values db-monitors :name [(:name %)]))
            matching-db-monitor-selector-hash
            (:content_selectors_hash matching-db-monitor)
            config-monitor-content-selectors-hash
            (get-content-selectors-hash %)]
        (and (not (nil? matching-db-monitor-selector-hash))
             (not= config-monitor-content-selectors-hash
                   matching-db-monitor-selector-hash)))
     config-monitors)))

(defn- select-monitor-by-name [monitor-maps monitor-name]
  (first (filter #(= (:name %) monitor-name) monitor-maps)))

(defn- get-updated-monitors [monitor-names-in-both config-monitors db-monitors]
  (letfn [(format-db-monitor [monitor]
            (assoc monitor :active (= 1 (:active monitor))))
          (get-activity-updates [config-monitor db-monitor]
            (cond
              (and (not (:active config-monitor))
                   (:active db-monitor))
              [:active 0]
              (and (:active config-monitor)
                   (not (:active db-monitor)))
              [:active 1]
              :else
              []))
          (get-content-selector-updates [config-monitor db-monitor]
            (let [content-selectors-hash
                  (get-content-selectors-hash config-monitor)]
              (if-not (= content-selectors-hash
                         (:content_selectors_hash db-monitor))
                [:content_selectors_hash content-selectors-hash]
                [])))]
    (keep (fn [monitor-name]
            (let [config-monitor
                  (select-monitor-by-name config-monitors monitor-name)
                  db-monitor
                  (format-db-monitor
                   (select-monitor-by-name db-monitors monitor-name))
                  activity-updates
                  (get-activity-updates config-monitor db-monitor)
                  content-selector-updates
                  (get-content-selector-updates config-monitor db-monitor)
                  combined-updates
                  (concat activity-updates content-selector-updates)]
              (when (seq combined-updates)
                (apply assoc {:name monitor-name} combined-updates))))
          monitor-names-in-both)))

(defn- get-new-monitors-and-updates [config-monitors]
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
        (get-updated-monitors
         existing-monitor-names config-monitors db-monitors)]
    {:db-monitors           db-monitors
     :deleted-monitor-names deleted-monitor-names
     :new-monitors          new-monitors
     :monitor-updates       monitor-updates}))

(defn update-db-monitors [config-monitors]
  (let [{:keys [db-monitors deleted-monitor-names new-monitors monitor-updates]}
        (get-new-monitors-and-updates config-monitors)]
    (doseq [n new-monitors]
      (ad/insert-web-monitor
       {:name                   (:name n)
        :active                 (if (:active n) 1 0)
        :first_defined          (t/make-datetime)
        :content_selectors_hash (get-content-selectors-hash n)}))
    (doseq [u monitor-updates]
      (let [monitor-name
            (:name u)
            db-monitor
            (select-monitor-by-name db-monitors monitor-name)
            [content-selectors-hash has-new-content-selectors]
            (if (contains? u :content_selectors_hash)
              [(:content_selectors_hash u)          true]
              [(:content_selectors_hash db-monitor) false])
            active-status
            (if (contains? u :active)
              (:active u)
              (:active db-monitor))
            monitor-update-map
            [{:content_selectors_hash content-selectors-hash
              :active                 active-status}
             {:name                   monitor-name}]]
        (when has-new-content-selectors
          (ad/delete-web-elements {:monitor_name monitor-name})
          (af/delete-rss-feed     monitor-name))
        (ad/update-web-monitor monitor-update-map)))
    (doseq [d deleted-monitor-names]
      ((ad/delete-web-monitor  {:name d})
       (ad/delete-web-elements {:monitor_name d})
       (af/delete-rss-feed     d)))))

(defn get-web-contents-db [monitor]
  (let [web-elements
        (remove-namespaces
         (ad/get-web-elements {:monitor_name (:name monitor)}))
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
                  converted-web-elements-without-filtered)]
    (reduce (fn [reduced-elements-map [elements-type web-elements]]
              (assoc reduced-elements-map
                     elements-type
                     (map #(select-keys % [:text :hrefs]) web-elements)))
            {}
            {:all      converted-web-elements
             :existing existing-web-elements
             :removed  removed-web-elements})))

(defn save-web-elements [elements-type web-elements]
  (cond
    (elements-type {:new true :filtered-out true})
    (let [filtered-by-href-filter (elements-type {:new 0 :filtered-out 1})]
      (doseq [web-element web-elements]
        (ad/insert-web-element
         (assoc (select-keys web-element [:text :hrefs])
                :filtered_out   filtered-by-href-filter
                :monitor_name   (:monitor-name web-element)
                :not_seen_since (:datetime     web-element)))))
    (elements-type {:removed true :rediscovered true})
    (let [elements-datetime (:datetime (first web-elements))
          not-seen-since    (elements-type {:removed elements-datetime})]
      (doseq [web-element web-elements]
        (ad/update-not-seen-since
         [{:not_seen_since not-seen-since}
          (assoc (select-keys web-element [:text :hrefs])
                 :monitor_name (:monitor-name web-element))])))))
