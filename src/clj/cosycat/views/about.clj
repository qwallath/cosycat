(ns cosycat.views.about
  (:require [hiccup.core :refer [html]]
            [cosycat.views.layout :refer [base]]
            [cosycat.views.imgs :refer [random-img]]))

(defn about-page [& {:keys [logged?] :or {:logged? false}}]
  (let [{href :href name :name} (random-img)]
    (base 
     {:left  [:div
              [:h2 "About page. "]
              [:h3 [:span.text-muted "This page was created so&so."]]
              [:p.lead "Some text block"]
              [:p.lead "Followed"]
              [:p.lead "By another"]]
      :right [:div.panel.panel-default
              [:div.panel-body {:style "text-align: center;"}
               [:img.circle {:src (str "img/" href) :alt name
                             :style "max-heigth: 100%; max-width: 100%;"}]]
              [:div.panel-footer {:style "text-align: center;"} name]]
      :logged? logged?})))
