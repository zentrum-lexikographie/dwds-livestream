(ns user
  (:require
   [clojure.tools.namespace.repl :as repl :refer [set-refresh-dirs]]
   [dwds.livestream.http :as http]
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [taoensso.timbre :as log]
   [jsonista.core :as json])
  (:import
   (java.util.zip GZIPInputStream)))

(set-refresh-dirs "dev" "src")

(defn stream-sample-wb-page-requests
  [ch]
  (a/thread
    (try
      (with-open [r (->> (io/resource "wb-page-requests.edn.gz")
                         (io/input-stream) GZIPInputStream. (io/reader))]
        (loop [lines (cycle (line-seq r))]
          (when-let [line (first lines)]
            (when (a/>!! ch (json/write-value-as-string (read-string line)))
              (Thread/sleep 1000)
              (recur (rest lines))))))
      (catch Throwable t
        (log/fatal t "Error while streaming sample page requests")))))

(defn start!
  []
  (let [ch           (a/chan)
        stop-server! (http/start-server! (a/mult ch))]
    (stream-sample-wb-page-requests ch)
    (fn [] (a/close! ch) (stop-server!))))

(def stop!
  nil)

(defn go
  []
  (alter-var-root #'stop! (constantly (start!))))

(defn halt
  []
  (when stop!
    (stop!)
    (alter-var-root #'stop! (constantly nil))))

(defn reset
  []
  (halt)
  (repl/refresh :after 'user/go))

(defn reset-all
  []
  (halt)
  (repl/refresh-all :after 'user/go))

(comment
  (go)
  (halt)
  (reset)
  (reset-all))
