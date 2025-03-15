(ns web.scraper
  (:require [clj-http.client :as client])
  (:import (org.openqa.selenium.chrome ChromeDriver ChromeOptions)
           (org.openqa.selenium.firefox FirefoxDriver FirefoxOptions)))

(defn- fetch-js-page-content [url js-load-time browser]
  (let [driver
        (case browser
          "chrome"
          (ChromeDriver. (doto (ChromeOptions.)
                           (.addArguments ["--headless"
                                           "--disable-gpu"
                                           "--no-sandbox"
                                           "--disable-dev-shm-usage"])))
          "firefox"
          (FirefoxDriver. (doto (FirefoxOptions.)
                            (.addArguments ["--headless"
                                            "--disable-gpu"
                                            "--no-sandbox"
                                            "--disable-dev-shm-usage"]))))]
    (try
      (.get driver url)
      (Thread/sleep (* 1000 js-load-time))
      (.getPageSource driver)
      (finally (.quit driver)))))

(defn fetch-html-string [monitor]
  (let [url          (:url monitor)
        js-load-time (:js-load-time-seconds monitor)
        browser      (:browser monitor)]
    (if-not (= browser "none")
      (fetch-js-page-content url js-load-time browser)
      (:body (client/get url)))))

