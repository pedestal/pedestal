---
title: Connecting 'Hello World' to Datomic
---

<!--
 Copyright 2013 Relevance, Inc.
 Copyright 2014 Cognitect, Inc.

 The use and distribution terms for this software are covered by the
 Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
 which can be found in the file epl-v10.html at the root of this distribution.

 By using this software in any fashion, you are agreeing to be bound by
 the terms of this license.

 You must not remove this notice, or any other, from this software.
-->

# Connecting "Hello World" to Datomic

This tutorial extends the
[Hello World Service](hello-world-service.md) to use
strings retrieved from [Datomic]. We will start from the "helloworld"
service that we created in our first tutorial.

This tutorial assumes that you have Datomic running already. If not,
hop over to the
[download site](http://www.datomic.com/get-datomic.html) and download
the free edition.

## Add Datomic to the Project

There are just a couple of steps to get our service hooked up to
Datomic. First, we need to add the dependency to our project.clj.

```
(defproject helloworld "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [io.pedestal/pedestal.service "0.1.0"]
                 [org.slf4j/jul-to-slf4j "1.7.2"]
                 [org.slf4j/jcl-over-slf4j "1.7.2"]
                 [org.slf4j/log4j-over-slf4j "1.7.2"]
                 [com.datomic/datomic-free "0.8.3826"]]
  :profiles {:dev {:source-paths ["dev"]}}
  :resource-paths ["config"]
  :main ^{:skip-aot true} helloworld.server)
```

Run `lein deps` to fetch any jars you need.

## Write Schema and Seed Data

On Pedestal app, a suitable place to put a schema is the
resources/[your-project-name-here] directory. In this sample, the project
name is "helloworld", so place the Datomic schema file below in
`resources/helloworld/schema.edn`. The schema is also pretty simple:
it has just one attribute.

```clj
[
  {:db/id #db/id[:db.part/db]
  :db/ident :hello/color
  :db/valueType :db.type/string
  :db/cardinality :db.cardinality/one
  :db/fulltext true
  :db/doc "Today's color"
  :db.install/_attribute :db.part/db}
]
```

This application doesn't have any way to write new data into Datomic
yet. So, in order to have something to show in a browser, we'll put
some seed data into Datomic. This file can also reside under the
resources/helloworld directory. Create the file
`resources/helloworld/seed-data.edn` with the following contents.

```clj
[
{:db/id #db/id[:db.part/user -1], :hello/color "True Mint"}
{:db/id #db/id[:db.part/user -2], :hello/color "Yellowish White"}
{:db/id #db/id[:db.part/user -3], :hello/color "Orange Red"}
{:db/id #db/id[:db.part/user -4], :hello/color "Olive Green"}
]
```

The funny looking negative IDs are a way to ask Datomic to assign
entity IDs automatically.

## Create Some Data Functions

Now we need create functions to establish a connection to Datomic,
define the schema, and retrieve data.  This code is nothing new, just
a simple Datomic sample. Put the following code into
`src/helloworld/peer.clj`.

```clj
(ns helloworld.peer
  (:require [datomic.api :as d :refer (q)]))

(def uri "datomic:mem://helloworld")

(def schema-tx (read-string (slurp "resources/helloworld/schema.edn")))
(def data-tx (read-string (slurp "resources/helloworld/seed-data.edn")))

(defn init-db []
  (when (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn schema-tx)
      @(d/transact conn data-tx))))

(defn results []
  (init-db)
  (let [conn (d/connect uri)]
    (q '[:find ?c :where [?e :hello/color ?c]] (d/db conn))))

```

# Use Database Results in the Service

Next, we need to use results from the database. For this, we will add
a function to `service.clj`. This new function will use `peer.clj` to
access data.

Open up `src/helloworld/service.clj` again and modify the `ns` macro to
reference helloworld.peer:

```clj
(ns helloworld.service
    (:require [io.pedestal.http :as bootstrap]
              [io.pedestal.http.route.definition :refer [defroutes]]
              [ring.util.response :refer [response]]
              [helloworld.peer :as peer :refer [results]]))
```

Let's now rewrite the `home-page` function in `service.clj` so that we
see the output from Datomic.

```clj
(defn home-page
  [request]
  (response (str "Hello Colors! " (results))))
```

If you still have the service running from
[Hello World Service](hello-world-service.md), then you
will need to exit the REPL. Restart the service the same way as
before: `lein repl`, `(use 'dev)`, and `(start)`.

Now point your browser at
[http://localhost:8080/](http://localhost:8080) and you will see the
thrilling string:

```clj
Hello Colors! [["True Mint"], ["Olive Green"], ["Orange Red"], ["Yellowish White"]]
```

Because `home-page` returns a string, the HTTP response will be sent
with a content type of "text/plain", as we can see by using "curl" to
access the server.

``` bash
$ curl -i http://localhost:8080/
HTTP/1.1 200 OK
Date: Fri, 22 Feb 2013 20:31:06 GMT
Content-Type: text/plain
Content-Length: 82
Server: Jetty(8.1.9.v20130131)

Hello Colors! [["True Mint"], ["Orange Red"], ["Olive Green"], ["Yellowish White"]]
```

# Where to go Next

For more about Datomic, check out [datomic.com][datomic].

[datomic]: http://www.datomic.com

