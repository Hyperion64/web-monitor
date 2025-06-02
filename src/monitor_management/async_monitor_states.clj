(ns monitor-management.async-monitor-states
  (:require [utils.shared-functions :as sf]
            [clojure.set :as set]))

(def continuous-monitor-states (atom {}))

(def regular-monitor-states (atom {}))

(defn- monitor-to-states-key [monitor]
  (keyword (:name monitor)))

(defn manage-continuous-active-monitors [continuous-monitors]
  (let [running-monitor-keys
        (set (keys @continuous-monitor-states))
        active-monitor-keys
        (set (map monitor-to-states-key continuous-monitors))
        monitors-to-stop-keys
        (set/difference running-monitor-keys active-monitor-keys)
        monitors-not-to-stop-keys
        (set/difference running-monitor-keys monitors-to-stop-keys)
        monitors-to-restart-keys
        (set (filter #(= (% @continuous-monitor-states) "restart-confirmed")
                     monitors-not-to-stop-keys))
        monitors-to-start-keys
        (set/union monitors-to-restart-keys
                   (set/difference active-monitor-keys running-monitor-keys))
        [monitors-to-start
         monitors-to-stop]
        (map (fn [monitor-keys]
               (sf/get-maps-with-values
                continuous-monitors :name (map #(name %) monitor-keys)))
             [monitors-to-start-keys
              monitors-to-stop-keys])]
    [monitors-to-start monitors-to-stop]))

(defn modify-continuous-monitor-states [monitor action]
  (let [monitor-key
        (monitor-to-states-key monitor)
        [assoc-or-dissoc state]
        (case action
          "initialize-start"   [assoc  "awaiting-start-confirmation"]
          "initialize-stop"    [assoc  "awaiting-stop-confirmation"]
          "initialize-restart" [assoc  "awaiting-restart-confirmation"]
          "confirm-start"      [assoc  "active"]
          "confirm-stop"       [dissoc nil]
          "confirm-restart"    [assoc  "restart-confirmed"])]
    (swap! continuous-monitor-states assoc-or-dissoc monitor-key state)))

(defn initialize-continuous-monitor-states
  [monitors-to-start monitors-to-stop monitors-to-restart]
  (doseq [[monitors action]
          [[monitors-to-start   "initialize-start"]
           [monitors-to-stop    "initialize-stop"]
           [monitors-to-restart "initialize-restart"]]]
    (run! #(modify-continuous-monitor-states % action) monitors)))

(defn request-continuous-monitor-state [monitor]
  ((monitor-to-states-key monitor) @continuous-monitor-states))

(defn modify-regular-monitor-state [monitor operation]
  (let [monitor-key (monitor-to-states-key monitor)]
    (case operation
      "start"
      (swap! regular-monitor-states assoc monitor-key "active")
      "stop"
      (swap! regular-monitor-states dissoc monitor-key))))

(defn request-regular-monitor-state [monitor]
  ((monitor-to-states-key monitor) @regular-monitor-states))
