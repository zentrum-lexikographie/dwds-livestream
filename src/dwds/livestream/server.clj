(ns dwds.livestream.server
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [dwds.livestream.env :as env]
   [dwds.livestream.html :as html]
   [dwds.livestream.wbdb :as wbdb]
   [jsonista.core :as json]
   [lambdaisland.uri :as uri]
   [lambdaisland.uri.normalize :refer [percent-decode]]
   [muuntaja.core :as m]
   [reitit.coercion.malli]
   [reitit.ring]
   [reitit.ring.coercion]
   [reitit.ring.middleware.exception]
   [reitit.ring.middleware.muuntaja]
   [reitit.ring.middleware.parameters]
   [ring.core.protocols :refer [StreamableResponseBody]]
   [ring.util.response :as resp]
   [taoensso.timbre :as log]
   [throttler.core :refer [throttle-chan]])
  (:import
   (com.codahale.metrics Meter MetricRegistry Slf4jReporter)
   (java.time Instant)
   (java.time.format DateTimeFormatter)
   (java.util.concurrent TimeUnit)
   (org.apache.commons.io.input Tailer TailerListenerAdapter)
   (org.eclipse.jetty.io EofException)
   (org.eclipse.jetty.server Server)))

(def ^MetricRegistry metrics
  (MetricRegistry.))

(defn stop-metrics-reporter!
  [^Slf4jReporter reporter]
  (.stop reporter))

(defn start-metrics-reporter!
  []
  (->>
   (doto (.build (Slf4jReporter/forRegistry metrics))
     (.start 1 TimeUnit/MINUTES))
   (partial stop-metrics-reporter!)))

(def wb-page-requests
  (a/chan (a/sliding-buffer 1)))

(def wb-page-request-broadcast
  (a/mult wb-page-requests))

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
  (Instant/from (.parse ^DateTimeFormatter timestamp-formatter s)))

(defn parse-log-line
  [line]
  (let [log-line-match (re-find log-line-pattern line)]
    (-> (apply hash-map (interleave log-line-parts log-line-match))
        (update :timestamp parse-timestamp))))

(def bot-patterns
  (with-open [r (io/reader (io/resource "dwds/livestream/bot-patterns.txt"))]
    (re-pattern (str/join "|" (line-seq r)))))

(defn valid-wb?
  [entry]
  (and (seq entry) (not (str/starts-with? entry "["))))

