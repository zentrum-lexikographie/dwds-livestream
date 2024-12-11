(ns user
  (:require
   [clojure.tools.namespace.repl :as repl :refer [set-refresh-dirs]]
   [dwds.livestream.server :as server]))

(set-refresh-dirs "dev" "src")

(defn start!
  []
  (let [stop-metrics-reporter! (server/start-metrics-reporter!)
        stop-tailer!           (server/start-tailer!)
        stop-server!           (server/start-server!)]
    (fn [] (stop-server!) (stop-tailer!) (stop-metrics-reporter!))))

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
