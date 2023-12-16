(ns io.pedestal.metrics.codahale-test
  (:require [io.pedestal.metrics :as metrics]
            [io.pedestal.metrics.codahale :as c]
            [clojure.test :refer [deftest is use-fixtures]]))

(defn registry-fixture
  [f]
  (reset! *now (System/nanoTime))
  (try
    (binding [metrics/*default-metric-source* (c/wrap-registry (c/default-registry))]
      (f))))

(use-fixtures :each registry-fixture)

(defn- get-counter
  [metric-name]
  (c/get-counter metrics/*default-metric-source* metric-name))

(defn- get-gauge
  [metric-name]
  (c/get-gauge metrics/*default-metric-source* metric-name))

(defn- get-timer
  [metric-name]
  (c/get-timer metrics/*default-metric-source* metric-name))

(deftest counter-by-keyword-and-string-name-are-the-same
  (let [metric-name "foo.bar.baz"
        f1          (metrics/counter (keyword metric-name) nil)
        f2          (metrics/counter metric-name nil)
        counter     (get-counter metric-name)]
    (is (= 0 (.getCount counter)))

    (f1)

    (is (= 1 (.getCount counter)))

    (f2 5.0)

    (is (= 6 (.getCount counter)))))

(deftest gauge-monitor
  (let [*monitored  (atom [])
        value-fn    #(count @*monitored)
        metric-name ::gauge
        _           (metrics/gauge metric-name nil value-fn)
        gauge       (get-gauge metric-name)]

    (is (= 0 (.getValue gauge)))

    (swap! *monitored conj 0)

    (is (= 1 (.getValue gauge)))

    (swap! *monitored conj 1 2 3)

    (is (= 4 (.getValue gauge)))))

(deftest duplicate-gauge-ignored
  (let [*monitored  (atom [])
        value-fn    #(count @*monitored)
        metric-name ::gauge
        _           (metrics/gauge metric-name nil value-fn)
        gauge       (get-gauge metric-name)]

    (is (= 0 (.getValue gauge)))

    (swap! *monitored conj 0)

    (is (= 1 (.getValue gauge)))

    (metrics/gauge metric-name nil (constantly 9999))

    (is (= 1 (.getValue gauge)))

    (swap! *monitored conj 1 2 3)

    (is (= 4 (.getValue gauge)))))

(deftest timer
  (let [metric-name ::db-read
        timer-fn    (metrics/timer metric-name nil)
        timer       (get-timer metric-name)
        stop-fn     (timer-fn)]
    (swap! *now + 15e8)
    (stop-fn)

    (is (= 1 (.getCount timer)))

    ;; It's idempotent - this is an assurance added by io.pedestal.metrics.micrometer.

    (stop-fn)

    ;; Only check the count, because the observed values are inside a histogram.

    (is (= 1 (.getCount timer)))))


(deftest parallel-timers
  (let [metric-name ::db-read
        timer-fn    (metrics/timer metric-name nil)
        timer       (get-timer metric-name)
        ;; Both start at the "same" time
        stop-fn-1   (timer-fn)
        stop-fn-2   (timer-fn)]
    (swap! *now + 15e8)
    (stop-fn-1)

    (let [after-fn1 (-> timer .getSnapshot .getMax)]
      (swap! *now + 25e8)

      (stop-fn-2)

      (is (= 2 (.getCount timer)))

      (is (< after-fn1 (-> timer .getSnapshot .getMax))
          "The timer has tracked additional time"))))
