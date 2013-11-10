# Public API

This is the complete API for new Pedestal.

- [io.pedestal.app.map/inform-to-transforms](#inform-to-transforms)
- [io.pedestal.app.map/inform->transforms](#inform->transforms)
- [io.pedestal.app.model/apply-transform](#apply-transform)
- [io.pedestal.app.model/transform->inform](#transform->inform)
- [io.pedestal.app.flow/transform->inform](#transform->inform)
- [io.pedestal.app.route/router](#router)


## [io.pedestal.app.map](https://github.com/pedestal/pedestal/blob/app-docs/app/src/io/pedestal/app/map.clj)

The `io.pedestal.app.map` namespace provides an implementation of the
dispatch map.

The purpose of a dispatch map is to take an inform message as input
and produce transform messages. The dispatch map will find a dispatch
map function based on patterns in the inform message and call this
function to produce transform messages. See the design-spec document
for more information.


### inform-to-transforms

```clj
(defn inform-to-transforms
  ([index inform])
  ([index inform args-fn]))
```

The `inform-to-transforms` function is passed an index and an inform
message and returns a vector of transform messages.

It can optionally be passed an `args-fn`. By default a dispatch map
function has the signature

```clj
(defn produce-transforms [inform-message])
```

where `inform-message` is a plain old inform message. There are times
when this may not be the desired signature. For example, when watching
for changes from the information model, we may not like having to deal
with the entire old and new state. The `args-fn` is a function that
receives the `patterns` and the `inform-message` and returns a vector
of arguments that will be applied to the dispatch map function.

The default implementation for this function is shown below.

```clj
(defn custom-args [patterns inform-message] [inform-message])
```


### inform->transforms

```clj
(defn inform->transforms
  ([config tchan])
  ([config tchan args-fn]))
```

The `inform->transforms` function takes a config that will be used to
create an index and a transform channel and returns an inform channel.

It can optionally be passed an `args-fn`.

This function sets up asynchronous processing of the inform
channel. When a message is received on the inform channel it is used
to produce transform messages which are put on the transform channel.


## [io.pedestal.app.model](https://github.com/pedestal/pedestal/blob/app-docs/app/src/io/pedestal/app/model.clj)

The `io.pedestal.app.model` namespace provides an implementation of the
information model.


### apply-transform

```clj
(defn apply-transform [old-model transform])
```

### transform->inform

```clj
(defn transform->inform [data-model inform-c])
```

## [io.pedestal.app.flow](https://github.com/pedestal/pedestal/blob/app-docs/app/src/io/pedestal/app/flow.clj)

The `io.pedestal.app.flow` namespace provides an implementation of the
information model which uses flow.

### transform->inform

```clj
(defn transform->inform
  ([data-model config inform-c])
  ([data-model config args-fn inform-c]))
```

## [io.pedestal.app.route](https://github.com/pedestal/pedestal/blob/app-docs/app/src/io/pedestal/app/route.clj)

The `io.pedestal.app.route` namespace provides an implementation of the
router.

### router

```clj
(defn router [id in])
```


## Implementation

This part of the API is an implementation detail but is listed here
because these functions are used by more than one of the above
components.

- [io.pedestal.app.match/index](#index)
- [io.pedestal.app.match/remove-from-index](#remove-from-index)
- [io.pedestal.app.match/match](#match)
- [io.pedestal.app.diff/model-diff-inform](#model-diff-inform)
- [io.pedestal.app.diff/combine](#combine)


## [io.pedestal.app.match](https://github.com/pedestal/pedestal/blob/app-docs/app/src/io/pedestal/app/match.clj)

The `io.pedestal.app.match` namespace provides functions for pattern
matching on messages.

An example message is shown below:

```clj
[[[:ui :b :c] :click]
 [[:ui :e :f] :submit]
 [[:ui :f :g] :over]]
```

This namespace provides a fast way to match this message to a function
or channel. See the design-spec document for more information.


### index

```clj
(defn index
  ([config])
  ([idx config]))
```

Given a config which is a vector of vectors like:

```clj
[[some-function [:ui :b :c] :click]
 [another-function [:ui :e :f] :*]
 [log [:ui :**] :*]]
```

return an data structure that provides fast pattern matching.

The single argument version builds a new index and the two argument
version adds the provided configuration to an existing index.


### remove-from-index

```clj
(defn remove-from-index [idx config])
```

The `remove-from-index` function will remove all items in the given
config from the provided index and return the new index.


### match

```clj
(defn match [idx inform])
```

The `match` function will match items in the index to the inform
message as described above.

The `match` function returns a set of vectors. Each vector has the
matched item, the patterns which matched the message and the matching
part of the message.

```clj
[fn-or-chan patterns message]
```

This is used by both the router and the dispatch map to match messages
to functions or channels.


## [io.pedestal.app.diff](https://github.com/pedestal/pedestal/blob/app-docs/app/src/io/pedestal/app/diff.clj)

The `io.pedestal.app.diff` namespace provides functions work calculate
data model diffs and to combine diffs. diffs are inform messages and
combining an inform message produces a smaller inform message.


### model-diff-inform

```clj
(defn model-diff-inform
  ([o n])
  ([paths o n]))
```

### combine

```clj
(defn combine [inform-message patterns])
```
