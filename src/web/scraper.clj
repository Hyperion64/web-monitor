(ns web.scraper
  (:require [clj-http.client :as client])
  (:import [org.openqa.selenium WebDriver WebElement By JavascriptExecutor Keys]
           [org.openqa.selenium.chrome ChromeDriver ChromeOptions]
           [org.openqa.selenium.firefox FirefoxDriver FirefoxOptions]))

(defn- find-element [driver {:keys [tag classes ids]}]
  (let [by-css-selector-string
        (str (or tag "")
             (apply str (map #(str "." %) classes))
             (apply str (map #(str "#" %) ids)))]
    (.findElement ^WebDriver driver (By/cssSelector by-css-selector-string))))

(defn- click-element [web-element]
  (.click ^WebElement web-element))

(defn- enter-text [{:keys [text submit]} web-element]
  (click-element web-element)
  (.sendKeys ^WebElement web-element (into-array CharSequence [text]))
  (when submit
    (.submit ^WebElement web-element (into-array CharSequence [Keys/ENTER]))))

(defn- scroll-page [driver max-scrolls]
  (let [execute-scroll-operation
        (fn [operation]
          (.executeScript ^JavascriptExecutor driver
                          operation (into-array Object [])))
        get-scroll-height
        (fn []
          (execute-scroll-operation
           "return document.body.scrollHeight;"))
        perform-scrolling
        (fn []
          (execute-scroll-operation
           "window.scrollTo(0, document.body.scrollHeight);"))]
    (loop [i 0
           last-height get-scroll-height]
      (when (or (= 0 max-scrolls)
                (< i max-scrolls))
        (perform-scrolling)
        (Thread/sleep 1000)
        (let [new-height get-scroll-height]
          (if (= last-height new-height)
            nil
            (recur (inc i) new-height)))))))

(defn- perform-web-actions [driver operations]
  (doseq [operation operations]
    (let [operation-type (:type operation)]
      (case operation-type
        "wait"   (Thread/sleep (* 1000 (:time operation)))
        "scroll" (scroll-page driver (:scrolls operation))
        (let [web-element (find-element driver (:css-selector operation))]
          (case operation-type
            "click"      (click-element web-element)
            "enter-text" (enter-text operation web-element)))))))

(defn create-driver [url js-load-time browser web-operations]
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
    (perform-web-actions driver web-operations)
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
  (let [{:keys [url
                js-load-time-seconds
                browser
                web-operations]}
        monitor]
    (if-not (= browser "none")
      (let [driver
            (create-driver url js-load-time-seconds browser web-operations)
            page-content
            (fetch-driver driver)]
        (quit-driver driver)
        page-content)
      (:body (client/get url)))))
