(ns portal.functional.test.accounts
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [common.db :as db]
            [common.util :as util]
            [portal.accounts :as accounts]
            [portal.functional.test.cookies :as cookies]
            [portal.functional.test.vehicles :as test-vehicles ]
            [portal.test.db-tools :refer
             [setup-ebdb-test-pool!
              setup-ebdb-test-for-conn-fixture
              clear-and-populate-test-database
              clear-and-populate-test-database-fixture
              reset-db!]]
            [portal.login :as login]
            [portal.test.login-test :as login-test]
            [portal.test.utils :as test-utils]
            [portal.vehicles :as vehicles]))

(use-fixtures :once setup-ebdb-test-for-conn-fixture)
(use-fixtures :each clear-and-populate-test-database-fixture)

(defn account-manager-context-uri
  [account-id manager-id]
  (str "/account/" account-id "/manager/" manager-id))

(defn add-user-uri
  [account-id manager-id]
  (str (account-manager-context-uri account-id manager-id) "/add-user"))

(defn account-users-uri
  [account-id manager-id]
  (str (account-manager-context-uri account-id manager-id) "/users"))

(defn manager-get-user-uri
  [account-id manager-id user-id]
  (str (account-manager-context-uri account-id manager-id) "/user/" user-id))

(defn manager-vehicles-uri
  [account-id manager-id]
  (str (account-manager-context-uri account-id manager-id) "/vehicles"))

