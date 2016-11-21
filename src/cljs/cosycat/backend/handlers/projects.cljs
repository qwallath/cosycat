(ns cosycat.backend.handlers.projects
  (:require [re-frame.core :as re-frame]
            [reagent.core :as reagent]
            [ajax.core :refer [POST]]
            [cosycat.utils :refer [format]]
            [cosycat.app-utils :refer [get-pending-users deep-merge update-coll]]
            [cosycat.routes :refer [nav!]]
            [cosycat.backend.handlers.users :refer [normalize-query-metadata]]
            [cosycat.backend.middleware :refer [standard-middleware check-project-exists]]
            [cosycat.backend.db
             :refer [default-project-session default-project-history default-settings]]
            [taoensso.timbre :as timbre]))

(defn normalize-queries [queries]
  (reduce-kv
   (fn [acc k v] (assoc acc k (normalize-query-metadata v)))
   {}
   queries))

(defn normalize-projects
  "transforms server project to client project"
  [projects]
  (reduce
   (fn [acc {:keys [name queries issues events] :as project}]
     (assoc acc name (cond-> project
                       true (assoc :session (default-project-session project))
                       issues (assoc :issues (zipmap (map :id issues) issues))
                       events (assoc :events (zipmap (map :id events) events))
                       queries (assoc :queries (normalize-queries queries)))))
   {}
   projects))

(re-frame/register-handler
 :remove-active-project
 standard-middleware
 (fn [db _]
   (assoc-in db [:session :active-project] nil)))

(defn get-project-settings [db project-name]
  (or (get-in db [:projects project-name :settings]) ;project-related settings
      (get-in db [:me :settings])                    ;global settings
      (default-settings :corpora (:corpora db))))    ;default settings

(re-frame/register-handler
 :reset-settings
 standard-middleware
 (fn [db [_ & {:keys [init] :or {init {}}}]]
   (let [active-project (get-in db [:session :active-project])
         project-settings (deep-merge (get-project-settings db active-project) init)]
     (update db :settings deep-merge project-settings))))

(re-frame/register-handler
 :set-active-project
 (conj standard-middleware check-project-exists)
 (fn [db [_ {:keys [project-name]}]]
   (let [project-settings (get-project-settings db project-name)]
     (-> db
         (assoc-in [:session :active-project] project-name)
         (update :settings deep-merge project-settings)))))

(re-frame/register-handler              ;add project to client-db
 :add-project
 standard-middleware
 (fn [db [_ project]]
   (update db :projects merge (normalize-projects [project]))))

(re-frame/register-handler              ;remove project from client-db
 :remove-project
 standard-middleware
 (fn [db [_ project-name]]
   (update db :projects dissoc project-name)))

(defn error-handler [{{:keys [message data code]} :response}]
  (re-frame/dispatch [:notify {:message message :meta data :status :error}]))

(defn new-project-handler [{project-name :name :as project}]
  (re-frame/dispatch [:add-project project])
  (nav! (str "/project/" project-name)))

(re-frame/register-handler
 :new-project
 standard-middleware
 (fn [db [_ {:keys [name description users] :as project}]]
   (POST "/project/new"
         {:params {:project-name name
                   :description description
                   :users users}
          :handler new-project-handler
          :error-handler error-handler})
   db))

(re-frame/register-handler              ;add project update to client-db
 :update-project-issue
 standard-middleware
 (fn [db [_ project-name {id :id :as issue}]]
   (update-in db [:projects project-name :issues id] deep-merge issue)))

(re-frame/register-handler
 :add-issue-meta
 standard-middleware
 (fn [db [_ issue-id path value]]
   (let [active-project (get-in db [:session :active-project])]
     (assoc-in db (into [:projects active-project :issues issue-id :meta] path) value))))

(re-frame/register-handler
 :update-issue-meta
 standard-middleware
 (fn [db [_ issue-id path update-fn]]
   (let [active-project (get-in db [:session :active-project])]
     (update-in db (into [:projects active-project :issues issue-id :meta] path) update-fn))))

(defn project-add-issue-handler [project-name]
  (fn [issue]
    (re-frame/dispatch [:notify {:message "New issue was added to project"}])
    (re-frame/dispatch [:update-project-issue project-name issue])))

(re-frame/register-handler
 :project-issue
 standard-middleware
 (fn [db [_ {:keys [payload project-name]}]]
   (let [project-name (or project-name (get-in db [:session :active-project]))]
     (POST "/project/issues/new"
           {:params {:project-name project-name :payload payload}
            :handler (project-add-issue-handler project-name)
            :error-handler error-handler}))
   db))

(re-frame/register-handler
 :comment-on-issue
 (fn [db [_ {:keys [comment issue-id parent-id] :as params}]]
   (let [active-project (get-in db [:session :active-project])]
     (POST "/project/issues/comment/new"
           {:params (assoc params :project-name active-project)
            :handler #(re-frame/dispatch [:update-project-issue active-project %])
            :error-handler #(re-frame/dispatch
                             [:notify {:message "Couldn't store comment" :status :error}])})
     db)))

