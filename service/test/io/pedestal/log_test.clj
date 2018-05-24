(ns io.pedestal.log-test
  (:require [clojure.test :refer :all]
            [io.pedestal.log :as log]))

(deftest mdc-context-set-correctly
  (let [inner-value (atom nil)
        unwrapped-value (atom nil)]
    (log/with-context {:a 1}
      (log/with-context {:b 2}
        (log/info :msg "See the MDC in action")
        (reset! inner-value log/*mdc-context*))
      (log/info :msg "More MDC goodness")
      (reset! unwrapped-value log/*mdc-context*))
    (is (= {:a 1 :b 2}
           @inner-value))
    (is (= {:a 1}
           @unwrapped-value))))

