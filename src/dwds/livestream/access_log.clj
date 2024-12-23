(ns dwds.livestream.access-log
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [dwds.livestream.env :as env]
   [dwds.livestream.metrics :as metrics]
   [lambdaisland.uri :as uri]
   [lambdaisland.uri.normalize :refer [percent-decode]]
   [taoensso.timbre :as log])
  (:import
   (java.time Instant)
   (java.time.format DateTimeFormatter)
   (org.apache.commons.io.input Tailer TailerListenerAdapter)))

(def uri-prefix
  "/wb/")

(def uri-prefix-length
  (count uri-prefix))

(def wb-http-request-prefix
  (str "GET " uri-prefix))

(def wb-typeahead-http-request-prefix
  (str wb-http-request-prefix "typeahead"))

(defn wb-page-request?
  [log-line]
  (and (str/includes? log-line wb-http-request-prefix)
       (not (str/includes? log-line wb-typeahead-http-request-prefix))))

(def log-line-parts
  [:line :ip :timestamp :method :uri :status :size :referrer :user-agent])

(def log-line-pattern
  #"(?x)
    (\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})?\s # Remote-IP
    -\s
    -\s
    \[(.*)\]\s                              # timestamp
    \"(\w+)\s([^\s]+)[^\"]*\"\s             # method and request URI
    (\d{3})\s                               # status code
    (\d+)\s                                 # response size
    \"([^\"]*)\"\s                          # referrer
    \"([^\"]*)\".*                          # user-agent
    ")

(def timestamp-formatter
  (DateTimeFormatter/ofPattern "dd/MMM/yyyy:HH:mm:ss Z"))

(defn parse-timestamp
  [^String s]
  (str (Instant/from (.parse ^DateTimeFormatter timestamp-formatter s))))

(defn parse-log-line
  [line]
  (let [log-line-match (re-find log-line-pattern line)]
    (-> (apply hash-map (interleave log-line-parts log-line-match))
        (update :timestamp parse-timestamp))))

(def bot-patterns
  (with-open [r (io/reader (io/resource "dwds/livestream/bot-patterns.txt"))]
    (re-pattern (str/join "|" (line-seq r)))))

(defn valid-lemma?
  [entry]
  (and (seq entry) (not (str/starts-with? entry "["))))

(defn sub-wb?
  [[entry & tail :as _path]]
  (or (seq tail) (#{"dwb" "dwb2" "etymwb" "wdg" "index" "Wörterbuch"} entry)))

(defn bot?
  [user-agent]
  (some? (re-find bot-patterns user-agent)))

(defn log-line->wb-page-requests
  [log-line]
  (try
    (when (wb-page-request? log-line)
      (let [{:keys [status uri user-agent timestamp]} (parse-log-line log-line)]
        (when (= "200" status)
          (let [uri              (uri/uri (subs uri uri-prefix-length))
                path             (str/split (or (uri :path) "") #"/")
                [lemma :as path] (into [] (map percent-decode) path)]
            (when (and (valid-lemma? lemma)
                       (not (sub-wb? path))
                       (not (bot? user-agent)))
              (list {:timestamp timestamp
                     :lemma     lemma}))))))
    (catch Throwable t
      (log/debugf t "Error parsing access log line '%s'" log-line))))

(def access-log-meter
  (metrics/meter "access-log"))

(def wb-page-request-meter
  (metrics/meter "wb-page-requests"))

(defn start-tailer!
  [ch]
  (let [access-log (str env/access-log)]
    (->> (Tailer/create
          access-log
          (proxy [TailerListenerAdapter] []
            (handle [event]
              (if (instance? Throwable event)
                (log/warnf ^Throwable event "Error while tailing %s" access-log)
                (let [log-line event]
                  (try
                    (metrics/mark! access-log-meter)
                    (when (wb-page-request? log-line)
                      (doseq [wpr (log-line->wb-page-requests log-line)]
                        (when (a/>!! ch wpr)
                          (metrics/mark! wb-page-request-meter))))
                    (catch Throwable t
                      (log/debugf t "Error dispatching log line '%s'" log-line))))))
            (fileNotFound []
              (log/debugf "File not found: %s" access-log))
            (fileRotated []
              (log/debugf "Rotated: %s" access-log)))
          1000
          true)
         (partial #(.stop ^Tailer %)))))

