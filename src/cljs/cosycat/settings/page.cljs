(ns cosycat.settings.page
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [react-bootstrap.components :as bs]
            [cosycat.settings.components.query-settings :refer [query-settings]]
            [cosycat.settings.components.notification-settings :refer [notification-settings]]
            [cosycat.settings.components.appearance-settings :refer [appearance-settings]]
            [taoensso.timbre :as timbre]))

(defn tabs [active-tab expanded?]
  (fn [active-tab expanded?]
    [bs/nav
     {:bsStyle "tabs"
      :active-key @active-tab
      :on-select #(reset! active-tab (keyword %))}
     [bs/nav-item {:event-key :query}
      [:span {:style {:font-weight "bold"}} "Query Settings"]]
     [bs/nav-item {:event-key :notification}
      [:span {:style {:font-weight "bold"}} "Notification Settings"]]
     [bs/nav-item {:event-key :appearance}
      [:span {:style {:font-weight "bold"}} "Appearance Settings"]]
     [:span.pull-right
      {:style {:cursor "pointer"}
       :on-click #(swap! expanded? not)}
      [bs/glyphicon {:glyph (if @expanded? "resize-small" "resize-full")}]]]))

(defmulti tab-panel identity)
(defmethod tab-panel :query [] [query-settings])
(defmethod tab-panel :notification [] [notification-settings])
(defmethod tab-panel :appearance [] [appearance-settings])

(defmulti get-update-map (fn [active-tab _] active-tab))
(defmethod get-update-map :query
  [_ settings]
  (timbre/debug settings)
  (let [{{:keys [query-opts snippet-opts]} :query} settings]
    {:query {:query-opts {:context (query-opts :context)
                          :page-size (query-opts :page-size)}
             :snippet-opts {:snippet-size (snippet-opts :snippet-size)
                            :snippet-delta (snippet-opts :snippet-delta)}}}))
(defmethod get-update-map :notification
  [_ settings]
  {:notifications {:delay (get-in settings [:notifications :delay])}})

(defmethod get-update-map :appearance
  [_ _]
  {})

(defn display-setting-submit [active-tab]
  (case active-tab
    :appearance false
    true))

(defn settings-panel []
  (let [active-tab (reagent/atom :query)
        expanded? (reagent/atom true)
        settings (re-frame/subscribe [:settings])]
    (fn []
      [:div
       {:class (if @expanded? "container-fluid" "container")}
       [bs/panel {:header (reagent/as-component [tabs active-tab expanded?])}
        [:div.container-fluid [tab-panel @active-tab]]
        (when (display-setting-submit @active-tab)
          [bs/button
           {:onClick #(re-frame/dispatch [:submit-settings (get-update-map @active-tab @settings)])
            :class "pull-right"
            :bsStyle "info"}
           "Save settings"])]])))