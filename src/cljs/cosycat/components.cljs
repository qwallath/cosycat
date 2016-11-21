(ns cosycat.components
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [cosycat.roles :refer [project-user-roles-descs]]
            [cosycat.utils :refer [color-codes date-str->locale parse-time human-time]]
            [cosycat.app-utils :refer [deep-merge dekeyword]]
            [cosycat.localstorage :refer [fetch-db get-backups]]
            [taoensso.timbre :as timbre]
            [goog.string :as gstr]
            [react-bootstrap.components :as bs]))

(def css-transition-group
  (reagent/adapt-react-class js/React.addons.CSSTransitionGroup))

(def transition-group
  (reagent/adapt-react-class js/React.addons.TransitionGroup))

(defn throbbing-panel
  [& {:keys [throbber] :or {throbber :loader-ticks}}]
  [:div.container-fluid
   [:div.row {:style {:height "55px"}}]
   [:div.text-center
    (case throbber
      :loader [:div {:class "loader"}]
      :loader-ticks [:div {:class "loader-ticks"}]
      :round-loader [:div [:img {:src "img/round_spinner.gif"}]]
      :horizontal-loader [:div [:img {:src "img/horizontal_spinner.gif"}]]
      :jupiter [:div [:img {:src "img/jupiter.gif"}]])]
   [:div.row {:style {:height "55px"}}]])

(defn error-panel [& {:keys [status content]}]
  {:pre [(and status)]}
  [:div.container-fluid.text-center
   {:style {:padding "40px"}}
   [:div.row [:h3 [:span.text-muted status]]]
   [:div.row [:br]]
   [:div.row.text-center content]])

(defn dropdown-select [{:keys [label model options select-fn header height] :as args}]
  (let [local-label (reagent/atom model)]
    (fn [{:keys [label model options select-fn header height] :or {select-fn identity}}]
      (let [args (dissoc args :label :model :options :select-fn :header)]
        [bs/dropdown
         (deep-merge
          {:id "my-dropdown"
           :onSelect (fn [e k] (reset! local-label k) (select-fn k))}
          args
          {:style {:height height}})
         [bs/button
          {:style {:pointer-events "none !important" :height height}}
          [:span.text-muted label] @local-label]
         [bs/dropdown-toggle {:style {:height height}}]
         [bs/dropdown-menu
          (concat
           [^{:key "header"} [bs/menu-item {:header true} header]
            ^{:key "divider"} [bs/menu-item {:divider true}]]
           (for [{:keys [key label]} options]
             ^{:key key} [bs/menu-item {:eventKey label} label]))]]))))

(defn status-icon [status]
  [:span.label.pull-left
   {:style {:margin-left "10px"
            :margin-top "0px"
            :font-size "20px"
            :color (color-codes status)}}
   [:i.zmdi
    {:style {:line-height "1.4em"
             :font-size "16px"}
     :class (case status
              :info  "zmdi-info"
              :ok    "zmdi-storage"
              :error "zmdi-alert-circle")}]])

(defn right-href [href]
  (if (and href (.startsWith href "public"))
    (second (gstr/splitLimit href "/" 1))
    href))

(defn user-thumb
  ([href] (user-thumb {} href))
  ([props href]
   [bs/image
    (merge {:src (right-href href)
            :height "42" :width "42"
            :circle true}
           props)]))

(defn user-selection-component
  [{username :username {href :href} :avatar}]
  (fn [{username :username {href :href} :avatar}]
    [:div {:style {:padding "1px 4px"
                   :border-radius "4.5px"
                   :display "inline-block"
                   :border-style "outset"
                   :background-color "#e2e2e2"}}
     [:div username
      [:span
       {:style {:padding-left "10px"}}
       [user-thumb {:height "25px" :width "25px"} href]]]]))

(defn move-cursor [dir els]
  (fn [idx]
    (let [[f top] (case dir
                    :next [inc (count els)]
                    :prev [dec (count els)])]
      (mod (f idx) top))))

(defn get-nth-role [idx roles]
  (nth roles idx))

(defn on-click-fn [dir roles current-idx-atom on-change]
  (fn [e]
    (.stopPropagation e)
    (let [new-idx (swap! current-idx-atom (move-cursor dir roles))]
      (on-change (get-nth-role new-idx roles)))))

