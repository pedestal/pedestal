; Copyright 2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.content-negotiation-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest testing is are]]
            [io.pedestal.http.content-negotiation :as cn]))

(def example-accept (string/join " , "
                                 ["*/*;q=0.2"
                                  "foo/*;    q=0.2"
                                  "spam/*; q=0.5"
                                  "foo/baz; q    =   0.8"
                                  "foo/bar"
                                  "foo/bar;baz=spam"]))

(deftest accept-parse-test
  (testing "A single requested type"
    (is (= (cn/parse-accept-* "foo/bar")
           [{:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}])))
  (testing "Multiple requested types - with q and params"
    (is (= (cn/parse-accept-* example-accept)
           [{:field "*/*" :type "*" :subtype "*" :params {:q 0.2}}
            {:field "foo/*" :type "foo" :subtype "*" :params {:q 0.2}}
            {:field "spam/*" :type "spam" :subtype "*" :params {:q 0.5}}
            {:field "foo/baz" :type "foo" :subtype "baz" :params {:q 0.8}}
            {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}
            {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0 :baz "spam"}}]))))

(deftest parse-and-weight-tests
  (testing "single request"
    (are [request-str supported-strs expected]
        (= (cn/weighted-accept-qs (mapv cn/parse-accept-element supported-strs)
                                   (cn/parse-accept-element request-str))
            expected)

         ;; A single request; single supported
         ;; We expect a score of 112: 100 (type) + 10 (subtype) + 1 (Q) + 1 (matching param in this case, Q=1)
         "foo/bar"  ["foo/bar"]   [112.0
                                   {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}
                                   {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}]
         ;; A single request; single support; no match
         "foo/bar"  ["qux/burt"]  nil
         ;; A single request; multi-supported with one applicable
         "foo/*"    ["qux/burt" "foo/bar"] [107.0
                                            {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}
                                            {:field "foo/*" :type "foo" :subtype "*" :params {:q 1.0}}]
         ;; A single request; multi-supported with two applicable
         ;;  - preference is based on order of supported types
         "foo/*"    ["foo/burt" "foo/bar"] [107.0
                                            {:field "foo/burt" :type "foo" :subtype "burt" :params {:q 1.0}}
                                            {:field "foo/*" :type "foo" :subtype "*" :params {:q 1.0}}]
         ;; A single request; multi-supported with no match
         "foo/*"    ["qux/burt" "qax/buzz"] nil))
  (testing "multi-request"
    (are [supported-strs expected]
         (= (mapv #(cn/weighted-accept-qs (mapv cn/parse-accept-element supported-strs) %)
                  (cn/parse-accept-* example-accept))
            expected)

         ;; A multi request; single supported
         ["foo/bar"] [[55.2
                       {:params {:q 1.0}, :field "foo/bar", :type "foo", :subtype "bar"}
                       {:params {:q 0.2}, :field "*/*", :type "*", :subtype "*"}]
                      [105.2
                       {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}
                       {:field "foo/*" :type "foo" :subtype "*" :params {:q 0.2}}]
                      nil
                      nil
                      [112.0
                       {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}
                       {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}]
                      [112.0
                       {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}
                       {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0 :baz "spam"}}]]
         ;; A multi request; single supported with no match apart from */*
         ["qux/burt"] [[55.2
                        {:params {:q 1.0}, :field "qux/burt", :type "qux", :subtype "burt"}
                        {:params {:q 0.2}, :field "*/*", :type "*", :subtype "*"}]
                       nil nil nil nil nil]
         ;; A multi request; multi-supported with one applicable
         ["foo/bar" "qux/burt"] [[55.2
                                  {:params {:q 1.0}, :field "foo/bar", :type "foo", :subtype "bar"}
                                  {:params {:q 0.2}, :field "*/*", :type "*", :subtype "*"}]
                                 [105.2
                                  {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}
                                  {:field "foo/*" :type "foo" :subtype "*" :params {:q 0.2}}]
                                 nil
                                 nil
                                 [112.0
                                  {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}
                                  {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}]
                                 [112.0
                                  {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0}}
                                  {:field "foo/bar" :type "foo" :subtype "bar" :params {:q 1.0 :baz "spam"}}]]
         ;; A multi-request; multi supported; multi applicable
         ["foo/burt" "spam/burt"] [[55.2
                                    {:params {:q 1.0}, :field "foo/burt", :type "foo", :subtype "burt"}
                                    {:params {:q 0.2}, :field "*/*", :type "*", :subtype "*"}]
                                   [105.2
                                    {:field "foo/burt" :type "foo" :subtype "burt" :params {:q 1.0}}
                                    {:field "foo/*" :type "foo" :subtype "*" :params {:q 0.2}}]
                                   [105.5
                                    {:field "spam/burt" :type "spam" :subtype "burt" :params {:q 1.0}}
                                    {:field "spam/*" :type "spam" :subtype "*" :params {:q 0.5}}]
                                   nil nil nil]
         ;; A multi-request; multi supported with no match apart from */*
         ["qux/burt" "qax/burt"] [[55.2
                                   {:params {:q 1.0}, :field "qux/burt", :type "qux", :subtype "burt"}
                                   {:params {:q 0.2}, :field "*/*", :type "*", :subtype "*"}]
                                  nil nil nil nil nil])))

;; Since matching is just a fallout of weighting, this next test is
;; just to ensure we fetch the correct value out of the weight response...
(deftest parse-and-match-tests
  (testing "Ensure match ensure best match when multiple options are present"
    (is (= {:field "spam/burt" :type "spam" :subtype "burt"}
          (cn/best-match
             (cn/best-match-fn ["foo/burt" "spam/burt"])
             (cn/parse-accept-* example-accept)))))
  (testing "Ensure match returns nil when there are no matches"
    (is (= nil
           (cn/best-match
             (cn/best-match-fn ["qux/burt" "qax/burt"])
             (cn/parse-accept-* (subs example-accept 12)))))))

;; Interceptor testing approach taken from io.pedestal.http.ring-middlewares-test
;; ----------------
(defn app [{:keys [response request] :as context}]
  (assoc context :response (or response (merge request {:status 200 :body "OK"}))))

(defn context [req]
  {:request (merge {:headers {}  :request-method :get} req)})



(deftest negcon-interceptor-tests
  (testing "No options; match"
    (is (= "foo/bar"
          (-> (context {:uri "/blah"
                         :headers {"accept" "foo/bar"}})
               (app)
               ((:enter (cn/negotiate-content ["foo/bar" "qux/burt"])))
               (get-in [:request :accept :field])))))
  (testing "No options; no match"
    (is (= 406
          (-> (context {:uri "/blah"
                         :headers {"accept" "spam/burt"}})
               (app)
               ((:enter (cn/negotiate-content ["foo/bar" "qux/burt"])))
               (get-in [:response :status])))))
  (testing "Not acceptable option; match"
    (is (= "foo/bar"
          (-> (context {:uri "/blah"
                         :headers {"accept" "foo/bar"}})
               (app)
               ((:enter (cn/negotiate-content ["foo/bar" "qux/burt"]
                                              {:no-match-fn (fn [ctx] (assoc-in ctx [:request :blarg] 42))})))
               (get-in [:request :accept :field])))))
  (testing "Not acceptable option; no match"
    (is (= 42
          (-> (context {:uri "/blah"
                         :headers {"accept" "spam/burt"}})
               (app)
               ((:enter (cn/negotiate-content ["foo/bar" "qux/burt"]
                                              {:no-match-fn (fn [ctx] (assoc-in ctx [:request :blarg] 42))})))
               (get-in [:request :blarg]))))))

