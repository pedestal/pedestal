;; tag::ns[]
(ns app.system-test
  (:require [io.pedestal.connector.test :refer [response-for]]
            matcher.combinators.test
            [com.stuartsierra.component :as component]
            [clojure.test :refer [deftest is]]
            app.system))
;; end::ns[]
(defn response
  [system method url & more]
  (apply response-for (get-in system [:pedestal :connector]) method url more))
;; tag::with-system[]
(defmacro with-system
  [[bound-var binding-expr] & body]
  `(let [~bound-var (component/start ~binding-expr)]
     (try
       ~@body
       (finally
         (component/stop ~bound-var)))))
;; end::with-system[]
;; tag::test[]
(deftest greeting-test
  (with-system [system (app.system/new-system true)]        ;; <1>
               (is (match?
                     {:status 200
                      :body   "Greetings for the 1st time"}
                     (response system :get "/greet")))))    ;; <5>
;; end::test[]
