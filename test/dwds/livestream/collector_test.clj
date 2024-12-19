(ns dwds.livestream.collector-test
  (:require
   [clojure.core.async :as a]
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

(def test-set-n
  25)

(deftest db-setup
  (with-open [container (postgres-container)]
    (let [db (container->db-spec container)]
      (collector/init-db! db)
      (as-> (a/chan (a/sliding-buffer 8192)) $
        (collector/retrieve-page-requests $ :n test-set-n)
        (collector/write-page-requests db $)
        (a/<!! $))
      (is (-> (jdbc/execute-one! db ["SELECT COUNT(*) AS cnt FROM wb_page_request"])
              (get :cnt) (= test-set-n))))))
