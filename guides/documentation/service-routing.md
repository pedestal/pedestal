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

# Quick Reference

Route vector format:

   1. Path string
   2. Verb. One of :any, :get, :put, :post, :delete, :patch, :options,
      :head
   3. Handler or vector of interceptors
   4. Optional route name clause
   5. Optional constraint clause

Syntax for calling `table-routes`

```clojure
(ns myapp.service
  (:require [io.pedestal.http.route.definition.table :as table]))

(def application-routes
  (table/table-routes
    {:host "example.com" :scheme :https}
    [["/user"          :get user-search-form]
     ["/user/:user-id" :get view-user        :constraints {:user-id #"[0-9]+"}]
     ,,,
     ]))
```

# Routing

A key problem in any backend service is directing requests to the
right bit of code. As an industry, we've settled on the term "routing"
to describe how a service understands a request's URL and invokes the
right function.

There's a flip side to routing, though, which is generating URLs to
put into links and hrefs. If we're not careful, link generation can
create hard-to-find coupling between different parts of a service.

Pedestal addresses both parts of this problem with the same feature.

This guide will teach you how to:

   * Define routes
   * Parse parameters from URLs
   * Connect routes to handlers
   * Apply interceptors along a route
   * Use constraints to ensure a route is invoked correctly
   * Generate links for a route during a request to that route
   * Generate links for _other_ routes

# A note about routing syntax

Since Pedestal was first unveiled, we've gone through a couple of
iterations on the routing syntax. So far, these iterations have all
been additive. Previous guides have used the "terse" syntax, which is
written as a triply-nested vector. The terse syntax is powerful but
not easy.

This release introduces a new syntax we're calling the "table"
syntax. It is more wordy and has some repetition from row to row, but
it also has some advantages:

   1. The parser is simpler and produces better error messages when
      the input is not right. (This includes some work to make stack
      traces more helpful.)
   2. The table does not have hierarchic nesting, so the rows are
      independent.
   3. The input is just data, so you can read it from an EDN file or
      compose it with regular functions. No more syntax-quoting to
      create interceptors with parameters.

The "terse" and "verbose" syntaxes are both still supported, until we
hear from the community. We've also put some effort into better error
messages when dealing with the terse format.

   * [Terse Syntax Reference](routing-terse-syntax.md)
   * [Routing Data Flow](routing-data-flow.md)

# Defining a route

## The Bare Minimum

The simplest route has just a URL, an HTTP verb, and a handler:

```clojure
["/users" :get view-users]
```

In this case, view-users is anything that can be resolved as an
interceptor:

   * An interceptor record
   * A function that returns an interceptor (the function must be
     annotated as `^:interceptor-fn`)
   * A request handler (really a special case of interceptor)

## Building handlers

There's nothing special about using a symbol in the handler's
position. You could call a function that returns an interceptor:

```clojure
["/users" :get (make-view-users-handler db-conn)]
```

In previous versions of Pedestal, we did some magic to treat that
function call as a list and defer evaluation. This led to a lot of
confusion and some questions like, "When should I syntax-quote?" and
"How do I inject context into request handling?"

In contrast, that call to `make-view-users-handler` is nothing
special. Clojure will evaluate it when you build the route table. Just
make sure it returns something that can be resolved as an interceptor.

## Path Parameters

The URL in a route is really a pattern that can match or generate
URLs. In the simple case above, `/users` just matches itself as a
literal string.

The pattern can include any number of segments to capture as
parameters. Any segment that looks like a keyword will be captured in
the :path-params map in the request.

So the route:

```clojure
["/users/:user-id" :get view-users]
```

will match any of the following requests:

   - `/users/abacab`
   - `/users/miken`
   - `/users/12345`
   - `/users/mike%20n`

When the request reaches our `view-users` handler, it will include a
map like this:

```clojure
{:path-params {:user-id "miken"}}
```

The path parameters are always delivered as strings. The strings are
HTTP decoded for you, but are not otherwise converted.

A single path parameter only matches one segment of a URL. One segment
is just the part between '/' characters. So the route above will _not_
match `/users/miken/profile/photos/blue-wig.jpg`.

What if our user IDs are all numeric? It would be convenient if the
route would only match when the URL meets a valid pattern in the path
parameters. That's the job of [Constraints](#constraints), discussed below.

## Catch-all Parameters

What if you actually do want to match any number of segments after a
path? In that case, you use a "catch-all" parameter. It looks like a
path parameter, except it has an asterisk instead of a colon:

```clojure
["/users/:user-id/profile/*subpage" :get view-user-profile]
```

This is still delivered as a path parameter in the request map:

