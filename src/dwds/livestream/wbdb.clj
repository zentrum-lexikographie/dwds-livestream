(ns dwds.livestream.wbdb
  (:require
   [chime.core :as chime]
   [dwds.livestream.env :as env]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as jdbc.results]
   [taoensso.timbre :as log])
  (:import
   (java.time Duration Instant LocalDateTime ZonedDateTime ZoneId)
   (java.time.temporal ChronoUnit)))

(def lemma-query-stmt
  [(str "SELECT l.lemma, l.hidx, l.type as lemma_type, l.form_type, "
        "a.type as article_type, a.status, a.source, a.date "
        "from lemma l join article a on l.article_id = a.id")])

(def lemma-keys
  [:lemma :lemma-type :form-type :article-type :source :date])

(defn first-homograph
  [{hidx-a :hidx :as lemma-a} {hidx-b :hidx :as lemma-b}]
  (if (and lemma-a (<= (or hidx-a 0) (or hidx-b 0))) lemma-a lemma-b))

(defn lemmata->map
  [m {:keys [lemma hidx] :as record}]
  (let [record (cond-> (select-keys record lemma-keys) hidx (assoc :hidx hidx))
        record (update record :date #(subs (str %) 0 10))]
    (update m lemma first-homograph record)))

(defn query-lemmata
  []
  (->>
   (jdbc/execute! env/dwdswb
                  lemma-query-stmt
                  {:builder-fn jdbc.results/as-unqualified-kebab-maps
                   :fetch-size 1024})
   (reduce lemmata->map {})))

(defonce lemmata
  (atom {}))

(defn update-lemmata!
  [& _]
  (try
    (let [lemmata* (query-lemmata)]
      (reset! lemmata lemmata*)
      (log/infof "Retrieved %,d lemmata from DWDS-WB database" (count lemmata*)))
    (catch Throwable t
      (log/warn t "Error while updating lemmata"))))

(defn today-at-hour
  [hour]
  (ZonedDateTime/of
   (.. (LocalDateTime/now) (truncatedTo ChronoUnit/DAYS) (withHour hour))
   (ZoneId/systemDefault)))

(defn stop-lemmata-update!
  [^java.lang.AutoCloseable schedule]
  (.close schedule))

(defn start-lemmata-update!
  []
  (let [times    (->> (chime/periodic-seq (today-at-hour 2) (Duration/ofHours 12))
                      (chime/without-past-times)
                      (cons (Instant/now)))
        schedule (chime/chime-at times update-lemmata!)]
    (partial stop-lemmata-update! schedule)))
