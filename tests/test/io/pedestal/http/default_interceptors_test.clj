; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.default-interceptors-test
  (:require [clojure.test :refer :all]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.interceptor.chain :as chain]
            [ring.util.io :as ringio]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.params :as params]))

(defn resp [s b & {:as headers}]
  (assoc {:status s :body b} :headers headers))

(defn okpage      [req] (resp 200 "OK"))
(defn okpage-html [req] (resp 200 "OK" "Content-Type" "text/html"))
(defn echo        [req] (resp 200 req))

(defn default-interceptors [routes]
  (http/default-interceptors {:io.pedestal.http/routes routes}))

(defn app [routes request]
  (chain/execute
   (chain/enqueue {:request request}
          (::http/interceptors (default-interceptors routes)))))

(defn request [method uri & {:as params}]
  (merge
   {:uri            uri
    :path-info      uri
    :request-method method
    :headers        {}
    :scheme         "do-not-match-scheme"
    :server-name    "do-not-match-host"
    :server-port    -1}
   params))

(defn form-post [u b & {:as params}]
  (let [b (or b "")]
    (merge
     (request :post u
              :headers {"content-type" "application/x-www-form-urlencoded"
                        "content-length" (str (count b))}
              :content-type "application/x-www-form-urlencoded"
              :body (ringio/string-input-stream b))
     params)))

(def not-found-routes
  (table/table-routes
   {}
   [["/users" :get (fn [req] (resp 200 "OK")) :route-name :users]]))

(deftest not-found
  (is (= 404 (-> (app not-found-routes (request :get "no-such-url")) (get-in [:response :status])))))

(def content-type-routes
  (table/table-routes
   {}
   [["/users"                            :get okpage      :route-name :users]
    ["/content-type-from-extension.json" :get okpage      :route-name :json-index]
    ["/content-type-from-extension.edn"  :get okpage      :route-name :edn-index]
    ["/content-type-from-handler"        :get okpage-html :route-name :page-html]]))

(defn- test-content-type
  [m u s c]
  (let [r (:response (app content-type-routes (request m u)))]
    (is (= c (some-> r :headers (get "Content-Type"))))
    (is (= s (:status r)))))

(deftest content-type
  (test-content-type :get "/content-type-from-extension.json" 200 "application/json")
  (test-content-type :get "/no-such-url"                      404 nil)
  (test-content-type :get "/content-type-from-extension.edn"  200 "application/edn")
  (test-content-type :get "/content-type-from-handler"        200 "text/html"))

(def params-routes
  (table/table-routes
   {}
   [["/echo"                    :any  echo :route-name :echo]
    ["/pecho/:user-id"          :get  [echo]:route-name :pecho]
    ["/fecho"                   :post [(body-params/body-params) params/keyword-params echo] :route-name :echo-form]]))

(defn- test-query
  [req query-params params form-params]
  (let [r (:response (app params-routes req))]
    (when query-params
      (is (= query-params (-> r :body :query-params))))
    (when params
      (is (= params (-> r :body :params))))
    (when form-params
      (is (= form-params (-> r :body :form-params))))
    (is (= 200 (:status r)))))

(defn- param-get  [u q]   (request :get u :query-string q))
(defn- param-path-get [u] (request :get u))
(defn- param-post [u b q] (form-post u b :query-string q))

(deftest query-params
  (test-query (param-get "/echo" "q=searchterm")     {:q "searchterm"} {:q "searchterm"}   nil)
  (test-query (param-get "/echo" "c=%20&b=2")        {:b "2" :c " "}   {:b "2" :c " "}     nil)
  (test-query (param-post "/fecho" nil       "a=b")  {:a "b"}          {:a "b"}            {})
  (test-query (param-post "/fecho" "foo=bar" "c=d")  {:c "d"}          {:c "d" :foo "bar"} {:foo "bar"}))

(defn- test-path-params
  [m u p]
  (let [req (request m u)
        res (:response (app params-routes req))]
    (is (= p (-> res :body :path-params)))))

(deftest path-params
  (test-path-params :get "/pecho/john%20doe" {:user-id "john doe"})
  (test-path-params :get "/pecho/%E2%99%A0%E2%99%A5%E2%99%A6%E2%99%A3" {:user-id "♠♥♦♣"})
  (test-path-params :get "/pecho/abcdefghiklmnopqrstuvwxyz0123456789-._~" {:user-id "abcdefghiklmnopqrstuvwxyz0123456789-._~"})
  (test-path-params :get "/pecho/:#[]@" {:user-id ":#[]@"})
  (test-path-params :get "/pecho/!$&'()*,;=" {:user-id "!$&'()*,;="}))

(defn- test-method-params
  [m u b q p v]
  (let [req (request m u :query-string q)
        r   (:response (app params-routes req))]
    (is (= p (-> r :body :query-params)))
    (is (= v (-> r :body :request-method)))))

(deftest method-params
  (test-method-params :post "/echo" nil "_method=put&q=searchterm" {:q "searchterm"} :put))
