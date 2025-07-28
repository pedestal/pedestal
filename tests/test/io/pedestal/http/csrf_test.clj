; Copyright 2024 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.csrf-test
  (:require [io.pedestal.http :as service]
            [io.pedestal.http.ring-middlewares :as rm]
            [clojure.test :refer [deftest is are testing]]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.test :refer [response-for]]
            [clojure.string :as s]))

(def success-msg "Success!")

(defn terminator
  "An interceptor that creates a valid ring response and places it in
  the context, terminating the interceptor chain."
  [_request]
  {:status 200 :body success-msg :headers {}})

(defn rotate-token-handler
  [_request]
  (csrf/rotate-token {:status 200 :body success-msg :headers {}}))

(def token-sniffer
  "Relay the token to the requester behind the scenes to mimic the
   use of a conventional form with a hidden field."
  {:leave (fn [context]
            (let [token   (get-in context [:response :session "__anti-forgery-token"])
                  headers (merge {} (when token {"csrf-token" token}))]
              (assoc-in context [:response :headers] headers)))})

(def request-handling-routes
  `[[:request-handling "csrf-test.pedestal"
     ["/anti-forgery" ^:interceptors [(rm/session) token-sniffer (csrf/anti-forgery)]
      ["/leaf" {:any [:leaf terminator]}]
      ["/rotate-token" {:any [:rotate-token rotate-token-handler]}]]]])

(defn make-app [options]
  (-> options
      service/default-interceptors
      service/service-fn
      ::service/service-fn))

(def app (make-app {::service/routes request-handling-routes}))

(def url "http://csrf-test.pedestal/anti-forgery/leaf")

(deftest request-method
  (are [status req] (= (:status req) status)
    200 (response-for app :head url)
    200 (response-for app :get url)
    200 (response-for app :options url)
    403 (response-for app :post url)
    403 (response-for app :put url)
    403 (response-for app :patch url)
    403 (response-for app :delete url)))

(deftest success-message-on-get
  (is (= success-msg
         (->> url (response-for app :get) :body))))

(deftest denied-message-on-bad-post
  (is (= csrf/denied-msg
         (->> url (response-for app :post) :body))))

(defn response->cookie&token [{:keys [headers]}]
  (let [cookie (some-> headers (get "Set-Cookie") first (s/split #";") first)
        token  (some-> headers (get "csrf-token"))]
    [cookie token]))

(defn header-data-from-initial-request []
  (-> (response-for app :get url)
      response->cookie&token))

(deftest good-token-is-respected
  (let [[cookie token] (header-data-from-initial-request)
        good-token {"cookie" cookie "x-csrf-token" token}]
    (is (= success-msg
           (:body (response-for app :post url :headers good-token))))))

(deftest bad-token-is-denied
  (let [[cookie token] (header-data-from-initial-request)
        bad-token {"cookie" cookie "x-csrf-token" (apply str (reverse token))}]
    (is (= csrf/denied-msg
           (:body (response-for app :post url :headers bad-token))))))

(defn req-w-hdrs [hdrs]
  (response-for app :post url :headers hdrs))

(deftest x-csrf-token-header-is-respected
  (let [[cookie token] (header-data-from-initial-request)
        headers {"cookie" cookie "x-csrf-token" token}]
    (is (= 200 (-> headers req-w-hdrs :status)))))

(deftest x-xsrf-token-header-is-respected
  (let [[cookie token] (header-data-from-initial-request)
        headers {"cookie" cookie "x-xsrf-token" token}]
    (is (= 200 (-> headers req-w-hdrs :status)))))

(deftest x-nota-token-header-is-denied
  (let [[cookie token] (header-data-from-initial-request)
        headers {"cookie" cookie "x-nota-token" token}]
    (is (= 403 (-> headers req-w-hdrs :status)))))

(defn standalone-anti-forgery-interceptor
  [& options]
  (let [af (apply csrf/anti-forgery options)]
    (comp #((:leave af) %) #((:enter af) %))))

(deftest forms
  (let [i            (standalone-anti-forgery-interceptor)
        k            "__anti-forgery-token"
        form         {:request {:session {k "foo"}}}
        good-form    (assoc-in form [:request :form-params] {k "foo"})
        bad-form     (assoc-in form [:request :form-params] {k "XXX"})
        good-mp-form (assoc-in form [:request :multipart-params] {k "foo"})
        bad-mp-form  (assoc-in form [:request :multipart-params] {k "XXX"})]
    (is (nil? (:response (i good-form))))
    (is (nil? (:response (i good-mp-form))))
    (is (not (nil? (:response (i bad-form)))))
    (is (not (nil? (:response (i bad-mp-form)))))))

(deftest kw-forms
  (let [i            (standalone-anti-forgery-interceptor)
        s            "__anti-forgery-token"
        k            :__anti-forgery-token
        form         {:request {:session {s "foo"}}}
        good-form    (assoc-in form [:request :form-params] {k "foo"})
        bad-form     (assoc-in form [:request :form-params] {k "XXX"})
        good-mp-form (assoc-in form [:request :multipart-params] {k "foo"})
        bad-mp-form  (assoc-in form [:request :multipart-params] {k "XXX"})]
    (is (nil? (:response (i good-form))))
    (is (nil? (:response (i good-mp-form))))
    (is (not (nil? (:response (i bad-form)))))
    (is (not (nil? (:response (i bad-mp-form)))))))

(deftest token-in-session
  (is (apply not= (map second (repeatedly 2 #(header-data-from-initial-request))))))

(deftest custom-error-response
  (let [error-response {:arbitrary :data}
        i              (standalone-anti-forgery-interceptor {:error-response error-response})]
    (testing "custom error-response is respected on errors"
      (is (= error-response
             (:response (i {:request {}})))))
    (testing "custom error-response is ignored on success"
      (let [good-request {:request {:headers {"x-csrf-token" "foo"}
                                    :session {"__anti-forgery-token" "foo"}}}]
        (is (= nil (:response (i good-request))))
        (is (= "foo" (get-in (i good-request)
                             [:request :io.pedestal.http.csrf/anti-forgery-token])))))))

(deftest custom-error-handler
  (let [error-response {:arbitrary :key}
        error-handler  (fn [context] (assoc context :response error-response))
        i              (standalone-anti-forgery-interceptor {:error-handler error-handler})]
    (testing "custom error-handler is respected on errors"
      (is (= error-response
             (:response (i {:request {:request-method :post}})))))))

(deftest disallow-both-error-response-and-error-handler
  (is (thrown?
        AssertionError
        (csrf/anti-forgery {:error-handler  (fn [_request] {:status 500 :body "Handler"})
                            :error-response {:status 500 :body "Response"}}))))

(deftest custom-read-token
  (let [read-token #(get-in % [:headers "x-forgery-token"])
        i          (standalone-anti-forgery-interceptor {:read-token read-token})]
    (testing "custom read token is respected"
      (is (= nil
             (:response (i {:request {:headers {"x-forgery-token" "foo"}
                                      :session {"__anti-forgery-token" "foo"}}})))))
    (testing "conventional read token is denied in presence of custom read token"
      (is (not= nil
                (:response (i {:request {:headers {"x-csrf-token" "foo"}
                                         :session {"__anti-forgery-token" "foo"}}})))))))

(deftest custom-cookie-token
  (let [i       (standalone-anti-forgery-interceptor {:cookie-token true})
        request {:request {:headers {"x-csrf-token" "foo"}
                           :session {"__anti-forgery-token" "foo"}}}]
    (testing "custom read token is respected"
      (is (= {:value "foo"}
             (get-in (i request) [:response :cookies "__anti-forgery-token"]))))))

(deftest custom-cookie-attrs
  (let [cookie-attrs {:path "/sub" :max-age 1234}
        i            (standalone-anti-forgery-interceptor {:cookie-token true
                                                           :cookie-attrs cookie-attrs})
        request      {:request {:headers {"x-csrf-token" "foo"}
                                :session {"__anti-forgery-token" "foo"}}}]
    (testing "custom cookie attrs  is respected"
      (is (= (merge cookie-attrs {:value "foo"})
             (get-in (i request) [:response :cookies "__anti-forgery-token"]))))))

(deftest sessionless-cookie-token
  (let [i       (standalone-anti-forgery-interceptor {:cookie-token true})
        request {:request {}}]
    (testing "get 403 if missing both session and cookie"
      (is (= 403
             (get-in (i request) [:response :status]))))))

(def rotate-token-url "http://csrf-test.pedestal/anti-forgery/rotate-token")

(deftest rotate-token-is-respected
  (let [[cookie token] (header-data-from-initial-request)
        headers {"cookie" cookie "x-csrf-token" token}
        resp    (response-for app :post rotate-token-url :headers headers)
        [cookie2 token2] (response->cookie&token (:headers resp))]
    (is (nil? cookie2))
    (is (not= token token2))))
