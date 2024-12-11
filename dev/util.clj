(ns util
  (:require
   [dwds.livestream.env :refer [get-env]]
   [clojure.java.io :as io]))

(defn stream-lines!
  [& _]
  (doseq [line (line-seq (io/reader *in*))]
    (println line)
    (flush)
    (Thread/sleep (parse-long (get-env "LINE_DELAY" "1000")))))
