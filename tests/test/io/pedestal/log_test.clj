(ns io.pedestal.log-test
  (:require [clojure.test :refer :all]
            [io.pedestal.log :as log]))

(deftest mdc-context-set-correctly
  (let [inner-value (atom nil)
        unwrapped-value (atom nil)
        some-map {:b 2}]
    (log/with-context {:a 1}
      (log/with-context some-map
        (log/info :msg "See the MDC in action")
        (reset! inner-value log/*mdc-context*))
      (log/info :msg "More MDC goodness")
      (reset! unwrapped-value log/*mdc-context*))
    (is (= {:a 1 :b 2}
           @inner-value))
    (is (= {:a 1}
           @unwrapped-value))))

(deftest with-context-expansion
  (let [body ["some" "random" "body"]]
    (testing "a nil context map in with-context doesn't incur macro code-gen overhead"
      (is (= `(do ~@body)
             (macroexpand `(log/with-context nil ~@body)))))

    (testing "providing a variable to with-context generates context-manipulating code"
      (is (not (= `(do ~@body)
                  (macroexpand `(log/with-context some-ctx-map-var ~@body))))))

    (testing "providing a non-empty map to with-context generates context-manipulating code"
      (is (not (= `(do ~@body)
                  (macroexpand `(log/with-context {:extra 'context} ~@body))))))))

(deftest nil-trace-origin
  (is (nil? (log/-span nil "operation-name")))
  (is (nil? (log/-span nil "operation-name" nil)))
  (is (nil? (log/-span nil "operation-name" nil nil))))