(re-frame/register-handler
 :delete-comment-on-issue
 (fn [db [_ {:keys [comment-id issue-id] :as params}]]
   (let [active-project (get-in db [:session :active-project])]
     (POST "/project/issues/comment/delete"
           {:params (assoc params :project-name active-project)
            :handler #(re-frame/dispatch [:update-project-issue active-project %])
            :error-handler #(re-frame/dispatch
                             [:notify {:message "Couldn't delete comment" :status :error}])})
     db)))

(defn open-annotation-fn [issue-type]  
  (fn [db [_ ann-data users]] ;; ann-data is (assoc previous-ann :value new-ann-value)
    (let [project (get-in db [:session :active-project])
          corpus (get-in db [:projects project :session :query :results-summary :corpus])
          query (get-in db [:projects project :session :query :results-summary :query-str])
          ann-data (assoc ann-data :corpus corpus :query query)]
      (POST "/project/issues/annotation-edit/open"
            {:params {:project-name project
                      :type issue-type
                      :users users
                      :ann-data ann-data}
             :handler (project-add-issue-handler project)
             :error-handler error-handler})
      db)))

(re-frame/register-handler
 :open-annotation-edit
 (open-annotation-fn "annotation-edit"))

(re-frame/register-handler
 :open-annotation-remove
 (open-annotation-fn "annotation-remove"))

(re-frame/register-handler              ;add user to project in client-db
 :add-project-user
 standard-middleware
 (fn [db [_ {:keys [user project-name]}]]
   (update-in db [:projects project-name :users] conj user)))

(defn project-add-user-handler [{:keys [user project-name] :as data}]
  (re-frame/dispatch [:add-project-user data]))

(re-frame/register-handler
 :project-add-user
 standard-middleware
 (fn [db [_ {:keys [username role]}]]
   (let [project-name (get-in db [:session :active-project])]
     (POST "/project/add-user"
           {:params {:username username :role role :project-name project-name}
            :handler project-add-user-handler
            :error-handler error-handler}))
   db))

(re-frame/register-handler              ;remove user from project in client-db
 :remove-project-user
 standard-middleware
 (fn [db [_ {:keys [username project-name]}]]
   (update-in
    db [:projects project-name :users]
    (fn [users] (vec (remove #(= (:username %) username) users))))))

(defn remove-user-handler [project-name]
  (fn []
    (nav! "/")
    (re-frame/dispatch [:remove-project project-name])
    (re-frame/dispatch [:notify {:message (str "Goodbye from project " project-name)}])))

(re-frame/register-handler
 :project-remove-user
 (fn [db [_ project-name]]
   (POST "/project/remove-user"
         {:params {:project-name project-name}
          :handler (remove-user-handler project-name)
          :error-handler error-handler})
   db))

(defn parse-remove-project-payload [payload]
  (if (empty? payload)
    :project-removed
    :added-project-remove-agree))

(defn remove-project-handler [{project-name :name :as project}]
  (fn [{:keys [id] :as delete-issue}]
    (case (parse-remove-project-payload delete-issue)
      :project-removed
      (do (re-frame/dispatch [:remove-project project-name])
          (re-frame/dispatch [:notify {:message (str "Project " project-name " was deleted")}])
          (nav! "/"))
      :added-project-remove-agree
      (let [updated-project (update project :issues assoc id delete-issue)
            {:keys [pending-users]} (get-pending-users updated-project)] ;still users
        (re-frame/dispatch [:update-project-issue project-name delete-issue])
        (re-frame/dispatch
         [:notify {:message (str (count pending-users) " users pending to remove project")}]))
      (throw (js/Error. "Couldn't parse remove-project payload")))))

(re-frame/register-handler
 :project-remove
 (fn [db [_ {:keys [project-name]}]]
   (POST "/project/remove-project"
         {:params {:project-name project-name}
          :handler (remove-project-handler (get-in db [:projects project-name]))
          :error-handler error-handler})
   db))

(re-frame/register-handler
 :update-project-user-role
 standard-middleware
 (fn [db [_ project-name username new-role]]
   (let [pred #(= username (:username %))]
     (update-in db [:projects project-name :users] update-coll pred assoc :role new-role))))

(defn handle-new-user-role [project-name]
  (fn [{:keys [username role]}]
    ;; refresh project events
    (re-frame/dispatch
     [:notify {:message (format "Succesfully updated %s's role to \"%s\"" username role)}])
    (re-frame/dispatch [:update-project-user-role project-name username role])))

(re-frame/register-handler
 :user-role-update
 (fn [db [_ {:keys [username new-role]}]]
   (let [project-name (get-in db [:session :active-project])]
     (POST "/project/update-user-role"
           {:params {:project-name project-name
                     :username username
                     :new-role new-role}
            :handler (handle-new-user-role project-name)
            :error-handler error-handler})
     db)))
