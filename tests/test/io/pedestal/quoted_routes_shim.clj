; Copyright 2026 Nubank NA

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.quoted-routes-shim
  "Test helper: temporarily installs a compatibility shim for quoted route specifications, by extending
  Pedestal's public IntoInterceptor and ExpandableVerbAction protocols to symbols and lists, to give them
  their 0.8 meaning again (a symbol is resolved, a list is evaluated).

  Pedestal 1.0 extends neither protocol to these types, but still lets symbols and lists reach them on
  purpose, so that such a shim gets the same routes as Pedestal 0.8. The shim is only installed while
  the function passed to [[call-with-shim]] runs: the previous extensions are restored afterwards
  (removed when there was none), so other tests never see it."
  (:require [io.pedestal.http.route.definition.terse :as terse]
            [io.pedestal.interceptor :as interceptor])
  (:import (clojure.lang Cons IPersistentList Symbol)))

(def into-interceptor-types
  "The types to which the shim extends IntoInterceptor."
  [Symbol IPersistentList Cons])

(def verb-action-types
  "The types to which the shim extends ExpandableVerbAction."
  [Symbol IPersistentList])

(defn current-impls
  "Returns the current extensions of both protocols to the types that the shim extends them to."
  []
  {:into-interceptor (select-keys (:impls interceptor/IntoInterceptor) into-interceptor-types)
   :verb-action      (select-keys (:impls terse/ExpandableVerbAction) verb-action-types)})

(defn ^:private install-shim!
  []
  (extend-protocol interceptor/IntoInterceptor
    Symbol
    (-interceptor [sym] (interceptor/-interceptor (resolve sym)))

    IPersistentList
    (-interceptor [l] (interceptor/-interceptor (eval l)))

    Cons
    (-interceptor [c] (interceptor/-interceptor (eval c))))

  (extend-protocol terse/ExpandableVerbAction
    Symbol
    (expand-verb-action [s] s)

    IPersistentList
    (expand-verb-action [l] (terse/expand-verb-action (eval l)))))

(defn ^:private restore-protocol!
  "Puts back the extension of the protocol to each type as it was in saved: re-extends the protocol
  when there was an extension, and removes the shim's extension when there was none."
  [protocol-var types saved]
  (doseq [type types]
    (if-let [method-map (get saved type)]
      (extend type @protocol-var method-map)
      (-reset-methods (alter-var-root protocol-var update :impls dissoc type)))))

(defn ^:private restore!
  [{:keys [into-interceptor verb-action]}]
  (restore-protocol! #'interceptor/IntoInterceptor into-interceptor-types into-interceptor)
  (restore-protocol! #'terse/ExpandableVerbAction verb-action-types verb-action))

(defn call-with-shim
  "Calls f with the shim installed, and returns its result. Restores the previous extensions
  afterwards, even when f throws."
  [f]
  (let [saved (current-impls)]
    (try
      (install-shim!)
      (f)
      (finally
        (restore! saved)))))

(defmacro with-shim
  "Evaluates the body with the shim installed; see [[call-with-shim]]."
  [& body]
  `(call-with-shim (fn [] ~@body)))
