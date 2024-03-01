; Copyright 2021-2024 Nubank NA
; Copyright 2018-2021 Relevance, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.log-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.data.json :as json]
            [io.pedestal.log :as log]))

(def *events (atom nil))

(use-fixtures :each (fn [f]
                      (reset! *events [])
                      (f)))

(deftest mdc-context-set-correctly
  (let [inner-value     (atom nil)
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

(deftest nil-trace-origin
  (is (nil? (log/-span nil "operation-name")))
  (is (nil? (log/-span nil "operation-name" nil)))
  (is (nil? (log/-span nil "operation-name" nil nil))))

(defn event [& data]
  (swap! *events conj (vec data))
  nil)

(def test-logger
  (reify log/LoggerSource

    (-level-enabled? [_ _] true)

    (-info [_ body]
      (event :info body))))


(deftest honors-logger
  ;; clj-kondo seems to be confused by the meta data, or the info macro
  #_:clj-kondo/ignore
  ^{:line 8888} (log/info ::log/logger test-logger :key :value)
  (is (= [[:info
           "{:key :value, :line 8888}"]]
         @*events))

  (let [m (read-string (-> @*events first second))]
    (is (= #{:line :key} (-> m keys set)))
    (is (= :value (:key m)))))

(defn special-formatter
  [data]
  (->> (assoc data :line :override)
       (into (sorted-map))
       pr-str
       string/upper-case))

(deftest can-override-formatter
  #_:clj-kondo/ignore
  (log/info ::log/logger test-logger
            ::log/formatter special-formatter
            :key :value
            :more-info {:this :that})

  (is (= [[:info
           "{:KEY :VALUE, :LINE :OVERRIDE, :MORE-INFO {:THIS :THAT}}"]]
         @*events)))

(deftest uses-default-formatter-if-not-specified
  (with-redefs [log/default-formatter (constantly json/json-str)
                log/make-logger       (constantly test-logger)]
    ^{:line 9999} (log/info :this :that)
    (is (= [[:info
             "{\"this\":\"that\",\"line\":9999}"]]
           @*events))))

(deftest use-log-directly
  (log/log {:key            :value
            ::log/logger    test-logger
            ::log/formatter special-formatter} :info)
  (is (= [[:info
           "{:KEY :VALUE, :LINE :OVERRIDE}"]]
         @*events)))
