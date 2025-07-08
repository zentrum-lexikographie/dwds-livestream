(ns dwds.livestream.collector-test
  (:require
   [clojure.java.process :as p]
   [clojure.test :refer [deftest is use-fixtures]]
   [com.potetm.fusebox.retry :as retry :refer [with-retry delay-exp]]
   [dwds.livestream.collector :as collector]
   [next.jdbc :as jdbc]
   [dwds.livestream.env :as env]))

(def db-retry
  (retry/init {::retry/retry? (fn [n _ms _ex] (< n 20))
               ::retry/delay  (fn [n _ms _ex] (min (delay-exp 1000 n) 10000))}))

(defn wait-for-db!
  []
  (with-retry db-retry
    (jdbc/execute! env/lexdb ["SELECT 1+1 AS n"])))

(defn start-db!
  []
  (p/exec "docker" "compose" "--progress" "quiet" "up" "db" "-d"))

(defn stop-db!
  []
  (p/exec "docker" "compose" "--progress" "quiet" "down" "db"))

(defn db
  [f]
  (start-db!) (try (wait-for-db!) (f) (finally (stop-db!))))

(use-fixtures :once db)

(def testset-limit
  25)

(defn count-wb-page-requests
  []
  (-> (jdbc/execute-one! env/lexdb ["SELECT COUNT(*) AS cnt FROM wb_page_request"])
      (get :cnt)))

(deftest db-setup
  (let [_     (collector/init-db! env/lexdb)
        wait! (collector/start-collector! env/lexdb :limit testset-limit)]
    (wait!)
    (is (<= testset-limit (count-wb-page-requests)))))
