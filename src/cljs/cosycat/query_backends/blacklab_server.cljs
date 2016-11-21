(ns cosycat.query-backends.blacklab-server
  (:require [ajax.core :refer [GET]]
            [cljs.core.async :refer [chan >! <! put! close! timeout sliding-buffer take!]]
            [cosycat.ajax-jsonp :refer [jsonp]]
            [cosycat.query-backends.protocols :as p]
            [cosycat.utils :refer [keywordify]]
            [cosycat.app-utils :refer [->int parse-hit-id]]
            [cosycat.localstorage :refer [with-ls-cache]]
            [taoensso.timbre :as timbre])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(declare ->BlacklabServerCorpus)

(defn make-blacklab-server-corpus
  "instantiate a BlacklabServerCorpus for a given corpus name
   `on-counting-callback` is a callback to be called internally in case BlacklabServer
   return a partial count for a given query"
  [corpus-name {:keys [server web-service on-counting-callback]
                :or {on-counting-callback identity}}]
  (let [timeout-ids (atom []), callback-id (atom -1)]
    (->BlacklabServerCorpus
     corpus-name server web-service on-counting-callback timeout-ids callback-id)))

(def default-query-params
  {:maxcount 200000
   :waitfortotal "no"})

;;; parse cosycat args -> blacklab-server params
(declare bl-server-url bl-server-sort-str bl-server-filter-str)

;;; normalize blacklab-out -> cosycat app-schemas
(declare
 normalize-results normalize-results-summary
 normalize-corpus-info normalize-snippet normalize-query-hit)

;;; handle counting callbacks
(declare on-counting clear-timeout)

;;; handle internal sort state (blacklab-server is stateless)
(declare maybe-reset-last-action set-last-action get-sort-opts)

(defn base-query
  ([corpus query-str {:keys [context from page-size] :as query-opts} sort-opts filter-opts]
   (let [server (.-server corpus) web-service (.-web-service corpus) index (.-index corpus)]
     (p/handle-query
      corpus (bl-server-url server web-service index)
      ;; transform params to bl-query params
      (cond-> {:patt query-str          
               :wordsaroundhit context
               :first from
               :number page-size
               :jsonp "callback"}
        (not (empty? sort-opts)) (merge {:sort (bl-server-sort-str sort-opts)})
        (not (empty? filter-opts)) (merge {:filter (bl-server-filter-str filter-opts)})
        ;; overwrite default query params
        true (merge default-query-params))
      :method jsonp)))
  ([corpus query-str query-opts] (base-query corpus query-str query-opts nil nil)))

(deftype BlacklabServerCorpus [index server web-service callback timeout-ids callback-id]
  p/Corpus
  (p/query [this query-str query-opts]
    (clear-timeout timeout-ids)
    (base-query this query-str query-opts))

  (p/query-sort [this query-str query-opts sort-opts filter-opts]
    (base-query this query-str query-opts sort-opts filter-opts))

  (p/query-hit [_ hit-id {:keys [words-left words-right] :or {words-left 0 words-right 0}} handler]
    (let [{:keys [doc-id hit-start hit-end]} (parse-hit-id hit-id)
          jsonp-callback-str (str "callback" (swap! callback-id inc))]
      (jsonp (bl-server-url server web-service index :resource (str "docs/" doc-id "/snippet"))
             {:params {:wordsaroundhit (max words-left words-right)
                       :hitstart (->int hit-start)
                       :hitend (->int hit-end)
                       :jsonp jsonp-callback-str}              
              :json-callback-str jsonp-callback-str
              :handler #(-> % (normalize-query-hit hit-id words-left words-right) handler)
              :error-handler #(timbre/error %)})))

  (p/snippet [_ query-str {:keys [snippet-size snippet-delta] :as snippet-opts} hit-id dir]
    (let [{:keys [doc-id hit-start hit-end]} (parse-hit-id hit-id)]
      (jsonp (bl-server-url server web-service index :resource (str "docs/" doc-id "/snippet"))
             {:params {:wordsaroundhit (+ snippet-delta snippet-size)
                       :hitstart (->int hit-start)
                       :hitend (->int hit-end)
                       :jsonp "callbackSnippet"}
              :json-callback-str "callbackSnippet"
              :handler #(-> % (normalize-snippet hit-id dir) (p/snippet-handler jsonp))
              :error-handler #(p/snippet-error-handler % jsonp)})))
  
  (p/transform-data [_ data]
    (let [{{:keys [message code] :as error} :error :as cljs-data}
          (js->clj data :keywordize-keys true)]
      (if error
        {:message message :code code}
        (let [{summary :summary hits :hits doc-infos :docInfos} cljs-data
              {{from :first :as params} :searchParam counting? :stillCounting} summary
              uri (bl-server-url server web-service index)]
          (when counting? (on-counting timeout-ids {:uri uri :params params :callback callback}))
          {:results-summary (normalize-results-summary summary)
           :results (normalize-results doc-infos hits from)
           :status {:status :ok}}))))
 
  (p/transform-error-data [_ data]
    (identity data))

  (p/corpus-info [_]
    (let [uri (bl-server-url server web-service index :resource "")]
      (if-let [corpus-info (some-> (with-ls-cache uri) keywordify)]
        (p/corpus-info-handler corpus-info)
        (jsonp uri {:params {:jsonp "callbackInfo"} ;avoid overwriting jsonp callback `callback`
                    :json-callback-str "callbackInfo"
                    :handler #(->> % normalize-corpus-info (with-ls-cache uri) p/corpus-info-handler)
                    :error-handler #(timbre/error %)})))))

;;; Parse cosycat args -> blacklab-server params
(defn join-params [& params]
  (apply str (interpose ":" (filter identity params))))

(defn parse-sort-opts
  "transforms internal app sort data into a single blacklab-server sort string"
  [ms]
  (map (fn [{:keys [position attribute facet]}]
         (if (= position "match")
           (join-params "hit" attribute facet)
           (join-params position attribute facet)))
       ms))

(defn parse-filter-opts
  "transforms internal app filter data into a single blacklab-server filter string"
  [ms]
  (map (fn [{:keys [attribute value]}] (join-params attribute value)) ms))

(defn bl-server-url
  "builds the blacklab server url"
  [server web-service index & {:keys [resource] :or {resource "hits"}}]
  (str "http://" server "/" web-service "/" index "/" resource))

(defn bl-server-sort-str
  "builds the blacklab sort string from param maps"
  [sort-opts]
  (apply str (interpose "&" (parse-sort-opts sort-opts))))

(defn bl-server-filter-str
  "builds the blacklab filter string from param maps"
  [filter-opts]
  (apply str (interpose " AND " (parse-filter-opts filter-opts))))

;;; normalize blacklab-out -> cosycat app-schemas
(defn normalize-meta [num doc]
  (assoc doc :num num))

(defn transpose-lists [x]
  (map (fn [m] (zipmap (keys x) m))
       (apply map vector (vals x))))

(defn sub-hit
  "transform a sub-hit (one of `left`, `right` or `match` into a vec of token maps
  containing at least :word and :id (plus any other field in the corpus)"
  [hit doc-id first-id & {:keys [is-match?]}]
  (let [first-id (->int first-id)]
    (->> (transpose-lists hit)
         (mapv (fn [token-map] (cond-> token-map is-match? (assoc :match true))))
         (mapv (fn [id token-map] (assoc token-map :id (str doc-id "." id)))
               (map (partial + first-id) (range))))))

(defn normalize-bl-hit
  ([{left :left match :match right :right doc-id :docPid start :start end :end}]
   {:hit (concat (sub-hit left  doc-id (- start (count (:word left)))) ;assuming word is present
                 (sub-hit match doc-id start :is-match? true)
                 (sub-hit right doc-id end))
    :id (apply str (interpose "." [doc-id start end]))})
  ([hit num doc]
   (assoc (normalize-bl-hit hit) :meta (normalize-meta num doc))))

(defn normalize-results-summary
  [{{from :first corpus :indexname query-str :patt num-hits :number} :searchParam
    query-size :numberOfHits query-time :searchTime has-next :windowHasNext}]
  {:page {:from (->int from) :to (+ (->int from) (->int num-hits))}
   :query-size query-size
   :query-str query-str
   :query-time query-time
   :has-next has-next
   :corpus corpus})

(defn normalize-results [doc-infos hits from]
  (let [results (map-indexed
                 (fn [idx {doc-id :docPid :as hit}]
                   (let [num (+ idx (->int from))
                         doc (get doc-infos (keyword doc-id))]
                     (normalize-bl-hit hit num doc)))
                 hits)]
    (vec results)))

(defn normalize-corpus-info [data]
  (let [{{created :timeCreated last-modified :timeModified} :versionInfo
         metadata :metadataFields
         {{props :basicProperties main-prop :mainProperty} :contents} :complexFields
         corpus-name :indexName
         word-count :tokenCount
         status :status} (js->clj data :keywordize-keys true)]
    {:corpus-info {:corpus-name corpus-name
                   :word-count word-count
                   :created created
                   :last-modified last-modified
                   :metadata metadata}
     ;; TODO add main-prop
     :status status
     :sort-props props}))

(defn normalize-query-hit
  [data hit-id words-left words-right]
  (let [{error :error :as data} (js->clj data :keywordize-keys true)]
    (if error
      data
      (let [{doc-id :doc-id start :hit-start end :hit-end} (parse-hit-id hit-id)
            bl-hit (normalize-bl-hit (assoc data :docPid doc-id :start start :end end))
            [left match right] (partition-by :match (:hit bl-hit))
            left (reverse (take (min (count left) words-left) (reverse left)))
            right (take (min (count right) words-right) right)]
        (assoc bl-hit :hit (vec (concat left match right)))))))

(defn normalize-snippet
  [data hit-id dir]
  (let [{error :error :as data} (js->clj data :keywordize-keys true)]
    (if error
      data
      (let [{doc-id :doc-id start :hit-start end :hit-end} (parse-hit-id hit-id)
            bl-hit (normalize-bl-hit (assoc data :docPid doc-id :start start :end end))
            [left match right] (partition-by :match (:hit bl-hit))]
        {:snippet (cond-> {:match match :left left :right right}
                    (= dir :left) (dissoc :right)
                    (= dir :right) (dissoc :left)
                    :else identity)
         :hit-id hit-id}))))

;;; handle counting callbacks
(defn clear-timeout [timeout-ids]
  (doseq [timeout-id @timeout-ids]
    (js/clearTimeout timeout-id))
  (reset! timeout-ids []))

(defn timeout-fn [timeout-ids {:keys [uri params callback] :as opts}]
  (fn []
    (jsonp uri
     {:params (assoc params :number 0 :jsonp "callback")
      :error-handler identity
      :handler #(let [{error :error :as data} (js->clj % :keywordize-keys true)]
                  (if-not error
                    (let [{{query-size :numberOfHits counting? :stillCounting} :summary} data]
                      (callback query-size)
                      (when counting? (on-counting timeout-ids (update opts :retried-count inc))))
                    (timbre/info "Error occurred when requesting counted hits")))})))

(defn on-counting
  [timeout-ids
   {:keys [uri params callback retry-count retried-count]
    :or {retry-count 5 retried-count 0} :as opts}]
  (when (< retried-count retry-count)
    (->> (js/setTimeout
          (timeout-fn timeout-ids opts)
          (+ 1000 (* 1500 retried-count)))
         (swap! timeout-ids conj))))

