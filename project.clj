(defproject website_notifications "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :main webMonitor.core
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/data.json "2.5.0"]
                 [org.clojure/data.xml "0.0.8"]

                 [clj-http "3.12.0"]
                 [clj-time "0.15.2"]

                 [cheshire "5.10.0"]
                 [cider/cider-nrepl "0.42.1"]
                 [compojure "1.6.2"]
                 [com.github.seancorfield/next.jdbc "1.3.925"] 
                 [org.clj-commons/hickory "0.7.3"]
                 [ring/ring-core "1.11.0"]
                 [ring/ring-jetty-adapter "1.11.0"]
                 [sqlitejdbc "0.5.6"]
                 
                 [org.seleniumhq.selenium/selenium-java "4.0.0"]
                 [org.seleniumhq.selenium/selenium-firefox-driver "4.0.0"]] 
  :repl-options {:init-ns webMonitor.repl-run})
