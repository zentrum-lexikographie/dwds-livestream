(ns ^{:ornament/prefix ""} dwds.livestream.html
  {:clj-kondo/config '{:linters {:unresolved-symbol {:level :off}}}}
  (:require
   [clojure.java.io :as io]
   [dwds.livestream.env :as env]
   [lambdaisland.hiccup :as h]
   [lambdaisland.ornament :as o :refer [defstyled]]))

(def cp
  (str env/http-context-path "/"))

(def title
  "DWDS – Digitales Wörterbuch der deutschen Sprache")

(defstyled page :html
  :h-full :w-full
  [:body :h-full :w-full
   [:main :h-full :w-full :bg-black :font-dwds-sans
    [:circle :stroke-none :fill-dwds-white]
    [:circle.full :fill-dwds-blue]
    [:circle.min :fill-dwds-darkblue]
    [:text :fill-black :text-lg]
    [:tspan.lemma :font-bold :text-xl]
    [:tspan.source :fill-dwds-grey-1]]
   [:header :absolute :top-0 :left-0 :p-4]
   [:footer :text-dwds-white
    [:p.info :absolute :bottom-0 :left-0 :p-6 :text-4xl :hidden]
    [:p.network-error :absolute :bottom-0 :right-0 :p-6 :w-fit :font-bold]]]
  ([page-title & contents]
   [:<> {:lang "de"}
    [:head
     [:meta {:charset "UTF-8"}]
     [:meta {:name "viewport", :content "width=device-width, initial-scale=1.0"}]
     [:link {:rel "stylesheet" :href (str cp "fonts.css")}]
     [:link {:rel "stylesheet" :href (str cp "fontawesome.min.css")}]
     [:link {:rel "stylesheet" :href (str cp "styles.css")}]
     [:title (str title " – " page-title)]]
    [:body
     [:header [:a {:href "https://www.dwds.de/"} [:img {:src (str cp "d.png")}]]]
     [:main contents]
     [:footer
      [:p.info [:a {:href ""} [:i.fa-solid.fa-question]]]
      [:p.network-error {:style "display: none"}
       "Verbindung unterbrochen! Lade in Kürze neu…"]]
     [:script (str "var cp = \"" cp "\";")]
     [:script {:src (str cp "js/main.js")}]]]))

(def index
  (h/render
   [page "Livestream"
    [:svg {:width "100%" :height "100%"}
     #_(for [n (range 10)]
       [:g {:transform (format "translate(%s,%s)" (rand-int 1000) (rand-int 1000))}
        [:circle {:class (if (odd? n) "full" "min") :r "140"}]
        [:circle {:r "100"}]
        [:text {:text-anchor "middle"}
         [:tspan.lemma "Achtung"]
         [:tspan.source {:x "0" :dy "1.2em"} "ZDL/DWDS"]]])]]))

(o/set-tokens! {:tw-version 3
                :fonts      {:dwds-sans  "Source Sans Pro"
                             :dwds-serif "Crimson Text"}
                :colors     {:dwds-white    "f6f6f6"
                             :dwds-grey-1   "666666"
                             :dwds-grey-2   "777777"
                             :dwds-grey-3   "e7e7e7"
                             :dwds-blue     "0087C2"
                             :dwds-darkblue "004A6B"
                             :dwds-green    "559955"
                             :dwds-red      "c9302c"
                             :dwds-lightred "F7D1BC"}})

(try
  (let [css (o/defined-styles {:preflight? true})]
    (spit (doto (io/file "public" "styles.css") (io/make-parents)) css))
  (catch java.io.FileNotFoundException _))
