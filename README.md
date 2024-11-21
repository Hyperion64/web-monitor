# web-monitor

Web-monitor is a Clojure-based tool that scrapes websites and notifies the user when new elements are detected with the use of CSS selectors. It will extract all text and links found inside the specified elements. This can be useful if you want to know when a new article, video, album, etc. has been published on a website. It is also possible to filter the content for certain keywords, number values and length. Web-monitor will store the found content in a SQLite database which can be accessed while web-monitor is running for integration with other applications. Currently rss and the matrix protocol are available by default but you can also specify a custom script that web-monitor will pass the content to or get print output in JSON format for piping it into other programs. Before using web-monitor for rss, check out the Awesome-rss browser extension. A lot of websites have rss feeds that you will miss otherwise.

## Installation

* Only works on Linux.

* The program is available as a jar in the web-monitor.tar file which also contains all the other relevant files to run it. You will need Java 11 to run that jar file. Learn how to start it under **How to start it**.

* Or just clone the repo and us leiningen. But then it will be completely compiled everytime and there is no reason to get leiningen for it unless you want to modify the code.

* To scrape websites with JavaScript loading enabled, web-monitor also requires Firefox and a compatible Geckodriver version. Ensure Geckodriver is executable in your system path.

## Usage

### Project file structure

* config.json: here you configure the web-monitor, a detailed explanation follows in **Setting up config.json**.

* data: contains two folders:
  - db: contains the SQLite database.
  - rss_feeds: contains all rss-feed xml files.

* notification_scripts: put your custom notifications scripts here as described below.

### Setting up config.json:
The config.json file contains the following 3 JSON objects:

* **settings**
  - "frequency-minutes": sets frequency of scraping.
  - "rss-port": sets port for rss feeds.
  - "javascript": sets if javascript should be loaded when scraping. Note that loading the js is by far the most resource intensive part in this program and could slow down already very busy and slow computers significantly for high frequency scrapes. Also note the dependencies in **Installation**. Defaults to false.
  - "js-load-time-seconds": set how long the js has time to load for each scraping, defaults to 2.
  - "messengers":
    - "matrix"
    - "rss": "rss" will automatically create an rss feed which you can use. Just provide a link like: http://localhost:8080/Example-monitor-name.xml to your rss reader with the localhost number being the port number specified in the settings. The rss file will be deleted if the monitor is removed from the config or if the details for the extraction of the monitor have changed but if rss is still defined as a messenger, it will just be replaced of course. It is not being deleted if it is merely deactivated.
    - "print"
    - "notify-send": will make a system notification with the found text.
    - the file names of custom scripts inside resources/notification_scripts are supported to which the newly found elements will be passed, just include the file name here.

    "print" and the custom script will pass the content in the form of a array of JSON objects with keys:
    - "monitor-name": the name of the monitor.
    - "text": the text found in the element.
    - "hrefs": the links found in the element.
    - "datetime": the exact time it was found. In the format dd.MM.yyyy HH:mm:ss with the timezone that the computer has as a system setting.
    - "type": "new", "removed" or "rediscovered.
    
    "messengers" can be specified as a string or array, depending on if you want one or multiple.
  - "report-first-found": string or array is given with messengers for which newly found content should be sent if the monitor has never been used before. Booleans are also allowed where true means every messenger and false means only rss. Always automatically enabled for rss. Defaults to only enabled for rss.
  - "notify-if-element-removed": Same input as "report-first-found" where the meaning of the inputs is equivalent but applied to notifying the user if an elemnt was removed, only that false does not include rss either. Defaults to false.
  - "notify-if-element-rediscovered": Same as "notify-if-element-removed" but it is about notifying the user if a previously removed element has been found on the website again. Defaults to false.

  All but frequency-minutes and rss-port can be overwritten for the individual monitors. A default definition in settings is not required if it is specified in every monitor.

  Also note, that all web-elements related to a monitor will be deleted from the database if the monitors properties relating to the kind of content being extracted has been modified. Specifically the parameters: "javascript", "url", "url-range", "css-selector", "inner-css-selector", "filters", "text-css-selector" and "href-css-selector". This will also happen, if the general settings have been changed and you have not overwritten it in the specific monitor configuration. This is to avoid any confusion, otherwise web-monitor would potentially think that an element has disappeared and notify the user about it even though just the filter has changed and the element is simply being filtered now. The deletions are only really relevant if you want to be notified about content found on the first scan as it will then do a new first scan after the modification, or if you access the SQLite database directly.

* **account-details**
  - Currently only matrix is supported in terms of messengers needing accounts. You can always pass the output to a custom script instead, where you define account details if you want to use a different messenger. Specify a JSON object with name "matrix" that has the following keys:
    - "homeserver"
    - "username"
    - "password"
    - "room-id"

* **monitors**
  - "name": sets unique name for each monitor.
  - "url": url of website to scrape. Can also be an array of websites.
  - "url-range": array with 5 elements (of which only the first 2 are mandatory) in order: 
    - first part of the url.
    - second part of the url.
    - the number you want to increment from, which is in between the two parts. Defaults to 1.
    - the amount of tries it should make with a higher increment before giving up if no specified content is found, defaults to 5.
    - max amount of websites it should succesfully scrape, defaults to infinity.
    You don't need to specify url if you specify url-range and vice versa.
  - "css-selector": a JSON object with the keys: 
    - "tag": for example put "tag": "div" if the element is &lt;div&gt;.
    - "classes"
    - "ids"
    - "type"
    
    Fill it with the selector of the html elements which contain the content you are interested in. If there are multiple articles on a website, define the CSS selector with the attributes of the outermost element of each article, it will then get all articles which have this structure. "classes" and "ids" can be defined as strings or arrays.
  - "inner-css-selector": if the individual elements don't have distinct attributes but are in a table and have tags like &lt;li&gt; each, put the attributes for the table in "css-selector" and (in this case) "tag": "li" in "inner-css-selector". Input is specified in the same format as "css-selector".
  - "filters": a JSON object with elements:
    - "operator": can be defined as "contains", "!contains", "=", "!=", "<=", ">=", "<", ">".
    - "filter-value": can be a number or string.
    - "number-format": can be specified as "eur" or "eng" so the number format can be parsed properly (as , and . mean different things in both), "eng" is the default.
    - "text-css-selector" to specify where the text in the elmement is located that should be used to apply the filter to in order to decide if the entire html-elemnt should be selected. The input is defined in the same format as "css-selector". "text-css-selector" can be left out to apply the filter to the entire element.
  - "text-css-selector": can optionally be defined to filter which part of the text of the element should be displayed in the notifications. Input can be JSON object or array of JSON objects with format specified for "css-selector".
  - "href-css-selector": can optionally be defined to filter which links of the element should be displayed in the notifications. Input can be JSON object or array of JSON objects with format specified for "css-selector".
  - "active": bool which defaults to true. Can be useful if you want to keep the monitor for now but do not want to have it running.

### How to start it:
Start it with the included start-web-monitor.sh script or execute `lein run` if you use leiningen. The program does use the path relative to the user to access the needed files, it can be run from anywhere. This goes for both the jar file and the leininge project.
Web-monitor will parse the config before every scraping so it will automatically load updated settings without a restart needed, unless you want to modify the rss port number.

### Bugs

* When scraping with js loading enabled, there are some websites where the js code crashes. It seems like their JS relies on metadata that my scraper is not providing (yet).
