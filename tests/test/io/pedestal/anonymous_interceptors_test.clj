; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.anonymous-interceptors-test
  "An interceptor without a :name gets a name derived from its function, without a warning.
  A derived name appears in errors and observations, but route names never come from it:
  routes are the same as in Pedestal 0.8."
  (:require [clojure.test :refer [deftest is testing]]
            [io.pedestal.internal :as i]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as interceptor :refer [interceptor definterceptor]]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.interceptor.impl :as impl])
  (:import (clojure.lang ExceptionInfo)))

(def ^:private this-ns "io.pedestal.anonymous-interceptors-test")

(defn enter-fn [context] context)
(defn leave-fn [context] context)
(defn error-fn [context _exception] context)
(defn handler [_request] {:status 200 :body "handler"})
(defn failing-enter [_context] (throw (ex-info "Boom!" {})))

(defn make-interceptor
  "An unnamed interceptor whose :enter function is anonymous."
  []
  (interceptor {:enter (fn [context] context)}))

(defn ^:private silently
  "Invokes f, returning its result and everything printed to *out* and *err*; deprecation warnings
  are reset first, so that a warning already printed once would be printed again."
  [f]
  (i/reset-deprecations)
  (let [err    (java.io.StringWriter.)
        result (atom nil)
        out    (with-out-str
                 (binding [*err* err]
                   (reset! result (f))))]
    {:result  @result
     :printed (str out err)}))

