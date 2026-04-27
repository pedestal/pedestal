; Copyright 2024 Nubank NA
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
  (:require [clojure.test :refer [deftest is]]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.interceptor.chain :as chain]
            [matcher-combinators.matchers :as m]
            [ring.util.io :as ringio]
            [io.pedestal.http.params :as params]))

(defn resp [s b & {:as headers}]
  (assoc {:status s :body b} :headers headers))

(defn okpage [_request] (resp 200 "OK"))
(defn okpage-html [_request] (resp 200 "OK" "Content-Type" "text/html"))
(defn echo [request] (resp 200 request))

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

(defn form-post [uri body & {:as params}]
  (let [body' (or body "")]
    (merge
      (request :post
               uri
               :headers {"content-type"   "application/x-www-form-urlencoded"
                         "content-length" (str (count body'))}
               :content-type "application/x-www-form-urlencoded"
               :body (ringio/string-input-stream body'))
      params)))

(def not-found-routes
  (table/table-routes
    {}
    [["/users" :get (fn [_request] (resp 200 "OK")) :route-name :users]]))

(deftest not-found
  (is (= 404 (-> (app not-found-routes (request :get "no-such-url")) (get-in [:response :status])))))

(def content-type-routes
  (table/table-routes
    {}
    [["/users" :get okpage :route-name :users]
     ["/content-type-from-extension.json" :get okpage :route-name :json-index]
     ["/content-type-from-extension.edn" :get okpage :route-name :edn-index]
     ["/content-type-from-handler" :get okpage-html :route-name :page-html]]))

(deftest content-type
  (is (match? {:response {:status  200
                          :headers {"Content-Type" "application/json"}}}
              (app content-type-routes (request :get "/content-type-from-extension.json"))))

  (is (match? {:response {:status  404
                          :headers {"Content-Type" m/absent}}}
              (app content-type-routes (request :get "/no-such-url"))))

  (is (match? {:response {:status  200
                          :headers {"Content-Type" "application/edn"}}}
              (app content-type-routes (request :get "/content-type-from-extension.edn"))))

  (is (match? {:response {:status  200
                          :headers {"Content-Type" "text/html"}}}
              (app content-type-routes (request :get "/content-type-from-handler")))))

(def params-routes
  (table/table-routes
    {}
    [["/echo" :any echo :route-name :echo]
     ["/pecho/:user-id" :get [echo] :route-name :pecho]
     ["/fecho" :post [(body-params/body-params) params/keyword-params echo] :route-name :echo-form]]))

(defn- param-get [u q] (request :get u :query-string q))
(defn- param-post [u b q] (form-post u b :query-string q))

(deftest query-params
  (is (match? {:response {:status 200
                          :body   {:query-params {:q "searchterm"}
                                   :params       {:q "searchterm"}}}}
              (app params-routes (param-get "/echo" "q=searchterm"))))

  (is (match? {:response {:status 200
                          :body   {:query-params {:b "2" :c " "}
                                   :params       {:b "2" :c " "}}}}
              (app params-routes (param-get "/echo" "c=%20&b=2"))))

  (is (match? {:response {:status 200
                          :body   {:query-params {:a "b"}
                                   :params       {:a "b"}
                                   ;; Prior to 0.8.2., this was an empty list, but
                                   ;; PR #1006 changed the missing body logic to not invoke
                                   ;; body parsers at all.
                                   :form-params  m/absent}}}
              (app params-routes (param-post "/fecho" nil "a=b"))))

  (is (match? {:response {:status 200
                          :body   {:query-params {:c "d"}
                                   :params       {:c "d" :foo "bar"}
                                   :form-params  {:foo "bar"}}}}
              (app params-routes (param-post "/fecho" "foo=bar" "c=d")))))

(deftest path-params
  (is (match? {:response {:body {:path-params {:user-id "john doe"}}}}
              (app params-routes (request :get "/pecho/john%20doe"))))

  (is (match? {:response {:body {:path-params {:user-id "♠♥♦♣"}}}}
              (app params-routes (request :get "/pecho/%E2%99%A0%E2%99%A5%E2%99%A6%E2%99%A3"))))

  (is (match? {:response {:body {:path-params {:user-id "abcdefghiklmnopqrstuvwxyz0123456789-._~"}}}}
              (app params-routes (request :get "/pecho/abcdefghiklmnopqrstuvwxyz0123456789-._~"))))

  (is (match? {:response {:body {:path-params {:user-id ":#[]@"}}}}
              (app params-routes (request :get "/pecho/:#[]@"))))

  (is (match? {:response {:body {:path-params {:user-id "!$&'()*,;="}}}}
              (app params-routes (request :get "/pecho/!$&'()*,;=")))))

(deftest method-params
  (is (match? {:response {:body {:query-params   {:q "searchterm"}
                                 :request-method :put}}}
              (app params-routes (request :post "/echo" :query-string "_method=put&q=searchterm")))))
