; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.removals-test
  "Pedestal 1.0 deletes everything that Pedestal 0.8.3 deprecated. Using a removed namespace or var
  fails with Clojure's own error. Only three removed options throw an error of their own (they would
  otherwise be silently ignored, and change the behavior): it says what was removed, what to use
  instead, and links to its section of the deprecations page."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [io.pedestal.connector.test :as connector.test]
            [io.pedestal.http :as-alias http]
            [io.pedestal.http.body-params :as body-params]
            io.pedestal.http.impl.servlet-interceptor
            [io.pedestal.http.jetty :as jetty]
            io.pedestal.http.response
            [io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition.verbose :as verbose]
            io.pedestal.http.route.prefix-tree
            io.pedestal.http.sse
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.internal :as i]
            [io.pedestal.service.protocols :as p]
            io.pedestal.websocket)
  (:import (clojure.lang Cons IPersistentList PersistentList Symbol)
           (java.io FileNotFoundException)))

(def ^:private deprecations-url "https://pedestal.io/pedestal/1.0/deprecations.html#")

(defn ^:private root-cause
  [t]
  (if-let [c (ex-cause t)]
    (recur c)
    t))

;; Removed namespaces: their source is gone, so require fails with Clojure's error

(defn ^:private check-removed-namespace
  [ns-sym]
  (let [path (-> ns-sym name (string/replace "-" "_") (string/replace "." "/"))]
    (testing (str "require " ns-sym)
      (is (nil? (io/resource (str path ".clj"))))
      (is (nil? (io/resource (str path "__init.class"))))
      ;; It fails every time, not only the first time.
      (dotimes [_ 2]
        (when-let [e (is (thrown? FileNotFoundException (require ns-sym)))]
          (is (string/starts-with? (ex-message e) (str "Could not locate " path "__init.class")))))
      (is (not (contains? (loaded-libs) ns-sym))))))

