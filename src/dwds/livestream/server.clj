(ns dwds.livestream.server
  (:require
   [clojure.core.async :as a]
   [dwds.livestream.access-log :as al]
   [dwds.livestream.env :as env]
   [dwds.livestream.http :as http]
   [dwds.livestream.metrics :as metrics]
   [dwds.livestream.wbdb :as wbdb]
   [jsonista.core :as json]
   [clojure.java.io :as io]))

(defn merge-wbdb-metadata
  [{:keys [lemma] :as wb-page-request}]
  (merge wb-page-request (@wbdb/lemmata lemma)))

(def wb-page-requests
  (a/chan (a/sliding-buffer 1)
          (comp
           (map merge-wbdb-metadata)
           (map json/write-value-as-string))))

(def wb-page-request-broadcast
  (a/mult wb-page-requests))

(defn start!
  [& _]
  (let [stop-metrics-reporter! (metrics/start-reporter!)
        stop-server!           (http/start-server! wb-page-request-broadcast)
        stop-lemmata-update!   (wbdb/start-lemmata-update!)
        stop-tailer!           (al/start-tailer! wb-page-requests)]
    (env/register-shutdown-fn! stop-tailer!)
    (env/register-shutdown-fn! stop-lemmata-update!)
    (env/register-shutdown-fn! stop-server!)
    (env/register-shutdown-fn! stop-metrics-reporter!)
    @(promise)))

(defn log->edn
  [& _]
  (wbdb/update-lemmata!)
  (binding [*print-length*   nil
            *print-dup*      nil
            *print-level*    nil
            *print-readably* true]
    (doseq [line (line-seq (io/reader *in*))
            wpr  (al/log-line->wb-page-requests line)]
      (pr (merge-wbdb-metadata wpr))
      (println)
      (flush))))
