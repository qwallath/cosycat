(ns cleebo.schemas.app-state-schemas
  (:require [schema.core :as s]
            [schema.spec.core :as spec]
            [schema.spec.collection :as coll]
            #?(:clj [clojure.core.match :refer [match]]
               :cljs [cljs.core.match :refer-macros [match]])
            [cleebo.schemas.annotation-schemas :refer [annotation-schema]]
            [cleebo.schemas.project-schemas :refer [project-schema]]))

(def hit-tokens-schema
  {;; required keys
   (s/required-key :word)   s/Str
   (s/required-key :id)     s/Any
   ;; optional keys
   (s/optional-key :marked) s/Bool
   (s/optional-key :anns)   {s/Str annotation-schema}
   ;; any other additional keys
   s/Keyword                s/Any})

(def hit-meta-schema
  {;; optional keys
   (s/optional-key :marked) s/Bool
   (s/optional-key :has-marked) s/Bool
   ;; any other additional keys
   s/Keyword                s/Any})

(def results-by-id-schema
  "Internal representation of results. A map from ids to hit-maps"
  {s/Int {:hit  [hit-tokens-schema]
          :id   s/Int
          :meta hit-meta-schema}})

(def results-schema
  "Current results being displayed are represented as an ordered list
  of hits ids. Each `id` map to an entry in the :results-by-id map"
  [s/Int])

(def query-opts-schema
  {:corpus s/Str
   :context s/Int
   :size s/Int
   :criterion s/Str
   :prop-name s/Str})

(def query-results-schema
  {:query-size s/Int
   :query-str  s/Str
   :from       s/Int
   :to         s/Int
   :status {:status         (s/enum :ok :error)
            :status-content s/Str}})

(def notification-schema
  {(s/required-key :id) s/Any
   (s/required-key :data) {(s/required-key :message) s/Any
                           (s/optional-key :by)      s/Any
                           (s/optional-key :status)  (s/enum :ok :error :info)
                           (s/optional-key :date)    s/Any}})

(def settings-schema
  {:notifications {:delay s/Int}
   :snippets {:snippet-delta s/Int
              :snippet-size s/Int}})

(def avatar-schema
  {:href s/Str
   :dominant-color s/Str})

(def user-schema
  {:username s/Str
   :avatar avatar-schema
   :roles #{s/Str}
   :created s/Int
   :last-active s/Int
   :projects [project-schema]})

(def public-user-schema
  {:username s/Str
   :avatar avatar-schema
   :roles #{s/Str}
   :created s/Int
   :last-active s/Int
   (s/optional-key :active) s/Bool
   (s/optional-key :projects) [project-schema]})

(def app-error-schema
  {:error s/Str
   :message s/Str
   (s/optional-key s/Any) s/Any})

(def db-schema
  {:settings settings-schema
   :session {:init-session s/Bool
             (s/optional-key :session-error) app-error-schema
             :query-opts query-opts-schema
             :query-results query-results-schema
             :results-by-id (s/conditional empty? {} :else results-by-id-schema)
             :results (s/conditional empty? [] :else results-schema)
             :notifications {s/Any notification-schema}
             :active-panel s/Keyword
             (s/optional-key :active-project) {:name s/Str :filtered-users #{s/Str}}
             (s/optional-key :corpora) [s/Str]
             (s/optional-key :throbbing?) {s/Any s/Bool}
             (s/optional-key :modals)     {s/Keyword s/Any}
             (s/optional-key :user-info)  user-schema
             (s/optional-key :users) [public-user-schema]}})