(deftest io-pedestal-http-is-removed
  (check-removed-namespace 'io.pedestal.http)
  (testing "its keywords can still be used, through :as-alias"
    (is (= :io.pedestal.http/routes ::http/routes))))

(deftest io-pedestal-test-is-removed
  (check-removed-namespace 'io.pedestal.test))

(deftest io-pedestal-http-jetty-util-is-removed
  (check-removed-namespace 'io.pedestal.http.jetty.util))

(deftest io-pedestal-http-specs-is-removed
  ;; Not deprecated in 0.8.3, but it only specified the removed service map API.
  (check-removed-namespace 'io.pedestal.http.specs))

;; Removed vars: their namespace still loads, but they don't resolve

(defn ^:private check-removed-var
  [qualified-sym]
  (testing (str qualified-sym)
    (is (contains? (loaded-libs) (symbol (namespace qualified-sym))))
    (is (nil? (resolve qualified-sym)))
    (when-let [e (is (thrown? Throwable (eval (list qualified-sym))))]
      (is (= (str "No such var: " qualified-sym)
             (ex-message (root-cause e)))))))

(deftest sse-start-event-stream-and-sse-setup-are-removed
  (check-removed-var 'io.pedestal.http.sse/start-event-stream)
  (check-removed-var 'io.pedestal.http.sse/sse-setup)
  (testing "the replacement"
    (is (fn? @(resolve 'io.pedestal.http.sse/start-stream)))))

(deftest websocket-add-endpoint-and-add-endpoints-are-removed
  (check-removed-var 'io.pedestal.websocket/add-endpoint)
  (check-removed-var 'io.pedestal.websocket/add-endpoints))

(deftest prefix-tree-add-satisfies-constraints-is-removed
  (check-removed-var 'io.pedestal.http.route.prefix-tree/add-satisfies-constraints?))

(deftest response-edn-response-is-removed
  (check-removed-var 'io.pedestal.http.response/edn-response))

(deftest servlet-interceptor-exception-debug-is-removed
  (check-removed-var 'io.pedestal.http.impl.servlet-interceptor/exception-debug)
  (testing "it was a value; using it as a value fails too"
    (when-let [e (is (thrown? Throwable (eval 'io.pedestal.http.impl.servlet-interceptor/exception-debug)))]
      (is (= "No such var: io.pedestal.http.impl.servlet-interceptor/exception-debug"
             (ex-message (root-cause e)))))))

(deftest jetty-server-is-removed
  ;; Not deprecated in 0.8.3, but only the removed service map API used it.
  (check-removed-var 'io.pedestal.http.jetty/server)
  (testing "its spec is gone too"
    (require 'io.pedestal.http.jetty.specs)
    (is (nil? ((requiring-resolve 'clojure.spec.alpha/get-spec) `jetty/server)))
    (is (some? ((requiring-resolve 'clojure.spec.alpha/get-spec) `jetty/create-connector)))))

;; Removed options: each throws an error that says what to use instead

(defn ^:private check-removal
  "Checks the root cause of the exception: its message says that what was removed (noun) was removed
  in Pedestal 1.0, contains each of the replacement's key words, and links to the anchor.
  Returns the root cause."
  [e noun replacement-words anchor]
  (let [root    (root-cause e)
        message (ex-message root)]
    (is (string/starts-with? message (str noun " was removed in Pedestal 1.0. ")))
    (doseq [words replacement-words]
      (is (string/includes? message words)))
    (is (string/ends-with? message (str " See " deprecations-url anchor)))
    (is (= noun (::i/removed (ex-data root))))
    root))

(defmacro ^:private removal
  "Asserts that the body throws, and checks the removal message of its root cause; see [[check-removal]]."
  [noun replacement-words anchor & body]
  `(when-let [e# (is (~'thrown? Throwable ~@body))]
     (check-removal e# ~noun ~replacement-words ~anchor)))

(deftest custom-json-parser-array-coerce-fn-is-removed
  (removal "The :array-coerce-fn option of io.pedestal.http.body-params/custom-json-parser"
           [":value-fn"]
           "array-coerce-fn"
           (body-params/custom-json-parser :array-coerce-fn (constantly [])))
  (testing "the replacement option"
    (is (fn? (body-params/custom-json-parser :value-fn (fn [_ v] v))))))

(def ^:private connector-map
  {:port            0
   :host            "localhost"
   :interceptors    []
   :initial-context {}
   :join?           false})

(deftest jetty-insecure-ssl-is-removed
  ;; Passing the key throws, whatever its value.
  (doseq [value [true false nil]]
    (testing (str ":insecure-ssl? " value)
      (removal "The :insecure-ssl? Jetty container option"
               [":sni-host-check?" ":insecure-ssl? true becomes :sni-host-check? false"]
               "insecure-ssl"
               (jetty/create-connector connector-map {:container-options {:insecure-ssl? value}})))))

(deftest jetty-non-routed-websockets-are-removed
  ;; Passing the key throws, whatever its value.
  (doseq [value [{"/ws" {}} nil]]
    (testing (str ":websockets " (pr-str value))
      (removal "Support for non-routed WebSockets (the :websockets key of the connector map)"
               ["io.pedestal.service.websocket/websocket-interceptor" "routed WebSockets"]
               "websockets"
               (jetty/create-connector (assoc connector-map :websockets value) {})))))

(deftest jetty-connector-without-removed-options-is-created
  ;; Control for the two tests above.
  (doseq [options [{}
                   {:container-options {:sni-host-check? false}}]]
    (testing (pr-str options)
      (let [connector (jetty/create-connector (assoc connector-map
                                                     :interceptors [(interceptor/interceptor
                                                                      {:name  ::hello
                                                                       :enter #(assoc % :response {:status 200 :body "hello"})})])
                                              options)]
        (is (satisfies? p/PedestalConnector connector))
        (is (match? {:status 200 :body "hello"}
                    (connector.test/response-for connector :get "/")))))))

;; Removed protocol method

(deftest writeable-body-default-content-type-is-removed
  (is (= #{:write-body-to-stream}
         (-> io.pedestal.http.impl.servlet-interceptor/WriteableBody :sigs keys set)))
  (is (nil? (resolve 'io.pedestal.http.impl.servlet-interceptor/default-content-type)))
  (testing "an implementation that still defines it fails to compile"
    (is (thrown? Throwable
                 (eval '(deftype StillHasDefaultContentType []
                          io.pedestal.http.impl.servlet-interceptor/WriteableBody
                          (default-content-type [_] "text/plain")
                          (write-body-to-stream [_ _])))))))

;; Quoted route specifications: symbols are no longer resolved, and lists no longer evaluated.
;; Neither protocol is extended to symbols or lists any more.

(def ^:private unsupported-interceptor
  "You're trying to use something as an interceptor that isn't supported by the protocol")

(deftest the-protocols-are-not-extended-to-symbols-or-lists
  (doseq [t [Symbol IPersistentList Cons]]
    (is (not (contains? (:impls interceptor/IntoInterceptor) t)) (str t)))
  (doseq [t [Symbol IPersistentList]]
    (is (not (contains? (:impls @(requiring-resolve 'io.pedestal.http.route.definition.terse/ExpandableVerbAction)) t))
        (str t))))

(deftest interceptor-from-a-symbol-is-removed
  (when-let [e (is (thrown? Exception (interceptor/interceptor 'clojure.core/identity)))]
    (is (string/starts-with? (ex-message e) unsupported-interceptor))
    (is (= {:t 'clojure.core/identity :type Symbol} (ex-data e)))))

(deftest interceptor-from-an-expression-is-removed
  (doseq [expression ['(clojure.core/identity 1)
                      (cons `identity (list 1))]]
    (testing (str (type expression))
      (when-let [e (is (thrown? Exception (interceptor/interceptor expression)))]
        (is (string/starts-with? (ex-message e) unsupported-interceptor))
        (is (= expression (:t (ex-data e)))))))
  (is (instance? Cons (cons `identity (list 1)))))

(deftest table-route-with-a-symbol-is-removed
  (doseq [route [["/x" :get 'clojure.core/identity :route-name ::x]
                 ["/x" :get 'clojure.core/identity]]]
    (testing (pr-str route)
      (when-let [e (is (thrown? AssertionError (route/expand-routes #{route})))]
        (is (string/includes? (ex-message e)
                              "In row 1, the handler should have been an interceptor but was clojure.core/identity"))))))

(deftest terse-route-with-a-symbol-verb-action-is-removed
  (when-let [e (is (thrown? IllegalArgumentException
                            (route/expand-routes [[["/x" {:get 'clojure.core/identity}]]])))]
    (is (string/starts-with? (ex-message e) "No implementation of method: :expand-verb-action"))
    (is (string/ends-with? (ex-message e) (str "found for class: " (.getName Symbol))))))

(deftest terse-route-with-a-symbol-in-the-vector-form-is-removed
  (when-let [e (is (thrown? Exception (route/expand-routes [[["/x" {:get [:x 'clojure.core/identity]}]]])))]
    (is (string/starts-with? (ex-message (root-cause e)) unsupported-interceptor))))

(deftest terse-route-with-an-expression-is-removed
  (when-let [e (is (thrown? IllegalArgumentException
                            (route/expand-routes [[["/x" {:get '(clojure.core/identity 1)}]]])))]
    (is (string/starts-with? (ex-message e) "No implementation of method: :expand-verb-action"))
    (is (string/ends-with? (ex-message e) (str "found for class: " (.getName PersistentList))))))

(deftest verbose-route-with-a-symbol-handler-is-removed
  (doseq [verbs [{:get 'clojure.core/identity}
                 {:get {:handler 'clojure.core/identity}}]]
    (testing (pr-str verbs)
      (when-let [e (is (thrown? Exception
                                (verbose/expand-verbose-routes [{:children [{:path  "/x"
                                                                             :verbs verbs}]}])))]
        (is (string/starts-with? (ex-message (root-cause e)) unsupported-interceptor))
        (is (= 'clojure.core/identity (:t (ex-data (root-cause e)))))))))
