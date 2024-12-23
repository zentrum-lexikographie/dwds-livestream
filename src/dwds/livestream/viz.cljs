(ns dwds.livestream.viz
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            ["d3" :as d3]))

(def events-per-minute
  45)

(def min-size
  125)

(def margin
  25)

(def display-parallel
  7)

(def show-sources?
  (str/includes? (.-search js/location) "?sources"))

(def wb-page-requests-xf
  (comp
   (filter :article-type)
   (map-indexed (fn [n v] (assoc v :n n)))))

(def wb-page-requests
  (a/chan (a/sliding-buffer 1) wb-page-requests-xf))

(def wb-page-request-broadcast
  (a/mult wb-page-requests))

(defn listen-to-page-requests
  [callback-fn]
  (let [ch (a/chan)]
    (a/tap wb-page-request-broadcast ch)
    (a/go-loop []
      (when-let [page-request (a/<! ch)]
        (try
          (callback-fn page-request)
          (catch js/Error e (js/console.log e)))
        (recur)))))

(def events
  (atom nil))

(def events-url
  (str (.-cp js/window) "api/events?epm=" events-per-minute))

(defn ^:dev/before-load stop-event-retrieval!
  []
  (some-> @events (.close))
  (reset! events nil))

(defn event->wb-page-requests
  [event]
  (->> (-> (.-data event) (js/JSON.parse) (js->clj :keywordize-keys true))
       (a/put! wb-page-requests)))

(defn reload-after-error
  []
  (.. (d3/select ".network-error") (attr "style" ""))
  (js/window.setTimeout #(js/location.reload) 10000))

(defn start-event-retrieval!
  []
  (stop-event-retrieval!)
  (reset! events (js/EventSource. events-url))
  (set! (.-onmessage @events) event->wb-page-requests)
  (set! (.-onerror @events) reload-after-error))

(def ^:dev/after-load start-logging!
  (partial listen-to-page-requests (comp js/console.log str)))

(def main
  (d3/select "main"))

(def svg
  (d3/select "svg"))

(def display-duration
  (* (/ events-per-minute 60) 1000 display-parallel))

(def full-article?
  #{"Vollartikel"})

(def norm-source
  (memoize (fn [s] (str/replace s #"Duden_1999" "Duden"))))

(defn desc
  [{:keys [source date]}]
  (str (norm-source source) " – " (subs date 0 4)))

(defn render-page-request
  [{:keys [article-type lemma] :as entry}]
  (let [bbox   (.. main (node) (getBoundingClientRect))
        width  (.-width bbox)
        height (.-height bbox)
        size   (max min-size (/ (min width height) 20))
        x      (+ (* (rand) (- width (* 2 size))) size margin)
        y      (+ (* (rand) (- height (* 2 size))) size margin)
        g      (.. svg
                   (append "g")
                   (attr "transform" (str "translate(" x "," y ")"))
                   (style "opacity" 1))
        full?  (full-article? article-type)
        dd     (cond-> display-duration full? (* 1.5))]
    (.. g
        (append "circle")
        (classed (if full? "full" "min") true)
        (attr "r" (+ size margin))
        (transition)
        (attr "r" (+ size margin margin))
        (style "opacity" 0)
        (ease js/Math.sqrt)
        (duration dd)
        (remove))
    (let [href  (str "https://www.dwds.de/wb/" (js/encodeURIComponent lemma))
          a (.. g (append "a")
                (attr "xlink:href" href)
                (attr "target" "_blank"))]
      (.. a
          (append "circle")
          (attr "r" size)
          (transition)
          (duration (* 2 dd))
          (style "opacity" 0)
          (ease js/Math.sqrt)
          (duration (* 2 dd))
          (on "end" (fn [] (. g (remove))))
          (remove))
      (let [label (.. a (append "text") (attr "text-anchor" "middle"))]
        (.. label
            (transition)
            (delay 1000)
            (style "opacity" 0)
            (duration (- (* 2 dd) 1000))
            (remove))
        (.. label
            (append "tspan")
            (classed "lemma" true)
            (text lemma))
        (when show-sources?
          (.. label
              (append "tspan")
              (classed "source" true)
              (attr "x" "0")
              (attr "dy" "1.2em")
              (text (desc entry))))))))

(def start-rendering!
  (partial listen-to-page-requests render-page-request))

(defn register-visibility-listener!
  []
  (js/document.addEventListener
   "visibilitychange"
   (fn []
     (if (.-hidden js/document)
       (stop-event-retrieval!)
       (start-event-retrieval!)))))

(defn ^:dev/after-load start!
  []
  (register-visibility-listener!)
  (start-rendering!)
  (start-event-retrieval!))
