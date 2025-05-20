(ns notifications.create-logs
  (:require [fs.access-files  :as af]
            [utils.timestamps :as t]
            [clojure.string :as str]))

(def ^:private log-file-id
  (let [log-timestamp (t/make-log-id-timestamp)
        ms-suffix     (mod (System/currentTimeMillis) 1000)
        randomish-str (Long/toString ms-suffix 36)]
    (str log-timestamp "-" randomish-str)))

(defn record-log-element [log-element]
  (let [log-string (str "[" (t/make-log-element-timestamp) "]"
                        " "
                        "[" (str/upper-case (:type log-element)) "]"
                        " "
                        (:content log-element))]
    (af/append-log-element log-string log-file-id)))
