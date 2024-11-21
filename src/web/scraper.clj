(ns web.scraper
  (:require [clj-http.client :as client])
  (:import (org.openqa.selenium.firefox FirefoxDriver)
           (org.openqa.selenium.firefox FirefoxOptions)))

(defn- fetch-js-page-content [url js-load-time]
  (let [options (doto (FirefoxOptions.) (.addArguments ["--headless"]))
        driver (FirefoxDriver. options)]
    (try
      (.get driver url)
      (Thread/sleep (* 1000 js-load-time))
      (.getPageSource driver)
      (finally (.quit driver)))))

(defn fetch-html-string [monitor]
  (let [js-bool      (:javascript           monitor)
        url          (:url                  monitor)
        js-load-time (:js-load-time-seconds monitor)]
    (if js-bool
      (fetch-js-page-content url js-load-time)
      (:body (client/get url)))))

