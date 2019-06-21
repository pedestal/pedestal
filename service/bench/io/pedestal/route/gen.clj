; Copyright 2013 Relevance, Inc.
; Copyright 2014-2019 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.route.gen
  "Generate routes and requests for a given set of routes.

  Why not use an existing generative library? A valid set of routes
  follows a very specific set of rules which is hard to encode in a
  generic generative library. It may not be impossible but it was
  faster to write this.

  Not much effort has gone into making this fast."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def segments (line-seq (io/reader (io/resource "io/pedestal/route/words.txt"))))

(defn dist [& probs]
  (let [{:keys [v c]} (reduce (fn [{:keys [v c]} [n val]]
                                {:v (conj v [(+ n c) val])
                                 :c (+ n c)})
                              {:v [] :c 0}
                              probs)
        v (second (first (drop-while (fn [[n val]] (<= n (rand-int c))) v)))]
    (if (fn? v)
      (v)
      v)))

(comment

  (dist [3 :get] [2 :post] [1 :put])
  ;; 3/7 times returns :get
  ;; 2/7 times returns :post
  ;; 1/7 times returns :put

  (dist [1 :one] [1 #(dist [1 :two] [1 :three])])
  )

(defn percent
  "Return true n% of the time."
  [n]
  (< (rand-int 100) n))

(declare extend-path)

(defn extend-path-internal [path n]
  (let [extend-with (take (rand-int 5) (shuffle segments))
        new-paths (if (seq extend-with) (map #(conj path %) extend-with) [path])]
    (mapcat #(extend-path % (dec n)) new-paths)))

(defn extend-path [path n]
  (if (zero? n)
    [path]
    (if (and (pos? (count path))
             (percent 20))
      (if-let [wild (keyword (gensym))]
        (extend-path (conj path wild) n)
        (extend-path-internal path n))
      (extend-path-internal path n))))

(defn covered-by-catchall? [set-of-prefixes ^String route-path]
  (some (fn [[s p]]
          (and (.startsWith route-path s)
               (not= route-path p)))
        set-of-prefixes))

(defn gen-route-paths-internal [catchall-prefixes]
  (map #(let [s (apply str "/" (interpose "/" %))]
          (if (percent 5)
            (let [p (str s "/*" (gensym))]
              (swap! catchall-prefixes conj [s p])
              p)
            s))
       (set (map #(take (inc (rand-int 9)) %) (extend-path [] 10)))))

(defn gen-route-paths [n]
  (let [catchall-prefixes (atom #{})]
    (loop [paths #{}]
      (if (< (count paths) n)
        (let [new-paths (take n (gen-route-paths-internal catchall-prefixes))
              new-paths (into paths new-paths)
              new-paths (remove (partial covered-by-catchall? @catchall-prefixes) new-paths)]
          (recur (set new-paths)))
        (take n paths)))))

(defn random-method [] (dist [3 :get] [2 :post] [1 :put]))

(defn maybe-random-method [] (dist [2 :any] [1 random-method]))

(defn random-host [] (dist [3 "example-one.com"] [1 "example-two.com"]))

(defn maybe-random-host [] (dist [20 nil] [1 random-host]))

(defn random-scheme [] (dist [3 :http]
                             [1 :https]))

(defn maybe-random-scheme [] (dist [20 nil]
                                   [1 random-scheme]))

(defn random-port [] (dist [10 "80"]
                           [2 "8080"]
                           [1 "3000"]))

(defn maybe-random-port [] (dist [20 nil]
                                 [1 random-port]))

(defn make-route [path]
  {:path path
   :method (maybe-random-method)
   :host (maybe-random-host)
   :port (maybe-random-port)
   :scheme (maybe-random-scheme)})

(defn more-methods [route]
  (cons route (map #(assoc route :method %)
                   (disj #{:any :post :get :put} (:method route)))))

(defn maybe-more-methods [route]
  (dist [8 [route]] [2 (more-methods route)]))

(defn generate-routes [n]
  (take n (shuffle (mapcat (comp maybe-more-methods make-route) (gen-route-paths n)))))

(comment

  (generate-routes 1)
  ;;=> [{:path "/dial/clubs/continued/ecology/online/allocation/walking/lovely/*G__5006"
  ;;=>   :method :any
  ;;=>   :host nil
  ;;=>   :port nil
  ;;=>   :scheme nil}]

 )

(defn generate-params [wilds]
  (reduce (fn [acc ^String x]
            (assoc acc
              x
              (cond (.startsWith x ":")
                    (first (shuffle segments))
                    :else
                    (str/join "/" (take (inc (rand-int 5))
                                        (shuffle segments))))))
          {}
          wilds))

(defn requests-for-route [{:keys [method host port scheme path]}]
  (let [parts (str/split path #"/")
        wilds (filter (fn [^String x] (or (.startsWith x ":")
                                          (.startsWith x "*")))
                      parts)
        req-map {:request-method (if (not= method :any) method (random-method))
                 :server-name (or host (random-host))
                 :server-port (or port (random-port))
                 :scheme (or scheme (random-scheme))
                 :path-info path}]
    (if (seq wilds)
      (mapv #(assoc req-map
               ::generated-params (zipmap (map (fn [k] (keyword (subs k 1))) (keys %))
                                          (vals %))
               :path-info (str/join "/" (mapv (fn [p] (get % p p)) parts)))
            (map generate-params (repeat (inc (rand-int 10)) wilds)))
      [(assoc req-map ::generated-params {})])))

(comment

  (def route (first (generate-routes 1)))
  route
  ;;=> {:path "/parking/switch/:G__6644/huge"
  ;;=>  :method :any
  ;;=>  :host nil
  ;;=>  :port nil
  ;;=>  :scheme nil}

  (first (requests-for-route route))
  ;;=> {:io.pedestal.route.gen/generated-params {:G__6644 "mo"}
  ;;=>  :request-method :get
  ;;=>  :server-name "example-one.com"
  ;;=>  :server-port "80"
  ;;=>  :scheme :https
  ;;=>  :path-info "/parking/switch/mo/huge"}

  )
