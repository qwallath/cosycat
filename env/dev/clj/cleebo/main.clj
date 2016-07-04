(ns cleebo.main
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :refer [refresh refresh-all]]
            [clojure.java.io :as io]
            [cleebo.figwheel :refer [new-figwheel]]
            [cleebo.components.http-server :refer [new-http-server]]
            [cleebo.components.db :refer [new-db]]
            [cleebo.components.blacklab :refer [new-bl paths-map-from-corpora]]
            [cleebo.components.ws :refer [new-ws]]
            [config.core :refer [env]]))

(defonce system nil)

(def dev-config-map
  {:port (env :port)
   :database-url (env :database-url)
   :corpora (env :corpora)})

(defn create-dev-system [config-map]
  (let [{:keys [handler port database-url corpora]} config-map]
    (-> (component/system-map
         :blacklab (new-bl (paths-map-from-corpora corpora))
         :db (new-db database-url)
         :ws (new-ws)
         :figwheel (new-figwheel)
         :http-server (new-http-server {:port port :components [:db :ws :blacklab]}))
        (component/system-using
         {:http-server [:db :ws :blacklab]
          :blacklab    [:ws]
          :ws          [:db]}))))

(defn init [& [config-map]]
  (let [resource-path (:dynamic-resource-path env)
        avatar-path (:avatar-path env)
        config-map (merge dev-config-map config-map)]
    (when-not (.exists (io/file resource-path))
      (do (println "Creating app-resources dir")
          (io/make-parents (str resource-path avatar-path "dummy"))))
    (alter-var-root #'system (constantly (create-dev-system config-map)))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system component/stop))

(defn run [& [config-map]]
  (init config-map)
  (start))

(defn reset []
  (stop)
  (refresh :after 'cleebo.main/run))

