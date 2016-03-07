(ns cleebo.shared-schemas
  (:require [schema.core :as s]
            [schema.coerce :as coerce]))

;{:_id "cpos" :anns [{:ann {"key" "value"} :username "foo" :timestamp 21930198012}]}

(def annotation-schema
  (s/conditional :span {:ann {:span {:IOB s/Str :ann {:key s/Str :value s/Str}}}
                        :timestamp s/Int
                        :username s/Str}
                 :else {:ann {:key s/Str :value s/Any}
                        :timestamp s/Int
                        :username s/Str}))

(s/defn ^:always-validate make-ann :- annotation-schema
  [k v username]
  {:ann {:key k :value v}
   :username username
   :timestamp #?(:cljs (.now js/Date)
                 :clj  (System/currentTimeMillis))})

(defn- ->span-ann*
  [IOB ann]
  (update ann :ann (fn [ann] {:span {:IOB IOB :ann ann}})))

(s/defn ^:always-validate ->span-ann  :- annotation-schema
  [k v username IOB]
  (->> (make-ann k v username)
       (->span-ann* IOB)))
