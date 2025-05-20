(ns web.scraper
  (:require [clj-http.client :as client])
  (:import (org.openqa.selenium.chrome ChromeDriver ChromeOptions)
           (org.openqa.selenium.firefox FirefoxDriver FirefoxOptions)))

(defn create-driver [url js-load-time browser]
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
    (.get driver url)
    (Thread/sleep (* 1000 js-load-time))
    driver))

(defn fetch-driver [driver]
  (try {:type "page-source"
        :content (.getPageSource driver)}
       (catch Exception exception
         {:type "error"
          :content (.getMessage exception)})))

(defn quit-driver [driver]
  (.quit driver))

(defn regular-scrape [monitor]
  (let [url          (:url monitor)
        js-load-time (:js-load-time-seconds monitor)
        browser      (:browser monitor)]
    (if-not (= browser "none")
      (let [driver       (create-driver url js-load-time browser)
            page-content (fetch-driver driver)]
        (quit-driver driver)
        page-content)
      (:body (client/get url)))))
