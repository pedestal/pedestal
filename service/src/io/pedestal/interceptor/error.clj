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

