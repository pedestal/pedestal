                                                                 ;; tag::ns[]
(ns system-test
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.test :refer [response-for]]
            [com.stuartsierra.component :as component]
            [clojure.test :refer :all]
            routes
            system
            pedestal))
                                                                 ;; end::ns[]

                                                                 ;; tag::url-for[]
(def url-for (route/url-for-routes
              (route/expand-routes routes/routes)))
                                                                 ;; end::url-for[]

                                                                 ;; tag::service-fn[]
(defn service-fn
  [system]
  (get-in system [:pedestal :service ::http/service-fn]))
                                                                 ;; end::service-fn[]

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
  (with-system [sut (system/new-system :test)]                   ;; <1>
    (let [service               (service-fn sut)                 ;; <2>
          {:keys [status body]} (response-for service
                                              :get
                                              (url-for :greet))] ;; <3>
      (is (= 200 status))                                        ;; <4>
      (is (= "Hello, world!" body)))))                           ;; <5>
                                                                 ;; end::test[]
