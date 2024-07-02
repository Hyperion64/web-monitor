(ns applicationLayer.selenium-scraper
  (:import (org.openqa.selenium.firefox FirefoxDriver)
           (org.openqa.selenium.firefox FirefoxOptions)) 
  (:require [applicationLayer.shared-functions :as sf]))

(defn fetch-page-content [url config-settings]
  (System/setProperty "webdriver.gecko.driver" 
                      (sf/get-project-path "/resources/geckodriver"))
  (let [options (doto (FirefoxOptions.) (.setHeadless true))
        driver (FirefoxDriver. options)]
    (try
      (.get driver url)
      (Thread/sleep (* 1000 (:js_load_time_seconds config-settings)))
      (.getPageSource driver)
      (finally (.quit driver)))))