```clojure
{:path-params {:user-id "miken" :subpage "photos/blue-wig.jpg"}}
```

## Query Parameters

You don't need to do anything in the route to capture query
parameters. They are automatically parsed and passed in the request
map, under the :query-params key. Like path parameters, query
parameters are always delivered as HTTP-decoded strings.

## Verbs

So far, all our examples have used :get as the HTTP verb. Pedestal
supports the following verbs:

   - :get
   - :put
   - :post
   - :delete
   - :patch
   - :options
   - :head
   - :any

These should look familiar, with the exception of `:any`. `:any` is a
wildcard verb that allows a route to match any request method. That
gives a handler the opportunity to decide whether a request method is
allowed or not.

## Interceptors

So far, all our examples have used just one handler function. But one
of Pedestal's key features is the ability to create a chain of
interceptors. The route table allows you to put a vector of
interceptors (or things that resolve to interceptors) in that third
position.

```clojure
["/user/:user-id/private" :post [inject-connection auth-required (body-params/body-params) view-user]]
```

In this example, `inject-connection` and `auth-required` are
interceptors. `body-params` is a builtin function (from
io.pedestal.http.body-params) that returns an interceptor. `view-user`
is a request-handling function.

When a request matches this route, the whole vector of interceptors
gets pushed onto the context.

### Common interceptors

The "terse" syntax used hierarchically nested routes to reuse
interceptors on subtrees. The table based syntax gives up that
feature, but allows you to compose interceptors like this:

```clojure
;; Make a var with the common stuff
(def common-interceptors [inject-connection auth-required (body-params/body-params)])

;; inside a call to table-routes
["/user/:user-id/private" :post (conj common-interceptors view-user)]
```

This puts you in charge of composing interceptors using ordinary
Clojure data manipulation.

## Constraints