(defn sub-wb?
  [[entry & tail :as _path]]
  (or (seq tail) (#{"dwb" "dwb2" "etymwb" "wdg" "index" "Wörterbuch"} entry)))

(defn bot?
  [{:keys [user-agent]}]
  (some? (re-find bot-patterns user-agent)))

(defn log-line->wb-page-request
  [log-line]
  (let [log-entry (parse-log-line log-line)]
    (when (= "200" (:status log-entry))
      (let [uri           (uri/uri (subs (:uri log-entry) uri-prefix-length))
            path          (str/split (or (uri :path) "") #"/")
            [wb :as path] (into [] (map percent-decode) path)]
        (when (and (valid-wb? wb) (not (sub-wb? path)) (not (bot? log-entry)))
          (-> (select-keys log-entry [:timestamp])
              (assoc :lemma wb)
              (merge (@wbdb/lemmata wb))
              (json/write-value-as-string)))))))

(def ^Meter wb-page-request-meter
  (.meter metrics "wb-page-requests"))

(defn dispatch-page-request
  [log-line]
  (try
    (when (wb-page-request? log-line)
      (when-let [wb-page-request (log-line->wb-page-request log-line)]
        (when (a/>!! wb-page-requests wb-page-request)
          (.mark wb-page-request-meter))))
    (catch Throwable t
      (log/debugf t "Error dispatching log line '%s'" log-line))))

(def ^Meter access-log-meter
  (.meter metrics "access-log"))

(def tailer-listener
  (let [access-log (str env/access-log)]
    (proxy [TailerListenerAdapter] []
      (fileNotFound []
        (log/debugf "File not found: %s" access-log))
      (fileRotated []
        (log/debugf "Rotated: %s" access-log))
      (handle [event]
        (if (instance? Throwable event)
          (log/warnf ^Throwable event "Error while tailing %s" access-log)
          (do
            (.mark access-log-meter)
            (dispatch-page-request ^String event)))))))

(defn stop-tailer!
  [^Tailer tailer]
  (.stop tailer))

(defn start-tailer!
  []
  (->> (Tailer/create env/access-log tailer-listener 1000 true)
       (partial stop-tailer!)))

(defn proxy-headers->request
  [{:keys [headers] :as request}]
  (let [scheme      (some->
                     (or (headers "x-forwarded-proto") (headers "x-scheme"))
                     (str/lower-case) (keyword) #{:http :https})
        remote-addr (some->>
                     (headers "x-forwarded-for") (re-find #"^[^,]*")
                     (str/trim) (not-empty))]
    (cond-> request
      scheme      (assoc :scheme scheme)
      remote-addr (assoc :remote-addr remote-addr))))

(def proxy-headers-middleware
  {:name ::proxy-headers
   :wrap (fn [handler]
           (fn
             ([request]
              (handler (proxy-headers->request request)))
             ([request respond raise]
              (handler (proxy-headers->request request) respond raise))))})

(defn log-exceptions
  [handler ^Throwable e request]
  (when-not (some-> e ex-data :type #{:reitit.ring/response})
    (log/warn e (.getMessage e)))
  (handler e request))

(def exception-middleware
  (-> reitit.ring.middleware.exception/default-handlers
      (assoc :reitit.ring.middleware.exception/wrap log-exceptions)
      (reitit.ring.middleware.exception/create-exception-middleware)))

(def handler-options
  {:muuntaja   m/instance
   :coercion   reitit.coercion.malli/coercion
   :middleware [proxy-headers-middleware
                reitit.ring.middleware.parameters/parameters-middleware
                reitit.ring.middleware.muuntaja/format-middleware
                exception-middleware
                reitit.ring.coercion/coerce-exceptions-middleware
                reitit.ring.coercion/coerce-request-middleware
                reitit.ring.coercion/coerce-response-middleware]})

(def ^Meter wb-page-broadcast-meter
  (.meter metrics "wb-page-broadcasts"))

(defn response-stream
  [jsonl->chunk req]
  (let [epm (get-in req [:parameters :query :epm])]
    (reify StreamableResponseBody
      (write-body-to-stream [_body _response output-stream]
        (let [ch        (a/chan (a/sliding-buffer 1))
              throttled (cond-> ch epm (throttle-chan epm :minute))]
          (try
            (a/tap wb-page-request-broadcast ch)
            (with-open [writer (io/writer output-stream)]
              (loop []
                (when-let [msg (a/<!! throttled)]
                  (doto writer (.write ^String (jsonl->chunk msg)) (.flush))
                  (.mark wb-page-broadcast-meter)
                  (recur))))
            (catch EofException e
              (->> "Client closed connection while streaming WB page requests"
                   (log/debug e)))
            (catch Throwable t
              (log/warn t "Error while streaming WB page requests"))
            (finally
              (a/untap wb-page-request-broadcast ch))))))))

(def text-event-stream
  (partial response-stream #(str "data: " % "\n\n")))

(def jsonl-stream
  (partial response-stream #(str % "\n")))

(defn handle-stream
  [stream-generator content-type req]
  (-> (resp/response (stream-generator req)) (resp/content-type content-type)))

(defn stream-handler
  [k generator content-type]
  {:name       k
   :handler    (partial handle-stream generator content-type)
   :parameters {:query [:map [:epm {:optional true} [:and :int [:> 0]]]]}})

(def index-handler
  {:name    :index
   :handler (-> (resp/response html/index)
                (resp/content-type "text/html")
                (constantly))})

(def ring-handler
  (reitit.ring/ring-handler
   (reitit.ring/router
    [env/http-context-path handler-options
     [["/" index-handler]
      ["/api"
       ["/events" (stream-handler :events text-event-stream "text/event-stream")]
       ["/jsonl"  (stream-handler :jsonl jsonl-stream "text/jsonl")]]]])
   (reitit.ring/routes
    (reitit.ring/redirect-trailing-slash-handler)
    (reitit.ring/create-file-handler {:path env/http-context-path})
    (reitit.ring/create-default-handler))))

(defn stop-server!
  [^Server server]
  (.stop server)
  (.join server))

(require 'ring.adapter.jetty)

(defn start-server!
  []
  (log/infof "Starting HTTP server @ %d/tcp" env/http-port)
  (->> {:port               env/http-port
        :output-buffer-size 1024
        :join?              false}
       (ring.adapter.jetty/run-jetty ring-handler)
       (partial stop-server!)))

(defn register-shutdown-fn!
  [fn]
  (.. (Runtime/getRuntime) (addShutdownHook (Thread. ^Runnable fn))))

(defn start!
  [& _]
  (let [stop-metrics-reporter! (start-metrics-reporter!)
        stop-server!           (start-server!)
        stop-lemmata-update!   (wbdb/start-lemmata-update!)
        stop-tailer!           (start-tailer!)]
    (register-shutdown-fn! stop-tailer!)
    (register-shutdown-fn! stop-lemmata-update!)
    (register-shutdown-fn! stop-server!)
    (register-shutdown-fn! stop-metrics-reporter!)
    @(promise)))
