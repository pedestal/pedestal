(ns io.pedestal.metrics-test
  (:require [io.pedestal.metrics :as metrics]
            [io.pedestal.metrics.micrometer :as mm]
            [clojure.test :refer [deftest is use-fixtures]])
  (:import (io.micrometer.core.instrument MeterRegistry)))

(def ^:dynamic ^MeterRegistry *registry* nil)

(defn registry-fixture
  [f]
  (try
    (let [registry (mm/default-registry)]
      (binding [*registry*                      registry
                metrics/*default-metric-source* (mm/wrap-registry registry)]
        (f)))))

(use-fixtures :each registry-fixture)

(defn- get-counter
  [metric-name tags]
  (-> (.get *registry* (mm/convert-metric-name metric-name))
      (.tags (mm/iterable-tags metric-name tags))
      .counter))

(defn- get-gauge
  [metric-name tags]
  (-> (.get *registry* (mm/convert-metric-name metric-name))
      (.tags (mm/iterable-tags metric-name tags))
      .gauge))


(deftest counter-by-keyword-and-string-name-are-the-same
  (let [metric-name "foo.bar.baz"
        f1          (metrics/counter (keyword metric-name) nil)
        f2          (metrics/counter metric-name nil)
        counter     (get-counter metric-name nil)]
    (is (= 0.0 (.count counter)))

    (f1)

    (is (= 1.0 (.count counter)))

    (f2 5)

    (is (= 6.0 (.count counter)))))

(deftest counter-by-keyword-or-symbol-are-the-same
  (let [metric-sym 'foo.bar/baz
        metric-kw  :foo.bar/baz
        f-sym      (metrics/counter metric-sym nil)
        f-kw       (metrics/counter metric-kw nil)
        counter    (get-counter metric-kw nil)]
    (is (= 0.0 (.count counter)))

    (f-sym)
    (f-kw)

    (is (= 2.0 (.count counter)))))

(deftest counter-with-non-equivalent-tags-are-unique
  (let [tags-foo    {:url "/foo"}
        tags-bar    {:url "/bar"}
        f-foo       (metrics/counter :my.metric tags-foo)
        foo-counter (get-counter :my.metric tags-foo)
        f-bar       (metrics/counter :my.metric tags-bar)
        bar-counter (get-counter :my.metric tags-bar)]
    (= 0.0 (.count foo-counter))
    (= 0.0 (.count bar-counter))

    (f-foo)

    (= 1.0 (.count foo-counter))
    (= 0.0 (.count bar-counter))

    (f-bar 9.0)

    (= 1.0 (.count foo-counter))
    (= 9.0 (.count bar-counter))

    (is (= "my.metric"
           (->> foo-counter .getId .getName)))
    (is (= ["tag(url=/foo)"]
           (->> foo-counter .getId .getTags (mapv str))))))

(deftest increment-counter
  (let [metric-name ::counter
        tags        {:method :post
                     :url    "/api"}
        _           (metrics/counter metric-name tags)
        counter     (get-counter metric-name tags)]
    (is (= 0.0 (.count counter)))

    (metrics/increment-counter metric-name tags)

    (is (= 1.0 (.count counter)))

    (metrics/advance-counter metric-name tags 4.0)

    (is (= 5.0 (.count counter)))))

(deftest valid-tag-keys-and-values
  (is (= ["tag(string-key=string-value)"
          "tag(io.pedestal.metrics-test/symbol-key=io.pedestal.metrics-test/symbol-value)"
          "tag(io.pedestal.metrics-test/keyword-key=io.pedestal.metrics-test/keyword-value)"
          "tag(key-long=1234)"
          "tag(key-double=3.14)"
          "tag(key-boolean=true)"]
         (->> (mm/iterable-tags :does.not.matter
                                {"string-key"  "string-value"
                                 `symbol-key   `symbol-value
                                 ::keyword-key ::keyword-value
                                 :key-long     1234
                                 :key-double   3.14
                                 :key-boolean  true})
              (mapv str)))))

(deftest invalid-tag-key
  (when-let [e (is (thrown-with-msg? Exception #"Exception building tags .* \QInvalid Tag key type: java.lang.Long\E"
                                     (mm/iterable-tags ::metric {37 :long})))]
    (is (= {:metric-name ::metric
            :tags        {37 :long}}
           (ex-data e)))))

(deftest invalid-tag-value
  (when-let [e (is (thrown-with-msg? Exception #"Exception building tags .* \QInvalid Tag value type: clojure.lang.PersistentVector\E"
                                     (mm/iterable-tags ::metric {:kw []})))]
    (is (= {:metric-name ::metric
            :tags        {:kw []}}
           (ex-data e)))))

(deftest gauge-monitor
  (let [*monitored  (atom [])
        value-fn    #(count @*monitored)
        metric-name ::gauge
        tags        {::this :that}
        _           (metrics/gauge metric-name tags value-fn)
        gauge       (get-gauge metric-name tags)]

    (is (= 0.0 (.value gauge)))

    (swap! *monitored conj 0)

    (is (= 1.0 (.value gauge)))

    (swap! *monitored conj 1 2 3)

    (is (= 4.0 (.value gauge)))))

(deftest duplicate-gauge-ignored
  (let [*monitored  (atom [])
        value-fn    #(count @*monitored)
        metric-name ::gauge
        tags        {::this :that}
        _           (metrics/gauge metric-name tags value-fn)
        gauge       (get-gauge metric-name tags)]

    (is (= 0.0 (.value gauge)))

    (swap! *monitored conj 0)

    (is (= 1.0 (.value gauge)))

    (metrics/gauge metric-name tags (constantly 9999))

    (is (= 1.0 (.value gauge)))

    (swap! *monitored conj 1 2 3)

    (is (= 4.0 (.value gauge)))))