As a convenience, you can supply a map of constraints, in the form of
regular expressions, that must match in order for the whole route to
match. This handles that case from [before](#path-parameters), where
we wanted to say that user IDs must be numeric.

You tell the router about constraints by supplying a map from
parameter name to regular expression:

```clojure
["/user/:user-id" :get view-user :constraints {:user-id #"[0-9]+"}]
```

If the constraint doesn't match, then the router keeps considering
other routes. If none match, then it's a 404.

Notice the `:constraints` keyword. That is required to tell the router
that the following map is to be treated as constraints. (The terse
syntax used a boolean flag in metadata for this purpose.)

Like the interceptor vector, the constraint map is just data. Feel
free to build it up however you like... it doesn't have to be a map
literal in the route vector:

```clojure
(def numeric #"[0-9]+")
(def user-id {:user-id numeric})

["/user/:user-id" :get  view-user   :constraints user-id]
["/user/:user-id" :post update-user :constraints user-id]
```

## Route names

Every route must have a name. Pedestal uses those names for the flip
side of route matching: URL generation. You can supply a route name in
the route vector:

```clojure
["/user" :get view-user :route-name :view-user-profile]
```

A route name must be a keyword.

The route name comes before `:constraints`, so if you have both, the
order is as follows

   1. Path
   2. Verb
   3. Interceptors
   4. Route name clause (:route-name :your-route-name)
   5. Constraints clause (:constraints _constraint-map_)

### Default Route Names

You'll notice that none of the examples before now have a
`:route-name` section. If you don't explicitly specify a route name,
Pedestal will pick one for you. It uses the `:name` of the last
interceptor in the interceptor vector (after resolving functions to
interceptors.) Most of the time, you'll have different handler
functions in that terminal position. But, if you reuse an interceptor
as the final step of the chain, you will have to assign unique route
names to distinguish them.

### Using Route Names to Distinguish Handlers

Suppose you have a single interceptor or handler that deals with
multiple verbs on the same path. Maybe it's a general API endpoint
function or a function created by another library. If you just try to
make multiple rows in a table, you will get errors:

```clojure
;;; This won't work in table syntax. Both rows get the same automatic
;;; route name.
["/users" :get user-api-handler]
["/users" :post user-api-handler]
```

You have a couple of options. To stick with table syntax, you can use
route names to distinguish the rows:

```clojure
["/users" :get user-api-handler :route-name :users-view]
["/users" :post user-api-handler :route-name :user-create]
```

The route names are enough to make each row unique.

With terse syntax, one path has a map that allows multiple verbs. Each
verb can use the same handler as long as they specify different route names:

```clojure
["/users" {:get user-api-handler
           :post [:user-create user-api-handler]}]
```

## Generating URLs

In addition to routing, route tables are also used for URL
generation. You can request a URL for a given route by name and
specify parameter values to fill in. This section describes URL
generation, starting with how routes are named.


## URL generation

The `io.pedestal.http.route/url-for-routes` function takes the parsed
route table and returns a URL generating function. The generator
accepts a route name and optional arguments and returns a URL that can
be used in a hyperlink.

```clojure
(def app-routes
   (table/table-routes
     {}
     [["/user"                   :get  user-search-form]
      ["/user/:user-id"          :get  view-user        :route-name :show-user-profile]
      ["/user/:user-id/timeline" :post post-timeline    :route-name :timeline]
      ["/user/:user-id/profile"  :put  update-profile]]))

(def url-for (route/url-for-routes app-routes))

(url-for :user-search-form)
;; => "/user"

(url-for :view-user :params {:user-id "12345"})
;; => "/user/12345"
```

Any leftover entries in the `:params` map that do not correspond to
path parameters get turned into query string parameters. If you want
more control, you can give the generator the specific arguments
`:path-params` and `:query-params`.

## Request-specific URL generation

The `url-for-routes` function provides a global URL generator. Within
a single request, the request map itself can provide a URL
generator. This generator allows you to create absolute or relative
URLs depending on how the request was matched.

When the routing interceptor matches a request to a route, it creates
a new URL generator function that closes over the request map. It adds
the function to the interceptor context and the request map, using the
key `:url-for`.

The routing interceptor also binds this request-specific URL generator
to a private var in the `io.pedestal.http.route` namespace. The
`io.pedestal.http.route/url-for` function calls the dynamically bound
function. This way, you can call `io.pedestal.http.route/url-for` from
any thread that is currently executing an interceptor. If you need to
use a request-specific URL generator function elsewhere, extract
`:url-for` from the context or request map and propagate it as needed.

### Verb smuggling

The `url-for` functions only return URLs. The
`io.pedestal.http.route/form-action-for-routes` function takes a
route table and returns a function that accepts a route-name (and optional
arguments) and returns a map containing a URL and an HTTP verb.

```clojure
(def form-action (route/form-action-for-routes app-routes))

(form-action :timeline :params {:user-id 12345})
;; => {:method "post", :action "/user/:user-id/timeline"}
```

A form action function will (by default) convert verbs other than GET
or POST to POST, with the actual verb added as a query string
parameter named `_method`:

```clojure
(form-action :update-profile :params {:user-id 12345})
;; => {:method "post", :action "/user/12345/profile?_method=put"}
```

This behavior can be disabled (or enabled for `url-for` functions) and
the query string parameter name can be changed. All of these settings
can be modified when an `url-for` or `form-action` function is created
or when it is invoked.

## Using the Routes in a Service

Up until now, we've looked at individual routes or a small handful of
them. Now let's see how to connect them to a Pedestal service.

We'll start with the `app-routes` definition from
[earlier](#url-generation). We can use that value in the service map:

```clojure
(ns myapp.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.route.definition.table :as table]))

;; definition of app-routes from above

(defn service
  []
  {:env :prod
   ::http/routes app-routes
   ::http/resource-path "/public"
   ::http/type :jetty
   ::http/port 8080})

(defn start
  []
  (-> service
      http/create-server
      http/start))
```

## Debugging Routes

The `io.pedestal.http.route/print-routes` helper function prints
route verbs, paths and names at the repl. When in doubt, you can use
it to find route names.

The function `io.pedestal.http.route/try-routing-for` lets you explore
routing decisions from a REPL. It will return the chosen route or nil
if nothing matched. Using the definition of `app-routes`
from before:

```clojure
(route/try-routing-for app-routes :prefix-tree "/user" :get)
;; => {:path "/user", :method :get, :path-re #"/\Quser\E", :path-parts ["user"], :interceptors [#Interceptor{:name :user-search-form}], :route-name :user-search-form, :path-params {}, :io.pedestal.http.route.prefix-tree/satisfies-constraints? #function[clojure.core/constantly/fn--4608]}

(route/try-routing-for app-routes :prefix-tree "/foo-bar-baz" :get)
;; => nil
```

Sometimes your routes will look right, but you still get a 404
response. This can happen if the final interceptor doesn't attach a
response to the context. The easiest way to debug that is to add a
`println` inside the interceptor that you think should be called.

You can also call one of an interceptor's functions directly to see if
it behaves as expected. Suppose you are debugging the `view-user`
interceptor. Since it is the final interceptor in its chain, the
context must have a response after calling it. To call the `:enter`
function on `view-user`:

```clojure
((:enter view-user) {})
;; => {:response {:status 200 :body ,,, :headers {,,,}}}
```

Here we can see that `view-user` does indeed attach a response to the
resulting context. You can also employ this technique with varying
inputs to exercise the corresponding conditional branches of your
interceptors from your test cases.
