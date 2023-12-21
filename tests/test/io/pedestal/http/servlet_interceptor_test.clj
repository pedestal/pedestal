(ns io.pedestal.http.servlet-interceptor-test
  (:require [io.pedestal.http.impl.servlet-interceptor :as si]
            [clojure.test :refer [deftest is]]
            [io.pedestal.http.request :as request])
  (:import (java.io IOException)))

(def create-stylobate @#'si/create-stylobate)

(deftest default-analyzer-returns-exception
  (let [e (RuntimeException.)]
    (is (identical? e
                    (si/default-exception-analyzer nil e)))))

(deftest default-analyzer-returns-nil-for-broken-pipe
  (is (nil?
        (si/default-exception-analyzer nil (IOException. "Broken pipe")))))

(deftest stylobate-calls-provided-analyzer
  (let [*exception (atom nil)
        f          (fn [_ exception]
                     (reset! *exception exception)
                     nil)
        e          (RuntimeException.)
        context    {:this :that}
        stylobate  (create-stylobate {:exception-analyzer f})]
    (with-redefs [request/async-started? (constantly false)]
      (is (identical? context
                      ((:error stylobate) context e)))
      (is (identical? e @*exception)))))
