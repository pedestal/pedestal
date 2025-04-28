; Copyright 2025 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.connector.dev-mode-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.http.http-kit :as hk]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.connector :as connector]
            [io.pedestal.connector.dev :as dev]
            [clojure.pprint :refer [pprint]]
            [ring.util.response :refer [response]]
            [io.pedestal.connector.test :as test]))

(defn hello-page
  [_request]
  (response "HELLO"))

(defn echo-origin
  [request]
  (response {:origin (get-in request [:headers "origin"])}))

(defn fail-page
  [_request]
  (throw (IllegalStateException. "Gentlemen, failure is not an option.")))

(def routes
  (table/table-routes {}
                      [["/hello" :get hello-page :route-name ::hello]
                       ["/echo/origin" :get echo-origin :route-name ::echo-origin]
                       ["/fail" :get fail-page :route-name ::fail]]))

(defn new-connector
  []
  (-> (connector/default-connector-map 8080)
      (connector/with-interceptors dev/dev-interceptors)
      (connector/with-default-interceptors)
      (dev/with-interceptor-observer {:omit          dev/default-debug-observer-omit
                                      :changes-only? true
                                      :tap?          true})
      (connector/with-routes routes)
      (hk/create-connector nil)))

(def *connector (atom nil))

(use-fixtures :once
              (fn [f]
                ;; This gets set up in user.clj (part of the trace support) and causes lots of
                ;; unwanted output.
                (remove-tap pprint)
                (try
                  (reset! *connector (new-connector))
                  (f)
                  (finally
                    (reset! *connector nil)))))

(def *taps (atom []))

(use-fixtures :each
              (let [tapper (fn [tapped-value]
                             (swap! *taps conj tapped-value))]
                (fn [f]
                  (add-tap tapper)
                  (try
                    (f)
                    (finally
                      (swap! *taps empty)
                      (remove-tap tapper))))))

(defn response-for
  [request-method url & {:as options}]
  (test/response-for @*connector request-method url options))

(deftest debug-observer-is-active
  (is (match? {:status 200
               :headers {:content-type "text/plain"}
               :body "HELLO"}
              (response-for :get "/hello")))

  ;; Just want to verify that *some* taps occurred. Going into more detail
  (is (seq @*taps)
      "some context changes were broadcast by the debug-observer"))

(deftest empty-string-default-for-origin
  (is (match? {:status 200
               ;; Note: empty string, not null, due to dev-allow-origin
               :body   "{:origin \"\"}"}
              (response-for :get "/echo/origin"))))

(deftest uncaught-exception-reporting
  (let [response (response-for :get "/fail")
        {:keys [body]} response]
    (is (match? {:status 500
                 :headers {:content-type "text/plain"}}
                response))

    ;; The full output is quite verbose, but these parts indicate that the exception has been formatted
    ;; using org.clj-commons/pretty.

    (is (string/includes? body "Error processing request!"))
    (is (string/includes? body "io.pedestal.connector.dev-mode-test/fail-page"))
    (is (string/includes? body "java.lang.IllegalStateException: Gentlemen, failure is not an option"))))
