(defproject web-monitor "1.0"
  :description "Web scraper with notification system"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :aot :all
  :main web-monitor.core
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/data.json "2.5.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [clj-http "3.13.0"]
                 [clojure.java-time "1.4.3"]
                 [compojure "1.7.1"]
                 [com.github.seancorfield/next.jdbc "1.3.955"]
                 [buddy/buddy-core "1.12.0-430"]
                 [org.clj-commons/hickory "0.7.5"]
                 [org.seleniumhq.selenium/selenium-java "4.26.0"]
                 [org.slf4j/slf4j-simple "2.0.16"]
                 [ring/ring-core "1.13.0"]
                 [ring/ring-jetty-adapter "1.13.0"]
                 [sqlitejdbc "0.5.6"]]
  :plugins [[lein-ancient "0.7.0"]]
  :jvm-opts ["-Dorg.slf4j.simpleLogger.defaultLogLevel=off"]
  :repl-options {:init-ns repl.repl-run})