(defn editable-role-btn [user roles editing editable? opts]
  (let [current-idx (reagent/atom 0)]
    (fn [user roles editing editable?
         {:keys [on-change on-submit on-dismiss]
          :or {on-change identity on-submit identity on-dismiss identity}}]
      (let [current-role (get-nth-role @current-idx roles)
            desc (project-user-roles-descs current-role)]
        [:div
         [:div.input-group
          [:span.input-group-btn
           [:button.btn.btn-default
            {:type "button"
             :on-click (on-click-fn :prev roles current-idx on-change)}
            [bs/glyphicon {:glyph "chevron-left"}]]]
          [bs/overlay-trigger
           {:overlay (reagent/as-component [bs/tooltip {:id "tooltip"} desc])
            :placement "top"}
           [:span.form-control.text-center
            [bs/label current-role]]]
          [:span.input-group-btn
           [:button.btn.btn-default
            {:type "button"
             :on-click (on-click-fn :next roles current-idx on-change)}
            [bs/glyphicon {:glyph "chevron-right"}]]]
          (when editable?
            [:span.input-group-btn
             [:button.btn.btn-default
              {:type "button"
               :on-click #(do (swap! editing not) (on-submit user current-role))}
              [bs/glyphicon {:glyph "ok"}]]])
          (when editable?
            [:span.input-group-btn
             [:button.btn.btn-default
              {:type "button"
               :on-click #(do (swap! editing not) (on-dismiss))}
              [bs/glyphicon {:glyph "remove"}]]])]]))))

(defn display-role-btn [editing role editable?]
  (fn [editing role editable?]
    [:div
     [:div.input-group
      (let [desc (project-user-roles-descs role)]
        [bs/overlay-trigger
         {:overlay (reagent/as-component [bs/tooltip {:id "tooltip"} desc])
          :placement "top"}
         [:span.form-control.text-center [bs/label role]]])
      [bs/overlay-trigger
       {:overlay (reagent/as-component
                  [bs/tooltip {:id "tooltip"}
                   (if editable? "Click to edit user role" "Can't edit user role")])
        :placement "right"}
       [:span.input-group-addon
        {:style {:cursor (if editable? "pointer" "not-allowed") :opacity (if-not editable? 0.6)}
         :onClick #(do (.stopPropagation %) (if editable? (swap! editing not)))}
        [bs/glyphicon {:glyph "pencil"}]]]]]))

(defn select-role-btn
  [user roles {:keys [role on-change on-submit displayable? editable?] :as opts}]
  (when displayable? (assert role "Role must be provide in displayable mode"))
  (let [editing (reagent/atom (not role))]
    (fn [user roles {:keys [role on-change on-submit]}]
      (if (not displayable?)
          [editable-role-btn user roles editing editable? opts]
          (if @editing
            [editable-role-btn user roles editing editable? opts]
            [display-role-btn editing role editable?])))))

(defn text-td [text]
  [:td [:div {:style {:width "100%"
                      :white-space "nowrap"
                      :overflow "hidden"
                      :text-overflow "ellipsis"}}
        text]])

(defn online-dot [active]
  (let [color (when active "rgb(66, 183, 42)")
        style {:height "8px" :width "8px" :display "inline-block"}]
    [bs/overlay-trigger
     {:overlay (reagent/as-component [bs/tooltip {:id "tooltip"} "Active"])}
     [:div {:style (assoc style :border-radius "50%" :background-color color)}]]))

(defn user-attributes [user & {:keys [align] :or {align :left}}]
  (fn [{:keys [avatar username firstname lastname email created last-active active] :as user}]
    [bs/table
     {:style {:table-layout "fixed"}}
     [:colgroup [:col {:span 1 :style {:width (if (= align :left) "10%" "90%")}}]]
     [:tbody
      {:style {:text-align "right"}}
      (if (= align :left)
        [:tr
         [:td [bs/glyphicon {:glyph "envelope"}]]
         [text-td email]]
        [:tr
         [text-td email]
         [:td [bs/glyphicon {:glyph "envelope"}]]])
      (if (= align :left)
        [:tr
         [:td [bs/glyphicon {:glyph "time"}]]
         [text-td [:span "Joined on " [:span.text-muted (parse-time created)]]]]
        [:tr
         [text-td [:span "Joined on " [:span.text-muted (parse-time created)]]]
         [:td [bs/glyphicon {:glyph "time"}]]])
      (if (= align :left)
        [:tr
         [:td [bs/glyphicon {:glyph "exclamation-sign"}]]
         [text-td [:span "Last active " [:span.text-muted (human-time last-active)]]]]
        [:tr
         [text-td [:span "Last active " [:span.text-muted (human-time last-active)]]]
         [:td [bs/glyphicon {:glyph "exclamation-sign"}]]])]]))

(defn user-profile-component
  "A component displaying basic user information. If `displayable?`, it requires an initial role,
   which is use to display an init view of the role, otherwise it presents the user as not
   having yet any role. If `editable?`, it requires an f (`on-change` role) and (`on-submit`
   user role) and component shows a button to trigger the role update (and another one to
   dismiss it)"
  [user roles & opts]
  (fn [{:keys [avatar username firstname lastname email created last-active active] :as user}
       roles & {:keys [role on-change on-submit on-dismiss editable? displayable?]
                :or {editable? true displayable? false}
                :as opts}]
    [:div.container-fluid
     [:div.row
      [:div.col-sm-4.col-md-4
       [:h4 [:img.img-rounded.img-responsive
             {:src (:href avatar) :style {:max-height "65.5px"}}]]] ;gravatar height
      [:div.col-sm-8.col-md-8
       [:h4.truncate username
        [:br]
        [:span [:small [:cite (str firstname " " lastname)]]]]
       (when active [online-dot active])]]
     [:div.row {:style {:padding "0 15px"}}
      [user-attributes user]]
     [:div.row {:style {:padding "0 15px"}}
      [select-role-btn user roles opts]]]))

(defn number-cell [n] (fn [n] [:td n]))

(defn dummy-cell [] (fn [] [:td ""]))

(defn prepend-cell
  "prepend a cell `child` to a seq of siblings (useful for prepending :td in a :tr)"
  [siblings {:keys [key child opts]}]
  (vec (cons ^{:key key} (apply merge [child] opts) siblings)))

(defn append-cell
  "append a cell `child` to a seq of siblings (useful for appending :td in a :tr)"
  [siblings {:keys [key child opts]}]
  (conj (vec siblings) ^{:key key} (apply merge [child] opts)))

(defn notification-child                ;add a button to display notification meta
  [message date status href meta]
  [:div.notification
   {:class (dekeyword status)}
   [:div.illustration
    [user-thumb href]]
   [:div.text
    {:style {:text-align "justify" :word-spacing "-2px"}}
    [:div.title message]
    [:div.text
     {:style {:text-align "right"}}
     (.toLocaleString date "en-US")]]])

(defn notification
  [{id :id {message :message date :date {href :href} :by status :status meta :meta} :data}]
  (fn [{id :id {message :message date :date by :by status :status meta :meta} :data}]
    [:li#notification
     {:on-click #(re-frame/dispatch [:drop-notification id])}
     [notification-child message date (or status :info) href meta]]))

(defn notification-container []
  (let [notifications (re-frame/subscribe [:notifications])]
    (fn []
      [:ul#notifications
       [css-transition-group
        {:transition-name "notification"
         :transition-enter-timeout 650
         :transition-leave-timeout 650}
        (map (fn [{:keys [id data]}]
               ^{:key id} [notification {:id id :data data}])
             @notifications)]])))

(defn filter-annotation-btn [username filtered & [opts]]
  (let [user (re-frame/subscribe [:user username])]
    (fn [username filtered]
      (let [{{href :href} :avatar} @user]
        [bs/overlay-trigger
         {:overlay (reagent/as-component [bs/tooltip {:id "tooltip"} username])
          :placement "bottom"}
         [bs/button
          (merge
           {:active (boolean filtered)
            :style {:max-height "40px"}
            :onClick #(re-frame/dispatch [:update-filtered-users username])}
           opts)
          (reagent/as-component [user-thumb {:height "20px" :width "20px"} href])]]))))

(defn filter-annotation-buttons []
  (let [filtered-users (re-frame/subscribe [:active-project :filtered-users])
        active-project-users (re-frame/subscribe [:active-project-users])]
    (fn []
      [bs/button-toolbar
       (doall (for [{:keys [username]} @active-project-users
                    :let [filtered (contains? @filtered-users username)]]
                ^{:key username} [filter-annotation-btn username filtered]))])))

(defn disabled-button-tooltip [disabled?-fn msg]
  (if (disabled?-fn)
    (reagent/as-component [bs/tooltip {:id "tooltip"} msg])
    (reagent/as-component [:span])))

(defn load-from-ls-row [backup]
  [:tr
   [:td
    {:style {:cursor "pointer"}
     :on-click #(let [dump (fetch-db backup)]
                  (re-frame/dispatch [:load-db dump])
                  (re-frame/dispatch [:close-modal :localstorage]))}
    (date-str->locale backup)]])

(defn load-from-ls-modal [open?]
  (fn [open?]
    [bs/modal
     {:show @open?
      :on-hide #(re-frame/dispatch [:close-modal :localstorage])}
     [bs/modal-header
      {:closeButton true}
      [bs/modal-title
       [:div [:span {:style {:padding-right "20px"}} [:i.zmdi.zmdi-storage]]
        "Application history"]]]
     [bs/modal-body
      (let [history (get-backups)]
        (if (empty? history)
          [:div.text-muted "No backups have been found"]
          [:div
           [:div.text-muted "Application state backups: choose one to time-travel to."]
           [:br]
           [bs/table
            {:hover true}
            [:thead]
            [:tbody
             (for [backup history
                   :let [timestamp (.parse js/Date backup)]]
               ^{:key timestamp} [load-from-ls-row backup])]]]))]]))

(defn session-message-modal [data]
  (fn [data]
    (let [{:keys [message]} @data]
      [bs/modal
       {:show (boolean @data)
        :on-hide #(re-frame/dispatch [:close-modal :session-message])}
       [bs/modal-header
        {:closeButton true}
        [bs/modal-title
         [:div [:span {:style {:padding-right "20px"}} [:i.zmdi.zmdi-storage]]
          "Session message"]]]
       [bs/modal-body
        [bs/alert {:bsStyle "danger"} message]]])))

(defn compute-feedback [project-name project-name-atom]
  (cond (empty? @project-name-atom) ""
        (not= @project-name-atom project-name) "has-error"
        :else "has-success"))
