(ns dwds.livestream.collector-test
  (:require
   [clojure.test :refer [deftest is]]
   [dwds.livestream.collector :as collector]
   [next.jdbc :as jdbc])
  (:import
   (org.testcontainers.containers PostgreSQLContainer)
   (org.testcontainers.utility DockerImageName)))

(defn postgres-container
  []
  (doto (PostgreSQLContainer. (DockerImageName/parse "postgres:16-alpine"))
    (. (start))))

(defn container->db-spec
  [^PostgreSQLContainer container]
  {:dbtype   "postgresql"
   :jdbcUrl  (.getJdbcUrl container)
   :user     (.getUsername container)
   :password (.getPassword container)})

(def testset-limit
  25)

(defn count-wb-page-requests
  [db]
  (-> (jdbc/execute-one! db ["SELECT COUNT(*) AS cnt FROM wb_page_request"])
      (get :cnt)))

(deftest db-setup
  (with-open [container (postgres-container)
              db        (collector/get-db-connection (container->db-spec container))]
    (let [_     (collector/init-db! db)
          wait! (collector/collect! db :limit testset-limit)]
      (wait!)
      (is (= testset-limit (count-wb-page-requests db))))))
