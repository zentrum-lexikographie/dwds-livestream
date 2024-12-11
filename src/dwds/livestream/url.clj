(ns dwds.livestream.url
  (:require
   [lambdaisland.uri :as uri]
   [reitit.core]
   [ring.util.request]))

(defn url-by-name
  ([k {:reitit.core/keys [router] :as req}]
   (let [match (reitit.core/match-by-name router k)
         path  (reitit.core/match->path match)]
     (str (uri/join (ring.util.request/request-url req) path)))))

(def index
  (partial url-by-name :index))
