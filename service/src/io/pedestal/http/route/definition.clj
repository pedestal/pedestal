; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.definition)

(def schemes #{:http :https})
(def allowed-keys #{:route-name :app-name :path :method :scheme :host :port :interceptors :path-re :path-parts :path-params :path-constraints :query-constraints :matcher})

;; TODO: Remove and refactor across the codebase
(defmacro defroutes
  "Define a routing table from the terse routing syntax."
  [name route-spec]
  `(def ~name (io.pedestal.http.route/expand-routes (quote ~route-spec))))
