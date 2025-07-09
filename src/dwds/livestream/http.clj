(ns dwds.livestream.http
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [dwds.livestream.env :as env]
   [dwds.livestream.html :as html]
   [dwds.livestream.metrics :as metrics]
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
   (java.util.concurrent Executors)
   (org.eclipse.jetty.io EofException)
   (org.eclipse.jetty.server Server)
   (org.eclipse.jetty.util.thread QueuedThreadPool)))

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

(def wb-page-broadcast-meter
  (metrics/meter "wb-page-broadcasts"))

(defn response-stream
  [jsonl->chunk broadcast-ch req]
  (let [epm (get-in req [:parameters :query :epm])]
    (reify StreamableResponseBody
      (write-body-to-stream [_body _response output-stream]
        (let [ch        (a/chan (a/sliding-buffer 1))
              throttled (cond-> ch epm (throttle-chan epm :minute))]
          (try
            (a/tap broadcast-ch ch)
            (with-open [writer (io/writer output-stream)]
              (loop []
                (when-let [msg (a/<!! throttled)]
                  (doto writer (.write ^String (jsonl->chunk msg)) (.flush))
                  (metrics/mark! wb-page-broadcast-meter)
                  (recur))))
            (catch EofException e
              (->> "Client closed connection while streaming WB page requests"
                   (log/debug e)))
            (catch Throwable t
              (log/warn t "Error while streaming WB page requests"))
            (finally
              (a/untap broadcast-ch ch))))))))

(def text-event-stream
  (partial response-stream #(str "data: " % "\n\n")))

(def jsonl-stream
  (partial response-stream #(str % "\n")))

(defn handle-stream
  [broadcast-ch stream-generator content-type req]
  (-> (resp/response (stream-generator broadcast-ch req))
      (resp/content-type content-type)
      (resp/header "Cache-Control" "no-cache")
      (resp/header "X-Accel-Buffering" "no")))

(defn stream-handler
  [broadcast-ch k generator content-type]
  {:name       k
   :handler    (partial handle-stream broadcast-ch generator content-type)
   :parameters {:query [:map [:epm {:optional true} [:and :int [:> 0]]]]}})

(def index-handler
  {:name    :index
   :handler (-> (resp/response html/index)
                (resp/content-type "text/html")
                (constantly))})

(defn ring-handler
  [broadcast-ch]
  (reitit.ring/ring-handler
   (reitit.ring/router
    [env/http-context-path handler-options
     [["/" index-handler]
      ["/api"
       ["/events" (stream-handler broadcast-ch :events text-event-stream
                                  "text/event-stream")]
       ["/jsonl"  (stream-handler broadcast-ch :jsonl jsonl-stream
                                  "text/jsonl")]]]])
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
  [broadcast-ch]
  (let [thread-pool (doto (QueuedThreadPool.)
                      (. (setVirtualThreadsExecutor
                          (Executors/newVirtualThreadPerTaskExecutor))))]
    (log/infof "Starting HTTP server @ %d/tcp" env/http-port)
    (->> {:port               env/http-port
          :thread-pool        thread-pool
          :output-buffer-size 1024
          :join?              false}
         (ring.adapter.jetty/run-jetty (ring-handler broadcast-ch))
         (partial stop-server!))))
