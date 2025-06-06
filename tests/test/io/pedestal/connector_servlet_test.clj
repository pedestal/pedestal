; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.connector-servlet-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :refer [go]]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.response :refer [respond-with]]
            [io.pedestal.connector.servlet :as servlet])
  (:import (io.pedestal.servlet ConnectorServlet)
           (io.pedestal.servlet.mock MockState)
           (jakarta.servlet Servlet ServletConfig)))

(def hello
  {:name  ::hello
   :enter (fn [context]
            (respond-with context 200 (::greeting context)))})

(def hello-async
  {:name  ::hello-async
   :enter (fn [context]
            (go
              (respond-with context 200 (::salutation context))))})

(defn create-bridge
  [^Servlet servlet]
  (servlet/create-bridge servlet
                         (-> (conn/default-connector-map -1)
                             (conn/with-default-interceptors)
                             (assoc :initial-context {::greeting   "Greetings!"
                                                      ::salutation "Salutations!"})
                             (conn/with-routes
                               #{["/hello" :get hello]
                                 ["/hello-async" :get hello-async]}))))

(defn- expect [path expected-response async?]
  (let [mock-state      (MockState. (str "http://locahost:8080/" path) "GET" "http" "locahost" 8080 path "" {} nil)
        servlet         (ConnectorServlet.)
        params          {"createBridge" "io.pedestal.connector-servlet-test/create-bridge"}
        config          (reify ServletConfig
                          (getInitParameter [_ k]
                            (get params k)))
        _               (do
                          (.init servlet config)
                          (.service servlet (.request mock-state) (.response mock-state))

                          (when async?
                            (.waitForCompletion mock-state 100)))
        response-bytes  (-> mock-state .responseStream .toByteArray)
        response-string (String. response-bytes "UTF-8")]
    (is (= 200 (.responseStatus mock-state)))
    (is (= expected-response response-string))))

(deftest round-trip
  (expect "hello" "Greetings!" false))

(deftest round-trip-async
  (expect "hello-async" "Salutations!" true))

