# pedestal.ions

[Datomic Ion](https://docs.datomic.com/cloud/ions/ions.html)
interceptor chain provider. To learn more about Ions, checkout the
[docs](https://docs.datomic.com/cloud/ions/ions.html).

## Usage

1. Declare the `io.pedestal.ions/ion-provider` chain provider in your service map.
1. Create a fn which constructs an ion handler fn from the service map.
1. Create your ion by ionizing  your handler.
1. Whitelist your ion in the `ion-config.edn` resource file.

### Example

```
;; src/my_ion/example.clj
;;
(ns my-ion.example
    (:require [io.pedestal.http :as http]
              [io.pedestal.http.route :as route]
              [io.pedestal.ions :as provider]
              [ion-provider.datomic]
              [ring.util.response :as ring-resp]
              [datomic.client.api :as d]
              [datomic.ion.lambda.api-gateway :as apig])

;; Routes elided
(def routes ...)

;;
;; 1. Declare the `io.pedestal.ions/ion-provider` chain provider in your service map.
;;
(def service {:env :prod
              ::http/routes routes
              ::http/resource-path "/public"
              ::http/chain-provider provider/ion-provider})

;;
;; 2. Create a fn which constructs an ion handler fn from the service map.
;;
(defn handler
  "Ion handler"
  [service-map]
  (-> service-map
      http/default-interceptors
      http/create-provider))

;;
;; 3. Create your ion by ionizing your handler.
;;
(def app (apig/ionize (handler service)))

;; resources/ion-config.edn
;;
;;
;; 4. Whitelist your ion in the `ion-config.edn` resource file.
;;
{:allow    [my_ion.example/app]
 :lambdas  {:app {:fn my_ion.example/app :description "Pedestal Ions example"}}
 :app-name "my-ion-example"}
```

<!-- Copyright 2013 Relevance, Inc. -->
<!-- Copyright 2018 Cognitect, Inc. -->
