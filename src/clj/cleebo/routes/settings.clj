(ns cleebo.routes.settings
  (:require [buddy.auth :refer [authenticated?]]
            [cleebo.routes.auth :refer [safe]]
            [cleebo.db.users :refer [update-user-info]]
            [cleebo.avatar :refer [user-avatar]]
            [cleebo.components.ws :refer [notify-clients]]
            [environ.core :refer [env]]
            [taoensso.timbre :as timbre]))

(defmulti settings-router (fn [{{route :route} :params}] route))
(defmethod settings-router :new-avatar
  [{{{username :username} :identity} :session
    {db :db ws :ws} :components}]
  (let [seed (rand-int 1000000)
        avatar (user-avatar (str username seed))]
    (update-user-info db username {:avatar avatar})
    (notify-clients
     ws
     {:type :notify :status :new-user-avatar
      :data {:avatar avatar :username username}})
    avatar))

(def settings-route 
  (safe (fn [req]
          (try {:status 200 :body (settings-router req)}
               (catch Exception e
                 {:status 500
                  :body {:message "Ooops"
                         :data {:exception (str (class e)) :type :internal-error}}})))
        {:login-uri "/login" :is-ok? authenticated?}))
