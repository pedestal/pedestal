(ns io.pedestal.http.impl.lazy-request-test
  (:require [clojure.test :refer :all]
            [io.pedestal.http.impl.lazy-request :refer :all]))

(deftest test-classify-keys
  (is (= :normal   (classify-keys [:foo :bar])))
  (is (= :delayed  (classify-keys [:foo (delay :bar)])))
  (is (= :realized (classify-keys [:foo (doto (delay :bar) deref)]))))

(deftest test-equality-only-exists-between-lazy-requests
  (is (not= {:foo :bar}                 (->LazyRequest {:foo :bar})))
  (is (not= (->LazyRequest {:foo :bar}) {:foo :bar}))
  (is (=    (->LazyRequest {:foo :bar}) (->LazyRequest {:foo :bar})))
  (is (not= (->LazyRequest {:foo :bar}) (->LazyRequest {:foo (delay :bar)}))))

(deftest test-works-like-map
  ;; Get
  (is (= :delayed (get  (->LazyRequest {:foo (delay :delayed)}) :foo)))
  (is (= :delayed (:foo (->LazyRequest {:foo (delay :delayed)}))))
  (is (= :delayed ((->LazyRequest {:foo (delay :delayed)}) :foo)))
  (is (= :default ((->LazyRequest {:foo (delay :delayed)}) :bar :default)))
  (is (= :immediate (get (->LazyRequest {:foo :immediate}) :foo)))

  ;; Assoc
  (is (= {:foo :bar}
         (raw (assoc (->LazyRequest {}) :foo :bar))))

  ;; Dissoc
  (is (= {}
         (raw (dissoc (->LazyRequest {:foo :bar}) :foo))))

  ;; Empty
  (is (= (->LazyRequest {})
         (empty (->LazyRequest {:foo :bar}))))

  ;; Map
  (is (= [2] (map (comp inc second)
                  (->LazyRequest {:a 1}))))

  ;; Count
  (is (= 1 (count (->LazyRequest {:foo :bar}))))

  ;; Contains
  (is (= true (contains? (->LazyRequest {:foo :bar}) :foo)))

  ;; Iterators
  (is (= [[:foo :bar]]
         (->> ^java.lang.Iterable (->LazyRequest {:foo :bar})
             (.iterator)
             iterator-seq
             (map seq) ;; Turn DerefingMapEntry into [<key> <val>]
             (into []))))

  ;; seq
  (is (= [[:foo :bar]]
         (->> (->LazyRequest {:foo :bar})
              seq
              (map seq) ;; Turn DerefingMapEntry into [<key> <val>]
              (into [])))))

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
    (is (= :dead @canary) "Accessing a val from seq should realize delays.")))

(deftest test-keys-seq-doesnt-realize-delays
  (let [{:keys [canary coalmine]} (gen-coalmine)]
    (is (= :kill (first (keys coalmine))))
    (is (= :alive @canary) "Accessing keys seq should not realize delays.")))

(deftest test-seq-doesnt-realize-delays
  (let [{:keys [canary coalmine]} (gen-coalmine)]
    (is (= :kill (key (first (seq coalmine)))))
    (is (= :alive @canary) "Seq/key-access itself should not realize delays.")))

(deftest test-iterable-doesnt-realize-delays
  (let [{:keys [canary coalmine]} (gen-coalmine)]

    (->> ^java.lang.Iterable coalmine
         (.iterator)
         iterator-seq
         (into []))
    (is (= :alive @canary) "Consuming an iterator does not realize delays.")))

(deftest test-printing-doesnt-realize-delays
  (let [{:keys [canary coalmine]} (gen-coalmine)]
    (pr-str coalmine)
    (is (= :alive @canary) "Printing a LazyRequest should not realize delays.")))

(deftest test-derefing-map-entry-equality
  (is (= (clojure.lang.MapEntry. :a :b)
         (seq (derefing-map-entry :a :b)))
      "Entry should be equal to equivalent MapEntry")
  (is (= [:a :b]
         (seq (derefing-map-entry :a :b)))
      "Seq'd Entry should be equal to equivalent vector")
  (is (= [:a :b]
         (seq (derefing-map-entry :a (delay :b))))
      "Seq'd Entry should be equal regardless of delayed-ness"))
