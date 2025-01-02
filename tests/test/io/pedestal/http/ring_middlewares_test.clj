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

(ns io.pedestal.http.ring-middlewares-test
  (:require [io.pedestal.http.ring-middlewares :as m]
            [io.pedestal.interceptor :as i :refer [valid-interceptor?]]
            [ring.middleware.session.memory :as memory]
            [io.pedestal.interceptor.chain :as chain]
            [clojure.test :refer [deftest is]]
            [io.pedestal.http.tracing :refer [mark-routed]]
            [ring.util.io :as rio]
            [matcher-combinators.matchers :as matchers]
            [ring.middleware.session.store :as store])
  (:import (java.util UUID)))

(def app
  (i/interceptor
    {:name  ::app
     :enter (fn [{:keys [response request]
                  :as   context}]
              (assoc context :response (or (::fixed-response request)
                                           response
                                           (merge request {:status 200 :body "OK"}))))}))

(defn context
  [partial-request]
  {:request (merge {:headers {} :request-method :get} partial-request)})

(defn execute
  [partial-request & interceptors]
  (doseq [interceptor interceptors]
    (is (valid-interceptor? interceptor)))
  (chain/execute (context partial-request) interceptors))

(deftest content-type-is-valid
  (is (match?
        {:response
         {:headers
          {"Content-Type" "application/json"}}}
        (execute {:uri "/index.json"}
                 (m/content-type)
                 app)))

  (is (nil? (->
              (execute {:uri "/foo"}
                       (m/content-type)
                       app)
              (get-in [:response :headers "Content-Type"])))))

(deftest cookies-is-valid
  (is (match?
        {:response
         {:headers {"Set-Cookie" ["a=b"]}}}
        (execute {:headers {"cookie" "a=b"}}
                 m/cookies
                 app))))

(defn mock-mark-routed
  [*route-name]
  (fn [context route-name]
    (reset! *route-name route-name)
    context))

(defmacro with-mark-routed
  [expected-route-name & body]
  `(let [*route-name# (atom nil)]
     (with-redefs [mark-routed (mock-mark-routed *route-name#)]
       ~@body)
     (is (= ~expected-route-name @*route-name#))))

(deftest file-is-valid
  (with-mark-routed
    :file
    (is (= "<h1>WOOT!</h1>\n"
           (-> (execute {:uri "/"}
                        (m/file "test/io/pedestal/public")
                        app)
               (get-in [:response :body])
               slurp)))))

(deftest file-info-is-valid
  (is (match?
        {:response
         {:headers
          {"Content-Type" "text/html"}}}
        (execute {:uri "/"}
                 (m/file "test/io/pedestal/public")
                 (m/file-info)
                 app))))

(deftest flash-is-valid
  (let [expected-message "This is the flash message"
        flash            (m/flash)
        flash-leave      (:leave flash)
        flash-response   (-> {:response {:flash expected-message}}
                             flash-leave
                             :response)]
    (is (= expected-message
           (-> (execute flash-response (m/flash))
               (get-in [:request :flash]))))))

(deftest head-is-valid
  (is (match?
        {:request
         {:request-method :get}
         :response {:status 200
                    :body   nil}}
        (execute {:request-method :head}
                 (m/head)
                 app))))

(deftest keyword-params-is-valid
  (is (match?
        {:request
         {:params
          {:a "1" :b "2"}}}
        (execute {:params {"a" "1" "b" "2"}}
                 m/keyword-params))))

(defn- string-store [item]
  (-> (select-keys item [:filename :content-type])
      (assoc :content (slurp (:stream item)))))

(deftest multipart-params-is-valid
  (let [form-body (str "--XXXX\r\n"
                       "Content-Disposition: form-data;"
                       "name=\"foo\"\r\n\r\n"
                       "bar\r\n"
                       "--XXXX\r\n"
                       "Content-Disposition: form-data;"
                       "name=\"foo\"\r\n\r\n"
                       "baz\r\n"
                       "--XXXX--")
        request   {:headers      {"content-type"   "multipart/form-data; boundary=XXXX"
                                  "content-length" (str (count form-body))}
                   :content-type "multipart/form-data; boundary=XXXX"
                   :body         (rio/string-input-stream form-body)}]
    (is (match?
          {:request
           {:multipart-params
            {"foo" ["bar" "baz"]}}}
          (execute request (m/multipart-params {:store string-store}))))))

(deftest nested-params-is-valid
  (is (match?
        {:request
         {:params
          {"foo" {"bar" "baz"}}}}
        (execute {:params {"foo[bar]" "baz"}} (m/nested-params)))))

(deftest not-modified-is-valid
  (is (match?
        {:response
         {:status  304
          ;; This was present in earlier version of Ring, but in Ring 1.3
          ;; it was removed (https://github.com/ring-clojure/ring/issues/509):
          :headers {"Content-Length" matchers/absent}
          :body    nil}}
        (execute
          {:headers         {"if-none-match" "42"}
           :request-method  :get
           ::fixed-response {:headers {"etag" "42"}
                             :status  200}} (m/not-modified) app))))

(deftest params-is-valid
  (is (match?
        {:request
         {:params {"a" "1" "b" "2"}}}
        (execute {:query-string "a=1&b=2"} (m/params) app))))

(deftest resource-is-valid
  (with-mark-routed :resource
                    (is (= "<h1>WOOT!</h1>\n"
                           (-> (execute {:uri "/index.html"}
                                        (m/resource "/io/pedestal/public"))
                               :response
                               :body
                               slurp)))))

(deftest fast-resource-is-valid
  (with-mark-routed :fast-resource
                    (is (= "<h1>WOOT!</h1>\n"
                           (-> (execute {:uri "/index.html"}
                                        (m/fast-resource "/io/pedestal/public"))
                               :response
                               :body
                               slurp)))))

(deftest fast-resource-passes-on-post
  (with-mark-routed nil
                    (is (= nil
                           (-> (execute {:uri            "/index.html"
                                         :request-method :post}
                                        (m/fast-resource "/io/pedestal/public"))
                               :response)))))

(deftest session-is-valid
  (let [session-key    (str (UUID/randomUUID))
        session-data   {:bar "foo"}
        store          (memory/memory-store (atom {session-key session-data}))
        session-cookie (str "ring-session=" session-key)]
    (is (match?
          {:request
           {:session     session-data
            :session/key session-key}
           :response
           {:headers {"Set-Cookie" [session-cookie]}}}
          (execute {:headers {"cookie" session-cookie}}
                   (m/session {:store store})
                   app)))))

(def delete-session
  (i/interceptor
    {:name  ::delete-session
     :leave #(update % :response assoc :session nil)}))

(deftest session-after-deletion
  (let [session-key    (str (UUID/randomUUID))
        session-data   {:bar "foo"}
        ;; Because memory-store returns nil, the old session-key will still be
        ;; used, but we'll check that the data for the session was removed.
        store          (memory/memory-store (atom {session-key session-data}))
        session-cookie (str "ring-session=" session-key)]
    (is (match?
          {:request
           {:session     session-data
            :session/key session-key}
           :response
           ;; Ensure that a new session id was created.
           {:headers {"Set-Cookie" [session-cookie]}}}
          (execute {:headers {"cookie" session-cookie}}
                   (m/session {:store store})
                   delete-session
                   app)))
    ;; Show that the data for the session was removed.
    (is (nil? (store/read-session store session-key)))))
