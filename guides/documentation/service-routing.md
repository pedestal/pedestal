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

# Service Routing

Pedestal's HTTP service plumbing provides a mechanism for routing
requests through an ordered list of interceptors that handle them. The
same infrastructure supports generating URLs that, when used with the
appropriate HTTP verb, cause a request to be routed to a particular
interceptor list. This document describes how routing and URL generation
work.

# Routing Tables

Pedestal's HTTP routing and URL generation features are driven by a
route table. A route table is a sequence of routes. A route is a map
containing criteria for matching an HTTP request and an ordered list
of interceptors to invoke on a request that matches a particular
route.

A route matching is based on:

* URL scheme
* HTTP method
* Host header
* URL path
* Constraints on param values in URL path and/or query string

## Defining route tables

A route table is simply a data structure; in our case, it is a
sequence of maps. The structure caters to the needs of matching and
dispatching of requests, and as such has a great deal of repeated
and derived data intended for use in that process. Creating the
data structure in its final, verbose form by hand would be very tedious.

We've built a simpler, terse form for route tables. The
terse form is also a data structure, albeit more explicitly hierarchical; Writing
a route table in the terse form is easier because information is not
explicitly duplicated. Instead, child nodes implicitly inherit
relevant route data from their ancestors.

It is important to note that the terse form is a convenient way to
define route tables, nothing more. It is always expanded to the
more verbose structure - a sequence of maps - before use. While a
convenient authoring format, it is not
directly used to route requests or generate URLs.

## The terse format

In the terse format, a route table is a vector of vectors, each
describing an application. Each application vector can contain the
following optional elements:

- a keyword identifying the application by name
- required URL scheme(s)
- a required host header value, e.g., example.com
- one or more nested vectors specifying routes

Here is a simple "Hello World" example:

```clojure
[[:hello-world :http "example.com"
  ["/hello-world" {:get hello-world}]]]
```

In this case, the following HTTP request:

```
GET /hello-world HTTP/1.1
Host: example.com
```

would be routed to the `hello-world` interceptor.

A request to a different host (either DNS name or IP
address) or using HTTPS would not be routed, unless the application's
specification were loosened, like so:

```clojure
[[:hello-world
  ["/hello-world" {:get hello-world}]]]
```

The application's name, `:hello-world`, is optionally used during URL
generation, and can also be omitted, leaving this:

```clojure
[[["/hello-world" {:get hello-world}]]]
```

This is the smallest possible example of a useful route table.

### Verb maps

In most cases, a nested vector specifying routes contains a path and a verb
map (there are exceptions, explained below). The verb map contains
keys corresponding to HTTP verbs. All verbs are supported, along with
the special value `:any`, indicating a match to any HTTP verb. Each
verb represents a different route. The values in the verb map
represent the "destination interceptor". Additional intermediate
interceptors may also be invoked, as described below.

The value for a key in a route's verb map specifies a route's
destination interceptor. The value can be:

- a symbol that resolves to one of:

    - a function that accepts a Ring request map and returns a Ring response map (i.e. a Ring handler)

    - an interceptor

    - a function that returns an interceptor and is marked with metadata ^{:interceptorfn true}

- a vector containing the following:

    - an optional keyword that names the route, for use in URL generation

    - a value that is either:

        - a symbol interpreted as described above

        - a list that evaluates to either:

            - a function that accepts a Ring request map and returns a Ring response map (i.e. a Ring handler)

            - an interceptor

    - an optional vector of interceptors, described below

    - an optional map of constraints, described below

The following sections explains how these values are used.

### Terse format expansion

A terse route definition must be expanded to a full route table
before it can be used. There are two ways to do this:

- the `io.pedestal.http.route.definition/expand-routes` function

- the `io.pedestal.http.route.definition/defroutes` macro

The `expand-routes` function takes a terse route definition data
structure as input and returns a route table. For example:

```clojure
(defn hello-world [req] {:status 200 :body "Hello World!"})

(def route-table
  (expand-routes '[[["/hello-world" {:get hello-world}]]]))
```

Note that the terse data structure is quoted, making `hello-world` a
symbol. It resolves the `hello-world` function, which takes a Ring
request and returns a Ring response.

The `defroutes` macro is equivalent to calling `expand-routes` with a
quoted data structure:

