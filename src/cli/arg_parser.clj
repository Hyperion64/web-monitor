(ns cli.arg-parser
  (:require [fs.access-files :as af]
            [clojure.string    :as str]
            [clojure.data.json :as json]))

(defn parse-args [args]
  (let [[arg-0 arg-1 arg-2]
        args
        [mode content-type content]
        (cond
          (nil? arg-0)    [nil nil nil]
          (= arg-0 "-op") ["-o" "-p" arg-1]
          (= arg-0 "-oj") ["-o" "-j" arg-1]
          :else           [arg-0 arg-1 arg-2])
        error-output
        ["error" nil (str "Unrecognized option '" (str/join " " args) "'")]
        [parsed-args-mode parsed-args-content-type parsed-args-content]
        (cond
          (nil? mode)
          ["looping" nil nil]
          (#{"-o" "--once"} mode)
          (if-not (nil? content)
            (cond
              (#{"-p" "--path"} content-type)
              ["once" "path" content]
              (#{"-j" "--json"} content-type)
              ["once" "json" (json/read-str content :key-fn keyword)]
              :else
              error-output)
            ["once" "standard" nil])
          (#{"-h" "--help"} mode)
          ["help" nil (af/get-help-output)]
          (#{"-v" "--version"} mode)
          ["version" nil "web-monitor 0.1.0"]
          :else
          error-output)]
    {:mode         parsed-args-mode
     :content-type parsed-args-content-type
     :content      parsed-args-content}))
