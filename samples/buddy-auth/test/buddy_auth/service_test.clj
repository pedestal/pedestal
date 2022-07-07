(ns buddy-auth.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as http]
            [buddy-auth.service :as service]
            [buddy.core.codecs :as codecs]
            [buddy.core.codecs.base64 :as base64]))

(def service
  (::http/service-fn (http/create-servlet service/service)))

(defn make-headers
  [username password]
  (let [b64-encoded (base64/encode (str username ":" password))]
    {"Authorization" (str "Basic " (codecs/bytes->str b64-encoded))}))

(defn GET
  [service & opts]
  (apply response-for service :get opts))

(deftest home-test
  (testing "Anonymous user"
    (is (= "Hello anonymous"
           (:body (GET service "/")))))
  (testing "Authenticated user"
    (is (= "Hello Aaron Aardvark"
           (:body (GET service "/" :headers (make-headers "aaron" "secret")))))))

(deftest admin-test
  (are [status username password _] (= status
                                       (:status (if username
                                                  (GET service "/admin"
                                                       :headers (make-headers username password))
                                                  (GET service "/admin"))))
    401 nil     nil        "Unauthenticated access is unauthorized."
    403 "aaron" "secret"   "Unauthorized access is forbidden."
    200 "gmw"   "rutabaga" "Authorized access is allowed."))