(deftest account-managers-security
  (with-redefs [common.sendgrid/send-template-email
                (fn [to subject message
                     & {:keys [from template-id substitutions]}]
                  (println "No reset password email was actually sent"))]
    (let [conn (db/conn)
          manager-email "manager@bar.com"
          manager-password "manager"
          manager-full-name "Manager"
          ;; register a manager
          _ (login-test/register-user! {:db-conn conn
                                        :platform-id manager-email
                                        :password manager-password
                                        :full-name manager-full-name})
          manager (login/get-user-by-email conn manager-email)
          account-name "FooBar.com"
          ;; register an account
          _ (accounts/create-account! account-name)
          ;; retrieve the account
          account (accounts/get-account-by-name account-name)
          account-id (:id account)
          ;; associate manager with account
          _ (accounts/associate-account-manager! (:id manager) (:id account))
          manager-login-response (test-utils/get-uri-json
                                  :post "/login"
                                  {:json-body {:email manager-email
                                               :password manager-password}})
          manager-user-id (cookies/get-cookie-user-id manager-login-response)
          manager-auth-cookie (cookies/auth-cookie manager-login-response)
          ;; child account
          child-email "james@purpleapp.com"
          child-password "child"
          child-full-name "Foo Bar"
          _ (login-test/register-user! {:db-conn conn
                                        :platform-id child-email
                                        :password child-password
                                        :full-name child-full-name})
          child (login/get-user-by-email conn child-email)
          ;; associate child-account with account
          _ (accounts/associate-child-account! (:id child) (:id account))
          ;; generate auth-cokkie
          child-login-response (test-utils/get-uri-json
                                :post "/login"
                                {:json-body
                                 {:email child-email
                                  :password child-password}})
          child-user-id (cookies/get-cookie-user-id child-login-response)
          child-auth-cookie (cookies/auth-cookie child-login-response)
          ;; register another account
          _ (accounts/create-account! "BazQux.com")
          ;; retrieve the account
          another-account (accounts/get-account-by-name "BaxQux.com")
          second-child-email "baz@bar.com"
          second-child-full-name "Baz Bar"
          ;; second user
          ;; second-email "baz@qux.com"
          ;; second-password "bazqux"
          ;; second-full-name "Baz Qux"
          ;; _ (login-test/register-user! {:db-conn conn
          ;;                               :platform-id second-email
          ;;                               :password second-password
          ;;                               :full-name second-full-name})
          ;; second-user (login/get-user-by-email conn second-email)
          ;; second-user-id (:id second-user)
          ]
      (testing "Only account managers can add users"
        ;; child user can't add a user
        (is (= 403
               (-> (test-utils/get-uri-json :post (add-user-uri
                                                   account-id
                                                   child-user-id)
                                            {:json-body
                                             {:email second-child-email
                                              :full-name second-child-full-name}
                                             :headers child-auth-cookie})
                   (get-in [:status]))))
        ;; account manager can
        (is (-> (test-utils/get-uri-json :post (add-user-uri
                                                account-id
                                                manager-user-id)
                                         {:json-body
                                          {:email second-child-email
                                           :full-name second-child-full-name}
                                          :headers manager-auth-cookie})
                (get-in [:body :success])))
        (testing "Users can't see other users"
          ;; can't see their parent account's users
          (is (= 403
                 (-> (test-utils/get-uri-json
                      :get (account-users-uri account-id child-user-id)
                      {:headers child-auth-cookie})
                     (get-in [:status]))))
          ;; can't see another child account user
          (is (= 403
                 (-> (test-utils/get-uri-json :get (manager-get-user-uri
                                                    account-id
                                                    child-user-id
                                                    (:id
                                                     (login/get-user-by-email
                                                      conn
                                                      second-child-email)))
                                              {:headers child-auth-cookie})
                     (get-in [:status]))))
          ;; can't see manager account user
          (is (= 403
                 (-> (test-utils/get-uri-json :get (manager-get-user-uri
                                                    account-id
                                                    child-user-id
                                                    manager-user-id)
                                              {:headers child-auth-cookie})
                     (get-in [:status]))))
          ;; ...but the manager can see the account user
          (is (= child-user-id
                 (-> (test-utils/get-uri-json :get (manager-get-user-uri
                                                    account-id
                                                    manager-user-id
                                                    child-user-id)
                                              {:headers manager-auth-cookie})
                     (get-in [:body :id])))))
        (testing "Only account managers can see all vehicles"
          ;; add some vehicles to manager account and child account
          (test-vehicles/create-vehicle! conn
                                         (test-vehicles/vehicle-map {})
                                         {:id manager-user-id})
          (test-vehicles/create-vehicle! conn
                                         (test-vehicles/vehicle-map
                                          {:color "red"
                                           :year "2006"})
                                         {:id manager-user-id})
          (test-vehicles/create-vehicle! conn
                                         (test-vehicles/vehicle-map
                                          {:make "Honda"
                                           :model "Accord"
                                           :color "Silver"})
                                         {:id child-user-id})
          (test-vehicles/create-vehicle! conn
                                         (test-vehicles/vehicle-map
                                          {:make "Hyundai"
                                           :model "Sonota"
                                           :color "Orange"})
                                         {:id (:id
                                               (login/get-user-by-email
                                                conn
                                                second-child-email))})
          ;; manager sees all vehicles
          (is (= 4
                 (-> (test-utils/get-uri-json :get (manager-vehicles-uri
                                                    account-id
                                                    manager-user-id)
                                              {:headers manager-auth-cookie})
                     (get-in [:body])
                     (count)))))
        (testing "Child accounts can only see their own vehicles"
          ;; (println (-> (get-uri-json :get (manager-vehicles-uri
          ;;                                  child-user-id)
          ;;                            {:headers child-auth-cookie})))
          ;; ;; child can't get account-vehicles
          ;; (is (not (-> (get-uri-json :get (manager-vehicles-uri
          ;;                                  child-user-id)
          ;;                            {:headers child-auth-cookie})
          ;;              (get-in [:body :success]))))
          ;; child can't see another user's vehicle
          ;; child can't can't see another vehicle associated with account
          )
        (testing "Users can't see other user's vehicles"
          ;; add a vehicle by another user, not associated with account
          )
        (testing "Account managers can see all orders"
          ;; add orders for manager and child account
          )
        (testing "Users can see their own orders"
          )
        (testing "... but users can't see orders of other accounts")
        (testing "Child accounts can't add vehicles")
        (testing "A user can get their own vehicles"
          #_ (let [_ (vehicles/create-vehicle! conn (test-vehicles/vehicle-map {})
                                               {:id user-id})
                   vehicles-response (portal.handler/handler
                                      (-> (mock/request
                                           :get (str "/user/" user-id "/vehicles"))
                                          (assoc :headers auth-cookie)))
                   response-body-json (cheshire/parse-string
                                       (:body vehicles-response) true)]
               (is (= user-id
                      (-> response-body-json
                          first
                          :user_id))))))
      (testing "A user can not access other user's vehicles"
        #_ (let [_ (create-vehicle! conn (vehicle-map {}) {:id second-user-id})
                 vehicles-response (portal.handler/handler
                                    (-> (mock/request
                                         :get (str "/user/" second-user-id
                                                   "/vehicles"))
                                        (assoc :headers auth-cookie)))]
             (is (= 403
                    (:status vehicles-response))))))))


(deftest selenium-acccount-user
  ;; users not show for account-children
  ;; is shown for managers
  ;; users can be added
  ;; child account can login and change password
  ;; users can add vehicles
  ;; .. but not if they are child users
  ;; account managers can add vehicles
  ;; child accounts can't
  )
