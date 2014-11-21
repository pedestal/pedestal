(ns io.pedestal.http.request-test
  (:require [clojure.test :refer :all]
            [io.pedestal.http.request :refer :all]))

(deftest test-classify-keys
  (is (= :normal   (classify-keys [:foo :bar])))
  (is (= :delayed  (classify-keys [:foo (delay :bar)])))
  (is (= :realized (classify-keys [:foo (doto (delay :bar) deref)]))))

(deftest test-equality-is-commutative
  (is (= {:foo :bar} (->LazyRequest {:foo :bar})))
  (is (= (->LazyRequest {:foo :bar}) {:foo :bar})))

(deftest test-works-like-map
  (is (= (assoc (->LazyRequest {}) :foo :bar)
         {:foo :bar}))
  (is (= {:foo :bar}
         (assoc (->LazyRequest {}) :foo :bar)))
  (is (= {} (dissoc (->LazyRequest {:foo :bar}) :foo)))
  ;; map
  ;; count
  ;; empty
  ;; contains
  ;; iterable
  ;; seq
  )

(defn gen-coalmine
  "Generate canary (atom) and coal mine (LazyRequest). Accessing the :kill
  value of the mine will reset the canary atom to :dead."
  []
  (let [canary (atom :alive)]
    {:canary canary
     :coalmine (->LazyRequest {:kill (delay (reset! canary :dead))})}))

(deftest test-assoc-doesnt-realize-delays
  (let [{:keys [canary coalmine]} (gen-coalmine)]
    (assoc coalmine :foo :bar)
    (is (= :alive @canary) "Assoc should not realize delays.")))

(deftest test-accessing-vals-realizes-delays
  (let [{:keys [canary coalmine]} (gen-coalmine)]
    (is (= [:dead] (vals coalmine)))
    (is (= :dead @canary) "Accessing vals should realize delays."))
  (let [{:keys [canary coalmine]} (gen-coalmine)]
    (is (= :dead (val (first (seq coalmine)))))
    (is (= :dead @canary) "Accessing val from seq should realize delays.")))

(deftest test-keys-seq-doesnt-realize-delays
  (let [{:keys [canary coalmine]} (gen-coalmine)]
    (is (= :kill (first (keys coalmine))))
    (is (= :alive @canary) "Access keys seq should not realize delays.")))

(deftest test-seq-doesnt-realize-delays
  (let [{:keys [canary coalmine]} (gen-coalmine)]
    (is (= :kill (key (first (seq coalmine)))))
    (is (= :alive @canary) "seq itself should not realize delays.")))
(deftest test-printing-doesnt-realize-delays)