(deftest unnamed-map-interceptor-gets-a-derived-name-silently
  (doseq [[label m expected] [[":enter" {:enter enter-fn :leave leave-fn :error error-fn} ::enter-fn]
                              [":leave" {:leave leave-fn :error error-fn} ::leave-fn]
                              [":error" {:error error-fn} ::error-fn]]]
    (testing (str "from " label)
      (let [{:keys [result printed]} (silently #(interceptor m))]
        (is (= expected (:name result)))
        (is (impl/derived-name? result))
        (is (nil? (impl/explicit-name result)))
        (is (= "" printed) "no warning")))))

(deftest anonymous-fn-names
  (testing "an anonymous fn is named after the function that encloses it"
    (is (= (keyword this-ns "make-interceptor/fn")
           (:name (make-interceptor)))))

  (testing "the number of an eval'ed form (top-level form, REPL) is dropped, so the name is stable"
    (let [created (binding [*ns* (the-ns 'io.pedestal.anonymous-interceptors-test)]
                    (eval '(io.pedestal.interceptor/interceptor {:enter (fn [context] context)})))]
      (is (= (keyword this-ns "eval/fn") (:name created)))
      (is (impl/derived-name? created)))))

(deftest unnamed-function-handler-gets-a-name
  (testing "with default handler names: a real name, as before"
    (binding [interceptor/*default-handler-names* true]
      (let [{:keys [result printed]} (silently #(interceptor handler))]
        (is (= ::handler (:name result)))
        (is (not (impl/derived-name? result)))
        (is (= ::handler (impl/explicit-name result)))
        (is (= "" printed)))))

  (testing "without default handler names: the same name, but only derived"
    (binding [interceptor/*default-handler-names* false]
      (let [{:keys [result printed]} (silently #(interceptor handler))]
        (is (= ::handler (:name result)))
        (is (impl/derived-name? result))
        (is (nil? (impl/explicit-name result)))
        (is (= "" printed)))))

  (testing "a Var of a function"
    (binding [interceptor/*default-handler-names* false]
      (let [result (interceptor #'handler)]
        (is (= ::handler (:name result)))
        (is (impl/derived-name? result))))))

(deftest explicit-names-are-untouched
  (let [named (interceptor {:name ::named :enter enter-fn})]
    (is (= ::named (:name named)))
    (is (not (impl/derived-name? named)))
    (is (= ::named (impl/explicit-name named))))

  (doseq [names? [true false]]
    (binding [interceptor/*default-handler-names* names?]
      (let [named-fn (interceptor (with-meta (fn [_] nil) {:name ::named-fn}))]
        (is (= ::named-fn (impl/explicit-name named-fn)) (str "with default handler names " names?)))))

  (testing "converting an interceptor again keeps its name and its mark"
    (let [derived (interceptor {:enter enter-fn})]
      (is (impl/derived-name? (interceptor derived)))
      (is (impl/derived-name? (assoc derived :leave leave-fn)) "assoc keeps the mark")))

  (testing "a name set later is explicit"
    (let [derived (interceptor {:enter enter-fn})]
      (is (= ::renamed (impl/explicit-name (assoc derived :name ::renamed))))
      (is (= ::renamed (impl/explicit-name (impl/rename derived ::renamed)))))))

(definterceptor named-component []
  (enter [_ context] context))

(deftest definterceptor-and-component-names-are-untouched
  (let [from-record (interceptor (->named-component))]
    (is (= ::named-component (:name from-record)))
    (is (= ::named-component (impl/explicit-name from-record))))
  (let [component (interceptor/component->interceptor ::component (->named-component))]
    (is (= ::component (impl/explicit-name component)))))

(deftest errors-show-the-derived-name
  (let [unnamed (interceptor {:enter failing-enter})]
    (when-let [e (is (thrown? ExceptionInfo (chain/execute {} [unnamed])))]
      (is (= (str "clojure.lang.ExceptionInfo in Interceptor :" this-ns "/failing-enter - Boom!")
             (ex-message e)))
      (is (= ::failing-enter (:interceptor (ex-data e)))))))

(deftest observers-see-the-derived-name
  (let [*names (atom [])]
    (-> {}
        (chain/add-observer #(swap! *names conj (:interceptor-name %)))
        (chain/execute [(interceptor {:enter enter-fn})]))
    (is (= [::enter-fn] @*names))))

;; Route names don't change: a derived name is never used as a route name.

(defn ^:private routing-table
  [route-spec]
  (->> (route/expand-routes route-spec)
       :routes
       (mapv (juxt :path :method :route-name #(mapv :name (:interceptors %))))))

(def ^:private table-assertion
  #"^\QAssert failed: In row 1, the last interceptor does not have a name and there is no explicit :route-name\E")

(deftest table-route-needs-a-route-name-as-before
  (testing "an anonymous fn handler without default handler names"
    (binding [interceptor/*default-handler-names* false]
      (is (thrown-with-msg? AssertionError table-assertion
                            (routing-table #{["/t" :get (fn [_] {:status 200})]})))
      (is (thrown-with-msg? AssertionError table-assertion
                            (routing-table #{["/t" :get handler]})))
      (is (= [["/t" :get ::t [::handler]]]
             (routing-table #{["/t" :get handler :route-name ::t]}))
          "with a route name; the interceptor keeps its derived name")))

  (testing "with default handler names, the handler's name is the route name, as before"
    (binding [interceptor/*default-handler-names* true]
      (is (= [["/t" :get ::handler [::handler]]]
             (routing-table #{["/t" :get handler]})))))

  (testing "an unnamed map interceptor as the handler"
    (doseq [names? [true false]]
      (binding [interceptor/*default-handler-names* names?]
        (is (thrown-with-msg? AssertionError table-assertion
                              (routing-table #{["/t" :get {:enter enter-fn}]})))))))

(def ^:private terse-error
  #"\QHandler was not a symbol, a var, or an interceptor with a name, and no route name was provided\E")

(deftest terse-route-of-an-unnamed-interceptor-takes-the-route-name-as-before
  (doseq [names? [true false]]
    (binding [interceptor/*default-handler-names* names?]
      (testing (str "with default handler names " names?)
        (let [unnamed (interceptor {:enter enter-fn})]
          (is (thrown-with-msg? ExceptionInfo terse-error
                                (routing-table [[["/t" {:get unnamed}]]])))
          (let [[[_ _ route-name names] :as table] (routing-table [[["/t" {:get [::t unnamed]}]]])
                terminal (-> (route/expand-routes [[["/t" {:get [::t unnamed]}]]]) :routes first :interceptors last)]
            (is (= 1 (count table)))
            (is (= ::t route-name))
            (is (= [::t] names) "the handler interceptor is named after the route, as in 0.8")
            (is (not (impl/derived-name? terminal)) "and that name is explicit")))))))

(deftest terse-route-of-a-function-handler-as-before
  (binding [interceptor/*default-handler-names* false]
    (is (thrown-with-msg? ExceptionInfo terse-error
                          (routing-table [[["/t" {:get (fn [_] {:status 200})}]]])))
    (is (= [["/t" :get ::t [::t]]]
           (routing-table [[["/t" {:get [::t handler]}]]])))
    (is (= [["/t" :get ::t [::enter-fn ::t]]]
           (routing-table [[["/t" {:get [::t ^:interceptors [{:enter enter-fn}] handler]}]]]))
        "a non-terminal unnamed interceptor keeps its derived name"))
  (binding [interceptor/*default-handler-names* true]
    (is (= [["/t" :get ::handler [::handler]]]
           (routing-table [[["/t" {:get handler}]]])))))
