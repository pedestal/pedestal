; Copyright 2013 Relevance, Inc.
; Copyright 2014-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor.error
  (:require [io.pedestal.interceptor :as interceptor]
            [clojure.core.match :as match]))

(defmacro error-dispatch
  "Return an interceptor for doing pattern-matched error-dispatch, based on
  the ex-data of the exception.
  Pedestal wraps *all* exceptions in ex-info on error, providing the following
  keys to match on: `:execution-id`, `:stage`, `:interceptor`, `:exception-type`

  This allows you to match the exact exception type, per interceptor/handler,
  and even constrain it to a single stage (:enter, :leave, :error).

  `:exception-type` is a keyword of the exception's type, for example,
  `:java.lang.ArithmeticException
  "
  [binding-vector & match-forms]
  `(io.pedestal.interceptor/interceptor
     {:error (fn ~binding-vector
               (clojure.core.match/match [(ex-data ~(second binding-vector))]
                  ~@match-forms))}))

