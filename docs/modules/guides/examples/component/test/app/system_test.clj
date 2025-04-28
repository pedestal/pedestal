;; tag::ns[]
(ns app.system-test
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [io.pedestal.connector.test :refer [response-for]]
            matcher-combinators.test
            [matcher-combinators.matchers :as m]
            [clojure.string :as string]
            app.system))
;; end::ns[]
;; tag::with-system[]
(defmacro with-system
  [[system-sym system-expr] & body]
  `(let [~system-sym (component/start-system ~system-expr)]
     (try
       ~@body
       (finally
         (component/stop-system ~system-sym)))))
;; end::with-system[]
;; tag::response[]
(defn- response
  [system method url & more]
  (apply response-for (get-in system [:pedestal :connector]) method url more))
;; end::response[]
;; tag::test[]
(deftest greeting-test
  (with-system [system (app.system/new-system)]             ;; <1>
               (is (match?                                  ;; <2>
                     {:status 200
                      :body   "Greetings for the 1st time\n"}
                     (response system :get "/api/greet")))  ;; <3>
               ;; end::test[]
               ;; tag::test2[]
               (is (match?
                     {:status 200
                      :body   (m/via string/trim "Greetings for the 2nd time")} ;; <1>
                     (response system :get "/api/greet"))))
  ;; end::test2[]
  )
