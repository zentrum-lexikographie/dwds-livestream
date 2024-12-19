(ns dwds.livestream.metrics
  (:import
   (com.codahale.metrics Meter MetricRegistry Slf4jReporter Timer)
   (java.util.concurrent TimeUnit)))

(def ^MetricRegistry registry
  (MetricRegistry.))

(defn start-reporter!
  []
  (->>
   (doto (.build (Slf4jReporter/forRegistry registry))
     (.start 1 TimeUnit/MINUTES))
   (partial #(.close ^Slf4jReporter %))))

(defn meter
  [k]
  (.meter registry k))

(defn timer
  [k]
  (.timer registry k))

(defn mark!
  [^Meter meter]
  (.mark meter))

(defn timed!
  [^Timer timer]
  (.time timer))
