(ns dwds.livestream.collector
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [com.potetm.fusebox.retry :as retry :refer [delay-exp with-retry]]
   [dwds.livestream.env :as env]
   [dwds.livestream.metrics :as metrics]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as jdbc.sql]
   [ragtime.next-jdbc :as rg]
   [ragtime.repl :as rgr]
   [taoensso.timbre :as log])
  (:import
   (java.io IOException)
   (java.net URL)
   (java.sql SQLException)
   (java.time Instant LocalDate)))

(require 'next.jdbc.date-time)

(def migrations-table
  "migrations_dwds_livestream_collector")

(defn init-db!
  [db]
  (rgr/migrate
   {:datastore  (rg/sql-database db {:migrations-table migrations-table})
    :migrations (rg/load-resources "dwds/livestream/collector")
    :reporter   (fn [_ _op id] (log/debugf "%20s: Migrating" id))}))

(defn parse-json
  [s]
  (json/read-value s json/keyword-keys-object-mapper))

(def wb-page-requests
  (metrics/meter "wb-page-requests"))

(defn get-event-stream
  []
  (let [conn (. (URL. env/collector-source-url) (openConnection))]
    (doto conn
      (. (setConnectTimeout 5000))
      (. (setReadTimeout 5000))
      (. (setRequestProperty "User-Agent" "dwds-livestream-collector/0.0.0")))
    (io/reader (. conn (getInputStream)))))

(def page-requests-retrieval-retry
  (retry/init
   {::retry/retry? (fn [_n _ms ex]
                     (log/warnf ex "Error while retrieving events: retrying")
                     (instance? IOException ex))
    ::retry/delay  (fn [n _ms _ex] (min 60000 (delay-exp 3000 n)))}))

(defn retrieve-page-requests
  [ch & {:keys [limit]}]
  (a/thread
    (try
      (with-retry page-requests-retrieval-retry
        (let [ch-closed? (atom false)]
          (loop []
            (with-open [r (get-event-stream)]
              (loop [events (cond->> (line-seq r) limit (take limit))]
                (when-let [event (first events)]
                  (if (a/>!! ch (parse-json event))
                    (do (metrics/mark! wb-page-requests)
                        (recur (rest events)))
                    (reset! ch-closed? true)))))
            (when-not (or limit @ch-closed?)
              (recur)))))
      (catch Throwable t
        (log/fatal t "Error while retrieving events"))
      (finally
        (a/close! ch)))))

(def db-table
  :wb_page_request)

(def db-columns
  [:ts :lemma :article_type :article_source :article_date])

(defn event->db
  [{:keys [timestamp lemma hidx article-type source date]}]
  (let [lemma     (cond-> lemma hidx (str "#" hidx))
        timestamp (Instant/parse timestamp )
        date      (some-> date (LocalDate/parse))]
    (when (< (count lemma) 128)
      (list [timestamp lemma article-type source date]))))

(defn log-retried-write
  [_v e]
  (log/warnf e "Error while writing page requests: retrying"))

(def wb-page-request-tx-timer
  (metrics/timer "wb-page-request-txs"))

(def batch-size
  128)

(def page-request-write-retry
  (retry/init
   {::retry/retry? (fn [_n _ms ex]
                     (log/warnf ex "Error while writing page requests: retrying")
                     (instance? SQLException ex))
    ::retry/delay  (fn [n _ms _ex] (min 20000 (delay-exp 1000 n)))}))

(defn write-page-requests
  [db ch]
  (a/thread
    (try
      (let [batches (a/chan 1 (comp (mapcat event->db) (partition-all batch-size)))]
        (a/pipe ch batches)
        (with-retry page-request-write-retry
          (with-open [c (jdbc/get-connection db)]
            (loop []
              (when-let [page-requests (a/<!! batches)]
                (with-open [_ (metrics/timed! wb-page-request-tx-timer)]
                  (jdbc/with-transaction [tx c]
                    (jdbc.sql/insert-multi! tx db-table db-columns page-requests)))
                (recur))))))
      (catch Throwable t
        (log/fatal t "Error while writing page requests"))
      (finally
        (a/close! ch)))))

(defn start-collector!
  [db & {:keys [limit buf-size] :or {buf-size 8192}}]
  (let [ch   (a/chan (a/sliding-buffer buf-size))
        _    (retrieve-page-requests ch :limit limit)
        done (write-page-requests db ch)]
    (if limit #(a/<!! done) #(a/close! ch))))

(defn start!
  [& _]
  (let [_                      (init-db! env/lexdb)
        stop-metrics-reporter! (metrics/start-reporter!)
        stop-collector!        (start-collector! env/lexdb)]
    (env/register-shutdown-fn! stop-collector!)
    (env/register-shutdown-fn! stop-metrics-reporter!)
    @(promise)))
