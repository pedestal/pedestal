(ns io.pedestal.http.body-params-bench
  (:require [clojure.core.cache.wrapped :as cache]
            [io.pedestal.http.body-params :as bp]
            [criterium.core :as c]))

(def parser-for #'bp/parser-for)

(def sample-size 1000)

(def content-types
  (->> (cycle ["application/edn"
               "application/json"
               "application/x-www-form-urlencoded"
               "application/transit+json"
               "application/transit+msgpack"])
       (take sample-size)))

(def parser-map (bp/default-parser-map))

(comment
  (c/quick-bench

    (let [*cache (cache/lru-cache-factory {})]
      (mapv #(parser-for parser-map %) content-types)
      :done))

  ;; Original code: 463 µs
  ;;
  ;; Using search-for-parser, reduce-kv: 319 µs

  ;; Using default lru-cache: 729 µs

  ;; No cache, but search-for-parser: 302 µs

  ;; search-for-parser inlined: 328 µs

  ;; Revert that, back to search-for-parser: 316 µs
  )
