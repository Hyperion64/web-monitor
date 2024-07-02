# web-monitor

Web-monitor is a clojure-based tool designed to scrape websites and notify users if changes occur in elements specified through their html tags. Currently rss and the matrix protocol are available (more messengers will hopefully be available in the future).

## Installation

* It requires leiningen to run, leiningen will automatically install all libraries needed when starting the program. Leiningen requires the java-jdk.

* It also requires firefox if you want to scrape websites with javascript loading enabled.

* Do `git clone https://github.com/Hyperion64/web-monitor` to download the program.

## Usage

### Setting up config.json:
* **settings**  
  - frequency\_minutes: set frequency of scraping.  
  - rss\_port: set port for rss readers.  
  - javascript: "all" enables js for all, "manual" requires to set it to true or false in each individual monitor and "none" disables it for all monitors.  
  - js\_load\_time\_seconds: set how long the js has time to load for each scraping.  

* **messengers**  
  - Currently only matrix is supported. Find the room id in room settings.  

* **monitors**  
  - name: set unique name for each monitor.  
  - url: url of website to scrape.  
  - element: add the opening tag that is defining each element like for example &lt;div class=\\&quot;example-class1 example-class2\\&quot; id=\\&quot;example-id\\&quot;&gt; if each of the html elements you want to monitor is starting with that. Of course, make sure that no other element is starting with that opening tag and that it only contains what you care about. Remember to escape the " symbols like this: \\&quot;. web-monitor will only use tag, id and class to filter the html but adding other things like style doesn't cause any issues.  
  There are two examples in the config.json file.  
  - inner\_element: if the individual elements don't have distinct selectors but are in a table and have tags like &lt;li&gt; each, put the selectors for the table in element and (in this case) &lt;li&gt; in inner\_element.  
  - javascript: see javascript in settings, set true or false.  
  - messenger: only one at a time is supported and only "matrix" and "rss".    

### Receiving messages with rss:
Just provide the link: http://localhost:8080/Example-monitor-name.xml to your rss reader with the localhost number being the port number specified in the settings.

### Running it:
Do `lein run` in the web-monitor directory and it will start. Obviously consider putting that in autostart.   
It will parse the config before every scraping so it will automatically load updated settings.

### Example config:
Note that the websites used in the config example can obviously change their tags and general structure anytime. So if the program does not find any content with the example config, thats why.  

## Todo

### Bugs

* Geckodriver fails on some machines, making the program crash when scraping with js loading enabled.

* When scraping with js loading enabled, there are some websites where the js code crashes. It seems like their code relies on data that the selenium scraper is not providing.

### Features

* Test using Xpath alongside opening tags for more robustness and specificity. Xpath alone is not sufficient for the functionality web-monitor aims to provide.

* Add more messengers like email, telegram, etc..

* Store links found in href and provide them in the messages.