```clojure
(defroutes route-table
  [[["/hello-world" {:get hello-world}]]])
```

A quoted terse route definition is read at load time and is static
after that. In some cases, you may need to dynamically generate
routes. Here is an example:

```clojure
(defn hello-fn [who]
  (fn [req] (ring.util.response/response (str "Hello " who)))

(defn make-routes-for-who [who]
  (expand-routes
    `[[["/hello" {:get [:hello-who (hello-fn ~who)]}]]]))

(def route-table (make-routes-for-who "World"))
```

In this case, the `make-routes-for-who` function takes an argument,
`who`, that it uses to configure the resulting routes. It generates
the terse route data structure using Clojure's syntax quote mechanism,
splicing in the value of `who` where it is needed.

In some cases, you may want to assemble the terse data structure
without quoting it at all. Here is an example:

```clojure
(defn hello-world [req] {:status 200 :body "Hello World!"})

(def route-table
  (expand-routes
    [[["/hello-world"
 {:get [(handler ::hello-world hello-world)]}]]]))
```

In this case, the `hello-world` symbol is resolved to the
`hello-world` function as the data structure is built. The `handler`
function (defined in `io.pedestal.interceptor`) takes the
function and builds an interceptor from it, to meet the requirement
that a value in a verb map must be a symbol, an interceptor, or a list.

Alternatively, `hello-world` can be defined as an interceptor
directly, using the `io.pedestal.interceptor/defhandler` macro:

```clojure
(defhandler hello-world [req] {:status 200 :body "Hello World!"})

(def route-table
  (expand-routes
    [[["/hello-world" {:get hello-world}]]]))
```

Or, `hello-world` can be quoted, making it a symbol again:

```clojure
(defn hello-world [req] {:status 200 :body "Hello World!"})

