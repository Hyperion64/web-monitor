(defproject web-monitor "1.0"
  :description "Web scraper with notification system"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :aot :all
  :main web-monitor.core
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/data.json "2.5.1"]
                 [org.clojure/data.xml "0.0.8"]
                 [clj-http "3.13.0"]
                 [clojure.java-time "1.4.3"]
                 [clj-time "0.15.2"]
                 [compojure "1.7.1"]
                 [com.github.seancorfield/next.jdbc "1.3.1002"]
                 [buddy/buddy-core "1.12.0-430"]
                 [org.clj-commons/hickory "0.7.7"]
                 [org.seleniumhq.selenium/selenium-java "4.32.0"]
                 [org.slf4j/slf4j-simple "2.0.17"]
                 [ring/ring-core "1.14.1"]
                 [ring/ring-jetty-adapter "1.14.1"]
                 [sqlitejdbc "0.5.6"]]
  :plugins [[lein-ancient "0.7.0"]]
  :jvm-opts ["-Dorg.slf4j.simpleLogger.defaultLogLevel=off"
             "-XX:-PrintGCDetails"]
  :repl-options {:init-ns utils.repl-run})
