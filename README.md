# web-monitor

web-monitor is a universal web-scraper written in Clojure with a notification system attached. It is mostly meant to be applied to reoccurring elements with the same css to get notifications if a new article, video, product, etc. entry has been published. But it can also be used to monitor changes of one specific element of a website. Of course you can filter elements with different criteria which even involves accessing the page an element is linking to and applying the filter to elements there.

Receive said notifications via matrix, rss, std output, pipe it into your own script or simply read out the SQLite database.

## Installation

* Only works on Linux.

* The program is available as a jar in the web-monitor.tar file that you can find under releases, which also contains all the other relevant files to run it. This file can be found under "Releases". You will need Java 11 to run that jar file. Learn how to start it under **How to start it**.

* Or just clone the repo and use leiningen. But then it will be completely compiled everytime and there is no reason to get leiningen for it unless you want to modify the code.

* To scrape websites with JavaScript loading enabled, web-monitor also requires Firefox and a compatible Geckodriver version or Chrome and a compatible Chromedriver version. Ensure Geckodriver or chromedriver are executable in your system path.

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
  - "frequency": string which sets frequency of scraping. You can use a number, followed by ms, s, m, h or d to specify how long. You can also put multiple which will then be added. Like lets say a valid input would be "12m 10s".
  - "url-range-frequency": number which specifies how often the program will iterate over the url-range. 15 means it will iterate over the range every 15th time. See what a url-range is below.
  - "rss-port": sets port for rss feeds.
  - "browser": sets the browser used for scraping. "none" is the default. The other options are "firefox" and "chrome" if javascript should be loaded. Note that loading the js is by far the most resource intensive part in this program and could slow down already very busy and slow computers significantly for high frequency scrapes. Also note the dependencies in **Installation**.
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
  - "continuous": boolean which is false by default. Use it to define if the website should remain opened instead of being repeatedly opened and closed again. Great for high frequency scrapes. The frequency will still be the same as defined with the "frequency" parameter so make sure to adjust it appropriately for the monitor.
  - "url": url of website to scrape. Can also be an array of websites.
  - "url-file": filename of a txt file inside the folder resources/url_files which should contain urls separated by newlines.
  - "url-range": object with 3 elements. url-range can be used, if you want to iterate over multiple pages with each page having a incrementally higher number in the url:
    - "url-parts": an array of 3 elements: first part of the url, second part of the url and first index to start.
    - "tolerance": the amount of attempts it should make more before giving up after not finding anything. If no specified content is found, it defaults to 5.
    - "max-scrapes": max amount of websites it should succesfully scrape, defaults to infinity.
    You don't need to specify url if you specify url-range and vice versa.
  - "css-selector": a JSON object with the keys: 
    - "tag": for example put "tag": "div" if the element is &lt;div&gt;.
    - "classes"
    - "ids"
    - "type"
    
    Fill it with the selector of the html elements which contain the content you are interested in. If there are multiple articles on a website, define the CSS selector with the attributes of the outermost element of each article, it will then get all articles which have this structure. "classes" and "ids" can be defined as strings or arrays.
  - "inner-css-selector": if the individual elements don't have distinct attributes but are in a table and have tags like &lt;li&gt; each, put the attributes for the table in "css-selector" and (in this case) "tag": "li" in "inner-css-selector". Input is specified in the same format as "css-selector".
  - "filters": a JSON object of one of four types. With the exception of the custom-filter type, they all have the parameters: operator, filter-value, format (optional). Additionally each type has a different parameter from which web-monitor will automatically infer the type of the filter. Here are the types and the specific parameter they have:
    - "select": "text-css-selector"
    - "href-based": "href-filters"
    - "custom-filter": "script-name"
    - "general": none

    Here is the format and meaning of said parameters. 
    - "operator": can be defined as "contains", "!contains" ,"=", "!=", "<=", ">=", "<", ">" where "contains" and "!contains" are only available for strings but the other operations are available for every type including dates.
    - "filter-value": can be a number or string.
    - "format": if left out it will be string or number depending on the filter-value. If a different number format is required, it can be specified with "dsxtsy" where x is some symbol for the decimal seperator and y is some symbol for the thousand seperator, for example "ds,ts." would be the german way to write numbers. It can also be a date format like for example "yyyy-MM-dd" which will be extracted and parsed from the text (so the element can contain other text which will be ignored). Take a look at joda-time which web-monitor is using to see which formats are available and how to define them. The only thing which it can not parse are time-zones. At least in theory as this needs a lot more testing.
    - "text-css-selector": to specify where the text in the elmement is located that should be used to apply the filter to in order to decide if the entire html-elemnt should be selected. The input is defined in the same format as "css-selector". "text-css-selector" can be left out to apply the filter to the entire element.
    - "href-filters": will filter based on the website linked to via the web element. To use this, make sure that only one href-link is found by restricting the search with a href-css-selector. Specify on which part of the website the filter should be applied to with a css-selector object as used to define the monitors and then add the usual filter parameter objects. You can also recursively define yet another href-filter within this filter and so on.
    - "custom-filter": name of a executable file inside resources/filter_scripts which will have to take in a json object with parameters text which contains the text of the web content found, and hrefs which contains all the links separated by newlines. The output should be a map with the key "out", which must be either "true" for accepted or "false" for rejected/filtered. If you use echo in bash for the output, it will suffice. It is recommended to execute the filter script through the bash script (so provide the bash script name here) and then pipe the result into echo for ensured compatibility.
  - "text-css-selector": can optionally be defined to filter which part of the text of the element should be displayed in the notifications. Input can be JSON object or array of JSON objects with format specified for "css-selector".
  - "href-css-selector": can optionally be defined to filter which links of the element should be displayed in the notifications. Input can be JSON object or array of JSON objects with format specified for "css-selector".
  - "href-addition": optional, same as css-selector but meant to be applied to a page linked from the web-element. Meant to be applied to one element only, instead of repeating ones. Requires that only one href-link is found in association with the element, this can easily be achieved with "href-css-selector". This text is then appended to the text found in the original element, separated by three newlines. It is saved under content in the rss feeds. This can be useful for example if you want to be notified about a new article, and then want to read the content of the linked article inside your rss reader.
  - "active": bool which defaults to true. Can be useful if you want to keep the monitor for now but do not want to have it running.

### How to start it:
Start it with the included start-web-monitor.sh script or execute `lein run` if you use leiningen. The program does not use the path relative to the user to access the needed files, it can be run from anywhere. This goes for both the jar file and the leininge project.
Web-monitor will parse the config before every scraping so it will automatically load updated settings without a restart needed, unless you want to modify the rss port number. It will also automatically restart continuous monitors which were changed, with the new settings.

### Bugs

* When scraping with js loading enabled, there are some websites where the js code crashes. It seems like their JS relies on metadata that web-monitor is not providing (yet).
