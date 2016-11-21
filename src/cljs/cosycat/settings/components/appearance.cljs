(ns cosycat.settings.components.appearance
  (:require [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [react-bootstrap.components :as bs]
            [cosycat.components :refer [dropdown-select]]
            [cosycat.settings.components.shared-components :refer [row-component]]
            [cosycat.backend.db :refer [default-settings]]
            [taoensso.timbre :as timbre]))

(def help-map
  {:avatar "Click to generate a new avatar with random seed"
   :delay "Set a time (in msec) to wait until notifications fade out"})

(defn on-mouse-over [target text-atom] (fn [e] (reset! text-atom (get help-map target))))

(defn on-mouse-out [text-atom] (fn [e] (reset! text-atom "")))

(defn on-click [v] (re-frame/dispatch [:set-settings [:notifications :delay] v]))

(defn get-default [path] (get-in (default-settings) path))

(defn appearance-controller []
  (let [help-text (reagent/atom "")]
    (fn []
      [row-component
       :label "Avatar"
       :controllers [bs/button
                     {:onClick #(re-frame/dispatch [:regenerate-avatar])
                      :on-mouse-over (on-mouse-over :avatar help-text)}
                     "Get new avatar"]
       :help-text help-text])))

(defn notification-controller []
  (let [notification-help (reagent/atom "")
        delay (re-frame/subscribe [:settings :notifications :delay])]
    (fn []
      [row-component
       :label "Notification Delay"
       :controllers [:div.btn-toolbar
                     [:div.input-group
                      {:style {:width "150px"}
                       :on-mouse-over (on-mouse-over :delay notification-help)
                       :on-mouse-out (on-mouse-out notification-help)}
                      [:span.input-group-btn
                       [:button.btn.btn-default
                        {:type "button"
                         :on-click #(on-click (max 0 (- @delay 250)))}
                        [bs/glyphicon {:glyph "minus"}]]]
                      [:span.form-control.input-number @delay]
                      [:span.input-group-btn
                       [:button.btn.btn-default
                        {:type "button"
                         :on-click #(on-click (+ @delay 250))}
                        [bs/glyphicon {:glyph "plus"}]]]]
                     [:button.btn.btn-default
                      {:type "button"
                       :on-click #(on-click (get-default [:notifications :delay]))}
                      "Set default"]]
       :help-text notification-help])))

(defn appearance-settings []
  [:div.container-fluid
   [appearance-controller]
   [notification-controller]])
