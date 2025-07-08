(ns dwds.livestream.env
  (:require
   [babashka.fs :as fs]
   [taoensso.timbre :as log])
  (:import
   (io.github.cdimascio.dotenv Dotenv)))

(def ^Dotenv dot-env
  (.. Dotenv (configure) (ignoreIfMissing) (load)))

(defn get-env
  ([k]
   (get-env k nil))
  ([^String k df]
   (or (System/getenv k) (.get dot-env k) df)))

(def debug?
  (some? (not-empty (get-env "DEBUG"))))

(def http-port
  (parse-long (get-env "DWDS_LIVESTREAM_HTTP_PORT" "3005")))

(def http-context-path
  (get-env "DWDS_LIVESTREAM_HTTP_CONTEXT_PATH" ""))

(def access-log
  (fs/file (get-env "DWDS_LIVESTREAM_ACCESS_LOG" "/log/dwds.de_access.log")))

(def dwdswb
  {:dbtype   "mysql"
   :host     (get-env "DWDS_LIVESTREAM_WBDB_HOST" "localhost")
   :dbname   (get-env "DWDS_LIVESTREAM_WBDB_NAME" "dwdswb")
   :user     (get-env "DWDS_LIVESTREAM_WBDB_USER" "dwdswb")
   :password (get-env "DWDS_LIVESTREAM_WBDB_PASSWORD" "dwdswb")})

(def lexdb
  {:dbtype   "postgresql"
   :host     (get-env "DWDS_LIVESTREAM_LEXDB_HOST" "localhost")
   :dbname   (get-env "DWDS_LIVESTREAM_LEXDB_NAME" "lexdb")
   :user     (get-env "DWDS_LIVESTREAM_LEXDB_USER" "lexdb")
   :password (get-env "DWDS_LIVESTREAM_LEXDB_PASSWORD" "lexdb")})

(def collector-source-url
  (get-env "DWDS_LIVESTREAM_COLLECTOR_SOURCE_URL"
           "https://www.dwds.de/live/api/jsonl"))

(defn register-shutdown-fn!
  [fn]
  (.. (Runtime/getRuntime) (addShutdownHook (Thread. ^Runnable fn))))

(log/handle-uncaught-jvm-exceptions!)
(log/merge-config!
 {:min-level [["org.eclipse.jetty.*" :warn]
              ["dwds.*" (if debug? :debug :info)]
              ["*" :info]]
  :appenders {:println (log/println-appender {:stream :std-err})}})
