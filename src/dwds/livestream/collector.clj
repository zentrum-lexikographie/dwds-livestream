(ns dwds.livestream.collector
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [diehard.core :as dh]
   [dwds.livestream.env :as env]
   [hato.client :as hc]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as jdbc.sql]
   [ragtime.next-jdbc]
   [ragtime.repl]
   [taoensso.timbre :as log])
  (:import
   (java.time Instant LocalDate)))

(require 'next.jdbc.date-time)

(defn init-db!
  [db]
  (ragtime.repl/migrate
   {:datastore  (ragtime.next-jdbc/sql-database db)
    :migrations (ragtime.next-jdbc/load-resources "dwds/livestream/collector")
    :reporter   (fn [_ _op id] (log/infof "%20s: Migrating" id))}))

(defn parse-json
  [s]
  (json/read-value s json/keyword-keys-object-mapper))

(defn log-retried-retrieval
  [_v e]
  (log/warnf e "Error while retrieving events: retrying"))

(defn retrieve-page-requests
  [ch & {:keys [n]}]
  (a/thread
    (try
      (dh/with-retry {:retry-on   Exception
                      :backoff-ms [3000 60000 2.0]
                      :on-retry   log-retried-retrieval}
        (let [response (hc/get env/collector-source-url {:as :stream})]
          (with-open [r (io/reader (response :body))]
            (loop [events (cond->> (line-seq r) n (take n))]
              (when-let [event (first events)]
                (when (a/>!! ch (parse-json event))
                  (recur (rest events))))))))
      (catch Throwable t
        (log/fatal t "Error while retrieving events"))
      (finally
        (a/close! ch))))
  ch)

(def db-table
  :wb_page_request)

(def db-columns
  [:ts :lemma :article_type :article_source :article_date])

(defn event->db
  [{:keys [timestamp lemma hidx article-type source date]}]
  (let [timestamp (Instant/parse timestamp )
        date      (some-> date (LocalDate/parse))]
    [timestamp (cond-> lemma hidx (str "#" hidx)) article-type source date]))


(defn log-retried-write
  [_v e]
  (log/warnf e "Error while writing page requests: retrying"))

(defn write-page-requests
  [db ch]
  (a/thread
    (try
      (let [batches (a/chan 1 (comp (map event->db) (partition-all 100)))]
        (a/pipe ch batches)
        (dh/with-retry {:retry-on   Exception
                        :backoff-ms [1000 20000 2.0]
                        :on-retry   log-retried-write}
          (with-open [c (jdbc/get-connection db)]
            (loop []
              (when-let [page-requests (a/<!! batches)]
                (jdbc.sql/insert-multi! c db-table db-columns page-requests)
                (recur))))))
      (catch Throwable t
        (log/fatal t "Error while writing page requests"))
      (finally
        (a/close! ch)))))
