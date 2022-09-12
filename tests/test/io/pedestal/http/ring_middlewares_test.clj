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
  (:use io.pedestal.http.ring-middlewares
        clojure.test
        ring.middleware.session.store))

(defn valid-interceptor? [interceptor]
  (and (every? fn? (remove nil? (vals (select-keys interceptor [:enter :leave :error]))))
       (or (nil? (:name interceptor)) (keyword? (:name interceptor)))
       (some #{:enter :leave} (keys interceptor))))

(defn app [{:keys [response request] :as context}]
  (assoc context :response (or response (merge request {:status 200 :body "OK"}))))

(defn context [req]
  {:request (merge {:headers {}  :request-method :get} req)})

(deftest content-type-is-valid
  (is (valid-interceptor? (content-type)))
  (is (= "application/json"
         (->
          (context {:uri "/index.json"})
          app
          ((:leave (content-type)))
          (get-in [:response :headers "Content-Type"]))))
  (is (nil? (->
              (context {:uri "/foo"})
              app
              ((:leave (content-type)))
              (get-in [:response :headers "Content-Type"])))))

(deftest cookies-is-valid
  (is (valid-interceptor? cookies))
  (is (= (list "a=b")
         (->
          (context {:headers {"cookie" "a=b"}})
          ((:enter cookies))
          app
          ((:leave cookies))
          (get-in [:response :headers "Set-Cookie"])))))

(deftest file-is-valid
  (is (valid-interceptor? (file "public")))
  (is (= "<h1>WOOT!</h1>\n"
         (->
          (context {:uri "/"})
          ((:enter (file "test/io/pedestal/public")))
          app
          (get-in [:response :body])
          slurp))))

(deftest file-info-is-valid
  (is (valid-interceptor? (file-info)))
  (is (= "text/html"
         (->
          (context {:uri "/"})
          ((:enter (file "test/io/pedestal/public")))
          app
          ((:leave (file-info)))
          (get-in [:response :headers "Content-Type"])))))

(deftest flash-is-valid
  (is (valid-interceptor? (flash)))
  (is (= "The flash message"
         (-> {:response {:flash "The flash message"}}
             ((:leave (flash)))
             ; emulate next request
             (#(assoc % :request (:response %)))
             ((:enter (flash)))
             app
             (get-in [:request :flash])))))

(deftest head-is-valid
  (is (valid-interceptor? (head)))
  (is (= :get
         (-> (context {:request-method :head})
             ((:enter (head)))
             app
             (get-in [:request :request-method]))))
  (is (= {:body nil :status 200}
         (-> (context {:request-method :head})
             ((:enter (head)))
             app
             ((:leave (head)))
             (#(select-keys (:response %) [:status :body]))))))

(deftest keyword-params-is-valid
  (is (valid-interceptor? keyword-params))
  (is (= {:a "1" :b "2"}
         (->
          (context {:params {"a" "1" "b" "2"}})
          ((:enter keyword-params))
          app
          (get-in [:request :params])))))

(defn- string-store [item]
  (-> (select-keys item [:filename :content-type])
      (assoc :content (slurp (:stream item)))))

(deftest multipart-params-is-valid
  (is (valid-interceptor? (multipart-params)))
  (let [ctx (context (let [form-body (str "--XXXX\r\n"
                                          "Content-Disposition: form-data;"
                                          "name=\"foo\"\r\n\r\n"
                                          "bar\r\n"
                                          "--XXXX\r\n"
                                          "Content-Disposition: form-data;"
                                          "name=\"foo\"\r\n\r\n"
                                          "baz\r\n"
                                          "--XXXX--")]
                       {:headers {"content-type" "multipart/form-data; boundary=XXXX"
                                  "content-length" (str (count form-body))}
                        :content-type "multipart/form-data; boundary=XXXX"
                        :body (ring.util.io/string-input-stream form-body)}))
        int-stack ((:enter (multipart-params {:store string-store})) ctx)
        req-ctx (app int-stack)]
  (is (= ["bar" "baz"]
         (get-in req-ctx [:request :multipart-params "foo"])))))

(deftest nested-params-is-valid
  (is (valid-interceptor? (nested-params)))
  (is (= {"foo" {"bar" "baz"}}
         (->
          (context {:params {"foo[bar]" "baz"}})
          ((:enter (nested-params)))
          app
          (get-in [:request :params])))))

(deftest not-modified-is-valid
  (is (valid-interceptor? (not-modified)))
  (is (= 304
         (->
          {:request {:headers {"if-none-match" "42"}
                     :request-method :get}
           :response {:headers {"etag" "42"}
                      :status 200}}
          app
          ((:leave (not-modified)))
          (get-in [:response :status])))))

(deftest params-is-valid
  (is (valid-interceptor? (params)))
  (is (= {"a" "1" "b" "2"}
         (->
          (context {:query-string "a=1&b=2"})
          ((:enter (params)))
          app
          (get-in [:request :params])))))

(deftest resource-is-valid
  (is (valid-interceptor? (resource "public")))
  (is (= "<h1>WOOT!</h1>\n"
         (->
          (context {:uri "/index.html"})
          ((:enter (resource "/io/pedestal/public")))
          app
          (get-in [:response :body])
          slurp))))

(deftest fast-resource-is-valid
  (is (valid-interceptor? (fast-resource "public")))
  (is (= "<h1>WOOT!</h1>\n"
         (->
          (context {:uri "/index.html"})
          ((:enter (fast-resource "/io/pedestal/public")))
          app
          (get-in [:response :body])
          slurp))))

(deftest fast-resource-passes-on-post
  (let [ctx (context {:uri "/index.html" :request-method :post})]
    (is (= ctx ((:enter (fast-resource "/io/pedestal/public")) ctx)))))

(defn- make-store [reader writer deleter]
  (reify SessionStore
    (read-session [_ k] (reader k))
    (write-session [_ k s] (writer k s))
    (delete-session [_ k] (deleter k))))

(deftest session-is-valid
  (is (valid-interceptor? (session)))
  (is (= {:bar "foo"}
         (let [interceptor (session
                            {:store
                             (make-store (constantly {:bar "foo"})
                                         (constantly nil)
                                         (constantly nil))})]
           (->
            (context {})
            ((:enter interceptor))
            app
            (get-in [:request :session])))))
  (is (= '("ring-session=deleted;Path=/;HttpOnly")
         (let [interceptor (session
                            {:store
                             (make-store (constantly {:foo "bar"})
                                         (constantly nil)
                                         (constantly "deleted"))})]
           (->
            (context {:headers {"cookie" "ring-session=foo%3Abar"}})
            ((:enter interceptor))
            ; delete session
            (#(assoc % :response (assoc (:request %) :session nil)))
            ((:leave interceptor))
            (get-in [:response :headers "Set-Cookie"]))))))
