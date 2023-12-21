(ns io.pedestal.clojure-var-servlet-test
  (:require [clojure.test :refer [deftest is use-fixtures]])
  (:import (io.pedestal.servlet ClojureVarServlet)
           (jakarta.servlet ServletConfig ServletException ServletRequest ServletResponse)))

(def *events (atom []))

(use-fixtures :each (fn [f]
                      (swap! *events empty)
                      (f)))

(defn- event [& args]
  (swap! *events conj (vec args)))

(defn matched?
  [v]
  (some #(= % v) @*events))

(deftest can-instantiate-servlet
  (is (some? (ClojureVarServlet.))))


(defn- mock-servlet-config
  [init service destroy]
  (let [data {"init"    init
              "service" service
              "destroy" destroy}]
    (reify ServletConfig

      (getInitParameter [_ param-name]
        (get data param-name)))))

(defn init-fn
  [servlet servlet-config]
  (event :init servlet servlet-config))

(defn service-fn
  [servlet request response]
  (event :service servlet request response))

(defn destroy-fn
  [servlet]
  (event :destroy servlet))

(defmacro as-str [v]
  `(-> #'~v .toSymbol str))


(deftest init-and-service
  (let [config  (mock-servlet-config (as-str init-fn) (as-str service-fn) nil)
        req     (reify ServletRequest)
        res     (reify ServletResponse)
        servlet (doto (ClojureVarServlet.)
                  (.init config))]
    (.service servlet req res)
    (.destroy servlet)

    (is (matched? [:init servlet config]))
    (is (matched? [:service servlet req res]))))

(deftest can-omit-init
  (let [config  (mock-servlet-config nil (as-str service-fn) nil)
        req     (reify ServletRequest)
        res     (reify ServletResponse)
        servlet (doto (ClojureVarServlet.)
                  (.init config))]
    (.service servlet req res)
    (.destroy servlet)

    (is (matched? [:service servlet req res]))))

(deftest can-supply-destroy
  (let [config  (mock-servlet-config nil (as-str service-fn) (as-str destroy-fn))
        req     (reify ServletRequest)
        res     (reify ServletResponse)
        servlet (doto (ClojureVarServlet.)
                  (.init config))]
    (.service servlet req res)
    (.destroy servlet)

    (is (matched? [:service servlet req res]))
    (is (matched? [:destroy servlet]))))

(deftest invalid-symbol-name
  (let [config (mock-servlet-config "this-is-not-valid" nil nil)]
    (when-let [e (is (thrown? ServletException
                              (doto (ClojureVarServlet.)
                                (.init config))))]
      (is (= "Invalid namespace-qualified symbol 'this-is-not-valid'" (ex-message e))))))

(deftest namespace-does-not-exist
  (let [config (mock-servlet-config "missing.namespace/does-not-exist" nil nil)]
    (when-let [e (is (thrown? ServletException
                              (doto (ClojureVarServlet.)
                                (.init config))))]
      (is (= "Failed to load namespace 'missing.namespace'" (ex-message e))))))

(deftest service-is-required
  (let [config (mock-servlet-config nil nil nil)]
    (is (thrown-with-msg? ServletException #"Missing required parameter 'service'"
                          (doto (ClojureVarServlet.)
                            (.init config))))))


