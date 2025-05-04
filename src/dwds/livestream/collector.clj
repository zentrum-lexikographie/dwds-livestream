(ns dwds.livestream.collector
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [diehard.core :as dh]
   [dwds.livestream.env :as env]
   [dwds.livestream.metrics :as metrics]
   [clj-http.client :as hc]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as jdbc.sql]
   [ragtime.next-jdbc :as rg]
   [ragtime.repl :as rgr]
   [taoensso.timbre :as log])
  (:import
   (java.io IOException)
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

(defn log-retried-retrieval
  [_v e]
  (log/warnf e "Error while retrieving events: retrying"))

(def wb-page-requests
  (metrics/meter "wb-page-requests"))

(defn get-event-stream
  []
  (->> {:as :stream :socket-timeout 5000}
       (hc/get env/collector-source-url)
       (:body)))

(defn retrieve-page-requests
  [ch & {:keys [limit]}]
  (a/thread
    (try
      (dh/with-retry {:retry-on   IOException
                      :backoff-ms [3000 60000 2.0]
                      :on-retry   log-retried-retrieval}
        (let [ch-closed? (atom false)]
          (loop []
            (with-open [r (io/reader (get-event-stream))]
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

(defn write-page-requests
  [db ch]
  (a/thread
    (try
      (let [batches (a/chan 1 (comp (mapcat event->db) (partition-all batch-size)))]
        (a/pipe ch batches)
        (dh/with-retry {:retry-on   SQLException
                        :backoff-ms [1000 20000 2.0]
                        :on-retry   log-retried-write}
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
