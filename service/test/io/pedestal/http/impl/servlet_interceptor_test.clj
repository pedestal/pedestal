(ns io.pedestal.http.impl.servlet-interceptor-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer :all]
            [io.pedestal.http.impl.servlet-interceptor :as servlet-interceptor]))

(deftest closes-async-body-channel-on-error
  (let [body (async/chan 1)
        write-loop (servlet-interceptor/write-body-async body nil nil nil)]
    (async/>!! body "hello")
    (is (nil? (async/<!! write-loop)))))
