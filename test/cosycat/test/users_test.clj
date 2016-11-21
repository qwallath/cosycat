(ns cosycat.test.users-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [cosycat.test.test-config :refer [db-fixture db]]
            [cosycat.db.utils :refer [is-user?]]
            [cosycat.db.users :as users]))

(use-fixtures :once db-fixture)

;;; Users
(def sample-user
  {:username "foo-user"
   :password "pass"
   :firstname "FOO"
   :lastname "USER"
   :email "foo@bar.com"})

(deftest users-db-test
  (testing "adding new user"
    (is (= (-> (users/new-user db sample-user)
               (select-keys [:username :roles]))
           {:username "foo-user" :roles #{"user"}})))
  (testing "adding existing user"
    (is (= :user-exists
           (-> (try (users/new-user db sample-user)
                    (catch clojure.lang.ExceptionInfo e
                      (ex-data e)))
               :code))))
  (testing "existing user"
    (is (= (boolean (is-user? db sample-user)) true)))
  (testing "user lookup"
    (is (= (-> (users/lookup-user db sample-user)
               (select-keys [:username :roles]))
           {:username (:username sample-user) :roles #{"user"}})))
  (testing "remove existing user"
    (is (not (nil? (users/remove-user db "foo-user")))))
  (testing "remove non-existing user"
    (is (nil? (do (users/remove-user db "foo-user") (is-user? db {:username "username"})))))
  (testing "user with admin role"
    (is (= (-> (users/new-user db (assoc sample-user :roles ["admin"]))
               (select-keys [:username :roles]))
           {:username "foo-user" :roles #{"admin"}})))
  (users/remove-user db "foo-user")
  (testing "user with multiple roles"
    (is (= (-> (users/new-user db (assoc sample-user :roles ["admin" "user"]))
               (select-keys [:username :roles]))
           {:username "foo-user" :roles #{"admin" "user"}}))))