(def route-table
  (expand-routes
    [[["/hello-world" {:get 'hello-world]}]]]))
```

The `expand-routes` function is more flexible, but also harder to use
than the `defroutes` macro. The latter is preferred in most cases.

## Advanced route definitions

This section describes path parameters, hierarchical route
definitions, intermediate interceptors and constraints.

### Path parameters

Segments of a route's path may be parameterized simply by
prepending ':' to the segment's name:

```clojure
(defn hello-who [req]
  (let [who (get-in req [:path-params :who])]
    (ring.util.response/response (str "Hello " who))))

(defroutes route-table [[["/hello/:who" {:get hello-who}]]])
```
As with Ring, Rails, etc, the path parameters are parsed and added to
the request's param map.

Splat parameters are also supported. They are defined using a final
path segment prepended with '*', like this:

```clojure
[[["/hello/:who" {:get hello-who}]
  ["/*other" {:get get-other-stuff]]
```

### Hierarchical route definitions

Route definitions in the terse form are hierarchical. A route
definition may contain zero or more child routes. A child route
inherits information from it's ancestors.

Here is an example showing how a path is inherited:

```clojure
[[["/order" {:get list-orders
             :post create-order}
   ["/:id" {:get view-order
            :put update-order}]]]]
```
This defines these four routes:

- GET /order

- POST /order

- GET /order/:id

- PUT /order/:id

The "/order" path segment is inherited by the child routes.

It is worth noting that this same structure could be defined without
hierarchy:

```clojure
[[["/order" {:get list-orders
             :post create-order}]
  ["/order/:id" {:get view-order
                 :put update-order}]]]
```
This would produce the same four routes.

### Interceptors

Every route definition includes an interceptor path that will be
executed for any request that matches the route. By default, a route's
interceptor path contains one interceptor, the route's handler. For
instance, the four routes defined in the previous section have the
following interceptor paths:

- GET /order => [list-orders]

- POST /order => [create-order]

- GET /order/:id => [view-order]

- PUT /order/:id => [update-order]

Route definitions can specify additional interceptors to include in
the interceptor path for a given route. These interceptors function as
before, after or around filters for specific routes. They are
specified using a vector marked with `^:interceptors` metadata. The
values specified in the interceptors vector may be:

- a symbol that resolves to one of:

    - an interceptor

    - a function that returns an interceptor and is marked with metadata ^{:interceptorfn true}

    - a function that accepts a Ring request map and returns a Ring response map (i.e. a Ring handler)

- a list that evaluates to either:

    - an interceptor

    - a function that accepts a Ring request map and returns a Ring response map (i.e. a Ring handler)

Here is an example:

```clojure
[[["/order" {:get list-orders
             :post create-order}
   ["/:id" ^:interceptors [load-order-from-db]
    {:get view-order
     :put update-order}]]]]
```
With this additional interceptor specified for the second two routes,
the interceptor paths become:

- GET /order => [list-orders]

- POST /order => [create-order]

- GET /order/:id => [load-order-from-db view-order]

- PUT /order/:id => [load-order-from-db update-order]

Any number of interceptors may be specified as an order sequence:

```clojure
[[["/order" {:get list-orders
             :post create-order}
   ["/:id" ^:interceptors [load-order-from-db
                           verify-order-ownership]
    {:get view-order
     :put update-order}]]]]
```

In this case, for requests that match the "/order/:id" route, the
`load-order-from-db` interceptor will run before the
`verify-order-ownership` interceptor, then the appropriate handler,
`view-order` or `update-order`, will run.

Interceptors may be specified at multiple levels of the hierarchy.
Like paths, interceptors are inherited. Inherited interceptors always
come first in the interceptor path for a given route.

```clojure
[[["/order" ^:interceptors [verify-request]
   {:get list-orders
    :post create-order}
   ["/:id" ^:interceptors [verify-order-ownership
                           load-order-from-db]
    {:get view-order
     :put update-order}]]]]
```

This definition produces the following routes and interceptor paths:

- GET /order => [verify-request list-orders]

- POST /order => [verify-request create-order]

- GET /order/:id => [verify-request verify-order-ownership load-order-from-db view-order]

- PUT /order/:id => [verify-request verify-order-ownership load-order-from-db update-order]

Inherited interceptors always precede a route definition's own handlers
in its interceptor path.

### Constraints

A route may specify constraints on path parameters and query string
parameters. Constraints are tested when a request is being matched
against a route. If the request does not satisfy a route's constraints, it
is not considered a match.

Constraints are specified as a map marked with `^:constraints`
metadata. The keys in the map are path parameters or query string
parameters. The values are regular expressions used for testing
parameter values.

Here is an example of how constraints can be used:

```clojure
["/user" {:get list-users
          :post add-user}
 ["/:user-id"
 ^:constraints {:user-id #"[0-9]+"}
  {:put update-user}
  [^:constraints {:view #"long|short"}
   {:get view-user}]]]
```

This defines four routes:

- GET /user => [list-users]

- POST /user => [add-user]

- PUT /user/:user-id => [update-user],
  but only if :user-id matches [0-9]+

- GET /user/:user-id => [view-user],
  but only if :user-id matches [0-9]+ and there is a "view" query param whose value is either "long" or "short"

Note that constraints can be used in addition to or in place of a path
when defining a child route. In that case, they must appear as the
first item in the child route vector.

Like intermediate interceptors, constraints are inherited by child routes.

# Routing

Once a route table is defined, it can be used to create a router. The
`io.pedestal.http.route/router` function takes a route table as
input and returns an interceptor that handles routing.

```clojure
(defn hello-world [req] {:status 200 :body "Hello World!"})

(defroutes route-table
    [[["/hello-world" {:get hello-world}]]])

(def router (router route-table))
```

When a routing interceptor's enter function is invoked, it attempts to
match the incoming request against each route in the route table in
turn. If a route matches, the routing interceptor adds all the
interceptors for the given route to the current interceptor path. They
will be invoked by the interceptor engine after the router's function
completes. It also adds the selected route to the interceptor context
map so that other interceptors can know which route was selected.

If no route matches, the router simply returns the current interceptor
context without modification.

During development it is useful to be able to reprocess route
definitions without restarting your server. If you call the `router`
function and pass a function that returns a route table, it will be
called every time the routing interceptor is used. This allows your
Web server to use the latest compiled routes without restarting.

```clojure
(def router (router #(deref #'route-table)))
```

If you are using the Pedestal service template for lein, it provides a
default route table and handles setting up a routing interceptor as
one of the steps of building a service. It also configures use of the
latest compiled routes when running in the repl.

# URL generation

In addition to routing, route tables are also used for URL
generation. You can request a URL for a given route by name and
specify parameter values to fill in. This section describes URL
generation, starting with how routes are named.

## Route names

Every route has a name, represented as a keyword. Route names are
implicit, where possible. For routes that specify destination
interceptors using symbols, the name is the fully-qualified symbol
name expressed as a keyword.

For routes that specify destination interceptors directly as
interceptor values, the route-name is the name of the interceptor.

For interceptors defined using the `defbefore`, `defafter`,
`defaround`, `defon-request`, `defhandler` and `defon-response` macros
in the `io.pedestal.interceptor` namespace, the name is the
interceptor's fully-qualified symbol name expressed as a keyword.

For interceptors defined using the `before`, `after`, `around`,
`on-request`, `handler` and `on-response` functions in the
`io.pedestal.interceptor` namespace, the name is the keyword
passed to the function, if any.

For routes that specify interceptors indirectly as lists to be
evaluated, no route name can be implicitly assigned.

You can specify an explicit route name for any route by adding a
keyword as the first item in the vector specified as the value of a
given HTTP verb for a given route. Explicit route names take
precedence over implicit names. *For routes that cannot be given an
implicit name, an explicit name must be provided or an exception will
be thrown during route expansion.*

Here is an example.

```clojure
(require '[orders :as o])

(defroutes routes
  [[["/order" {:get o/list-orders
               :post [:make-an-order o/create-order]}
              ^:interceptors [verify-request]
     ["/:id" {:get o/view-order
              :put o/update-order}
             ^:interceptors [o/verify-order-ownership
                             o/load-order-from-db]]]]])
```

In this case, the destination interceptors are all specified as
symbols in the `orders` namespace. The route names are listed below:

- GET /order => :orders/list-orders

- POST /order => :make-an-order

- GET /order/:id => :orders/view-order

- POST /order/:id => :orders/update-order

The second route specified an explicit route name, `:make-an-order`,
which takes precedence over the implicit name for that route,
`:orders/create-order`.

The `io.pedestal.http.route/print-routes` helper function prints
route verbs, paths and names at the repl. When in doubt, you can use
it to find route names.

## URL generation

The `io.pedestal.http.route/url-for-routes` function takes a
route table and returns a function that accepts a route-name (and optional
arguments) and returns a URL that can be used in a hyperlink.

```clojure
(def url-for (route/url-for-routes route-table))

(url-for ::o/list-orders) ;; use keyword derived from symbol to name route
;; => "/order"

(url-for :make-an-order) ;; use specified route name
;; => "/order"
```

An `url-for` function can populate parameters in a route. Parameter values
are passed as additional arguments:

```clojure
(url-for :view-order :params {:id 10})
;; => "/order/10"
```

Entries in the `:params` map that do not correspond to parameter values
in a route's path are added to the returned URL as query string
parameters. Alternatively, `:path-params` and `:query-params` can be
used to specify parameter values independently.

## Request-specific URL generation

A route table provides the basis for URL generation. A request map can
act as an additional basis. This allows for the generation of absolute
vs relative URLs, depending on the URL a request was sent to and how
specific a route-table is about an application's host name and
supported schemes.

When the routing interceptor matches a request to a route, it creates
a new URL generator function that closes over the request. It adds the
function to the interceptor context and the Ring request map, using
the key `:url-for`.

The request-specific URL generator function is also dynamically bound
to a private var in the `io.pedestal.http.route` namespace. The
`io.pedestal.http.route/url-for` function calls the dynamically
bound function.

The `io.pedestal.http.route/url-for` function can be called from
any thread that is currently executing an interceptor. If you need to
use a request-specific URL generator function elsewhere, extract
`:url-for` from the context or request map and propagate it as needed.

## Verb smuggling

The `url-for` functions only return URLs. The
`io.pedestal.http.route/form-action-for-routes` function takes a
route table and returns a function that accepts a route-name (and optional
arguments) and returns a map containing a URL and an HTTP verb.

```clojure
(def form-action (route/form-action-for-routes routes-table))

(form-action :make-an-order)
;; => {:action "/order" :method :post}
```

A form action function will (by default) convert verbs other than GET
or POST to POST, with the actual verb added as a query string
parameter named `method`:

```clojure
(form-action ::o/update-order :params {:id 20})
;; => {:action "/order/20?`method=put" :method :post}
```

This behavior can be disabled (or enabled for `url-for` functions) and
the query string parameter name can be changed. All of these settings
can be modified when an `url-for` or `form-action` function is created
or when it is invoked.
