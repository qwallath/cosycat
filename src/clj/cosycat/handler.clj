(ns cosycat.handler
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [config.core :refer [env]]
            [compojure.core :refer [GET POST ANY routes wrap-routes]]
            [compojure.route :refer [resources files not-found]]
            [prone.middleware :refer [wrap-exceptions]]
            [cosycat.views.error :refer [error-page]]
            [cosycat.views.cosycat :refer [cosycat-page]]
            [cosycat.views.landing :refer [landing-page]]
            [cosycat.views.about :refer [about-page]]
            [cosycat.views.login :refer [login-page]]
            [ring.util.response :refer [redirect]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery *anti-forgery-token*]]
            [ring.middleware.transit :refer [wrap-transit-response wrap-transit-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.nested-params :refer [wrap-nested-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring-ttl-session.core :refer [ttl-memory-store]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [cosycat.routes.auth
             :refer [is-logged? auth-backend token-backend login-route logout-route signup-route]]
            [cosycat.routes.utils :refer [safe]]
            [cosycat.components.ws :refer [ws-handler-http-kit send-clients]]
            [cosycat.routes.session :refer [session-route]]
            [cosycat.routes.projects :refer [project-routes]]
            [cosycat.routes.settings :refer [settings-routes]]
            [cosycat.routes.annotations :refer [annotation-routes]]
            [cosycat.routes.users :refer [users-routes]]
            [cosycat.routes.events :refer [events-routes]]
            [cosycat.routes.admin :refer [admin-routes]]))

(defn static-routes []
  (routes
   (GET "/" req (landing-page :logged? (is-logged? req)))
   (GET "/login" [] (login-page :csrf *anti-forgery-token*))
   (POST "/login" [] login-route)
   (POST "/signup" [] signup-route)
   (ANY "/logout" [] logout-route)
   (GET "/about" [] (safe (fn [req] (about-page :logged? (is-logged? req)))))
   (GET "/cosycat" [] (safe (fn [req] (cosycat-page :csrf *anti-forgery-token*))))))

(defn web-app-routes []
  (routes
   (GET "/session" [] session-route)
   (GET "/ws" [] ws-handler-http-kit)))

(defn base-routes []
  (routes
   (resources "/")
   (files "/" {:root (:dynamic-resource-path env)})
   (not-found (error-page {:status 404 :title "Page not found!!"}))))

;;; middleware
(defn is-ajax
  "not sure how robust this is"
  [{headers :headers :as req}]
  (boolean (= "XMLHttpRequest" (get headers "X-Requested-With"))))

(defn wrap-debug [handler]
  (fn [req]
    (timbre/debug req)
    (handler req)))

(defn format-exception [req error]
  (let [msg "Oops! Something bad happened!"]
    (if (is-ajax req)
      {:status 500 :body {:message msg :data {:exception error :type :internal-error}}}
      (error-page {:status 500 :title msg :message error}))))

(defn wrap-internal-error [handler]
  (fn [req]
    (try
      (handler req)
      (catch clojure.lang.ExceptionInfo e
        (timbre/error (str e))
        (->> e ex-data :error (format-exception req)))
      (catch Throwable t
        (timbre/error (str t))
        (->> t class str (format-exception req))))))

(defn wrap-error [handler]
  (fn [req]
    (if (:dev? env)
      ((wrap-exceptions handler) req)
      ((wrap-internal-error handler) req))))

(defn wrap-base [handler]
  (-> handler
      ;; wrap-debug
      wrap-reload
      (wrap-authorization auth-backend) ;todo, swap with token backend (jwt)
      (wrap-authentication auth-backend) ;todo swap with token backend (jwt)
      (wrap-anti-forgery {:read-token (fn [req] (get-in req [:params :csrf]))})
      (wrap-session {:store (ttl-memory-store (* (env :session-expires) 60))})
      (wrap-transit-params {:encoding :json-verbose})
      wrap-keyword-params
      wrap-nested-params
      wrap-params
      (wrap-transit-response {:encoding :json-verbose})
      wrap-error))

(defn wrap-app-component [handler components]
  (fn [req]
    (handler (assoc req :components components))))

;;; handler
(defn app-routes
  "utility function to avoid global route definitions. Routes are defined as functions and
   only called inside the web-server component. This allows the redefinition of routes during dev"
  [& route-fns]
  (apply routes (map #(%) route-fns)))

(defn make-handler [component & {:keys [debug]}]
  (let [components (select-keys component (:components component))]
    (-> (app-routes static-routes web-app-routes settings-routes annotation-routes
                    project-routes users-routes events-routes admin-routes base-routes)
        (wrap-app-component components)
        (wrap-routes wrap-base))))
