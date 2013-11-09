# Introduction

This document provides a description of the components of Pedestal
covering both design and API. As much as possible, we attempt to
enumerate design decisions and point out open issues. Most design
decisions are still up for discussion.

Pedestal is a set of components which is intended to provide a sound
structure and efficient execution to large `core.async` based
applications. The main focus of these components is to provide a
standard way to model change. Most of the difficulty in building large
interactive applications comes from correctly and efficiently
responding to change. How do we report changes? How do we tell
something to change? How do we know what has changed? How do we do all
of this efficiently without writing overly complex code? Providing a
good answer to these questions is the goal of Pedestal.

We often use simple examples such as clicking a button an showing a
counter increment on a screen to illustrate what the various parts
will do and how they fit together. These are not motivating
examples. For applications this simple, you don't need Pedestal. A
true motivating example is a complex application with lots of:

- screens
- widgets
- interactions with services
- state

Small programs are easy. Large programs need to have more thought put
into how they are structured. The goal of Pedestal is to help build
these larger applications by providing a well thought out structure
and a way to consistently model change. A good common solution to
these problems will allow many developers to build higher level
components on top of it.

The problems we are trying to solve a listed below:

- events

  How do we deal with incoming events? Events should cause things to
  happen. What should happen and where should the code live which
  knows about what should happen? We should avoid callbacks into
  application code by using `core.async`. What do we send on a channel
  when an event occurs?

- state

  Where does state live? Do we need to have state? How is state
  changed? Where do put the code that changes state? Which components
  are allowed to change state? After state has changed, much of the
  work of an application is to figure how what has changed and what
  effect that should have. How do we do this? Do we use atoms to store
  state? If so, how many? Do we put state in the DOM? Do we put state
  in Widgets? Where do we put state and specific to rendering only?

- views

  We don't want to assume that we are only ever using the DOM for
  views. What part of the application should be updating views? How do
  keep application logic separate from view logic? How do we manage
  changing views? For example one screen has five widgets on it and
  another screen has ten different widgets. What happens when we
  switch from on screen to another? How do we handle listening for
  events in views and converting events to data on a `core.async`
  channel?

- control

  How do we know what state the application is in? For example, we
  need to log in before we can begin using the app. We may see one set
  of widgets when we are in one state and another when we are in a
  different state. How do we transition from one screen to another?
  Where do we put the code that knows how to control the application?

- communicate

  How do we communicate with back-end services? We need to allow for
  the server to push data to the app as well as make requests. Which
  components are allowed to initiate service requests and receive
  responses from services? How do make service requests and receive
  responses? Do we make function calls or send messages?

- logic

  The logic of an application is what knows how to interpret events
  and turn them into a useful change. It may need to change data in
  the model, request some data or determine which UI components need
  to be updated based on a change to the data. Where does this live?



# Tenets

There are few things to keep in mind while reading this document.

- We prefer data over functions over macros
- Each component must work in both Clojure AND ClojureScript
- For each component, we need to be aware of where it is running
  relative to other components. Sometimes components will run
  in the same address space as other components and sometimes they
  will not

An example of the third point is that we may run some components in a
web worker and others in the main JavaScript thread. We may also run
some components on the server and others in the browser. We need to
keep in mind where serialization and marshaling could happen.


# Conceptual Model

Pedestal Applications can be divided into three areas of concern: user
interfaces, services and the information model. User interfaces allow
the application to communicate with a user. Services allow the
application to communicate with external systems. The information
model stores the application's state. These areas of concern
communicate with each other using messages. There are two types of
messages:

- *transform* messages tell the message target how to change. They
  cause a UI element or data in the data model to change. They can
  also be used to tell a service to make a request. (TBD: see open
  issue)

- *inform* messages describe how the message source has changed. They
   notify application logic that a UI element or data in the data
   model has changed, or that a service has returned a result (TBD:
   what about async input from service via SSE or websocket? Can't
   these just be inform messages?)


![Pedestal Overview](overview.png)


## Kinds of things

In the diagram above, red arrows represent `transform channels`
(channels which convey transform messages) and blue arrows represent
`inform channels` (channels which convey inform messages). The green
shapes are `dispatch maps` which use patterns in inform message to find
functions which take the inform message and return transform
messages. These functions contain the logic which knows what an inform
message implies for other components of the application. The `info
model` stores application state and detects state deltas. `widgets`
connect to the world outside this flow of information. They can change
that world based on received transform messages and then can report
changes and generate inform messages. A widget could be a button, a
chart or something which communicates with external services. A
`router` can be used to route transform messages from one incoming
transform channel to multiple outgoing transform channels.


## Specific parts

The diagram above uses three dispatch maps. One which receives inform
messages from widgets, one which send transform messages to widgets
and services and one which is tied closely to the information model.

The UI->Info dispatch map is used to find functions which know what
changes to the UI imply for the information model. For example, it
might know that a button click means that a counter in the info model
should be incremented.

The Info->UI dispatch map is used to find functions which know what
changes to the info model imply for the UI. For example, when the
counter in the info models then some UI widget will need to display
the new counter value.

The Info->Info dispatch map is used to find functions which know what
changes to the info model imply for other parts of the info
model. This is how dataflow is implemented. Some of the information in
the system depends on other information. The logic for these data
connections can be put in one place instead of spreading it across all
of the places which update the base data.

The info model contains all application state in a single map. When
transforms are applied to the data it keeps track of all of the
changes that were made and reports those changes on the outgoing
inform channel.


## Processing an event

Now that we know what all the things in the diagram are, let's walk
though a complete cycle from user input to result being displayed on
the screen.

1. The user clicks a button on the screen which is backed by a widget
2. The button widget has set up an event listener for that
button. When the button is clicked, the event is converted to an
inform message and placed on the widgets inform channel.
3. The UI->Info dispatch map receives the inform message and finds a
function which has been configured to handle that message based on
matching patterns in the message.
4. The function is called, passing the inform message and the function
returns one or more transform messages.
5. The dispatch map places these transform messages on the transform
channel.
6. The info model receives a transform message and applies the
it to the model, keeping track of what has changed.
7. All changes to the model that were the result of applying one
transform to the model and reported as a single inform message on the
outgoing inform channel.
8. The Info->UI dispatch map receives an inform message from the info
model and finds the function which has been configured to handle that
message based on matching patterns in the message.
9. The function is called, passing the matched inform message and
returns one or more transform messages.
10. The dispatch map places these transform messages on the transform
channel.
11. A widget which displays the counter value on the screen receives
the transform message and changes the DOM to reflect the new value.


### Using a router

This sequence of events does not explain how transform messages find
there way to a particular widget. That is the function of the
`router`.

![Router](router.png)

When the Info->UI dispatch map sends transforms, a router will receive
each transform and, matching patters in the transform message, find
the outbound channel on which the transform message should be placed.

When a widget is created it will need to register itself with the
router. When a widget is destroyed, it will need to unregister.

The router can also be used to allow messages to be send back to the
info model. This allows for recursive updates which do not have to
occur at the same time as the transaction that caused them.


### Flow

If the info model has calculated dependencies then the following
sequence of events may occur.

1. The info model takes a transform message from the incoming
transform channel
2. It applies the transform and creates the inform message which
describes the changes.
3. This inform message is put on the inform channel which goes out to
the Info->Info dispatch map.
4. The Info->Info dispatch map takes the inform message off of the
inform channel and finds a function to generate transforms. Any
generated transforms are put on the outgoing transform channel.
5. The transform is received from the transform channel and applied to
the model.
6. Steps 3 through 5 are repeated until step 4 produces no new
transforms.
7. All changes to the info model are put into one transform message
and sent to on the outbound inform channel.


### Comments

TBD: The sequence of events above does not describe how flow works.

TBD: Do we really need to have both a UI->Info dispatch map and a
Info->UI dispatch map? Do these two pictures represent the same
instance of a dispatch map. If we have a router then it seems like one
could do the job.

TBD: describe the widgets canvas here, including:

- the kinds of parts, e.g., red arrows are transform messages delivered via channels, what's the green thing
- the specific parts, e.g., the map for UI->info model, info model->info model (flow), and info model->UI
- the processing an event - from a single event, e.g., button press, through all the steps to UI update



# How it works

This section of the doc will begin to layout how things work and why
they work that way.

Break out the individual parts and explain how they work

- input, output formats
- maybe include a subsection on the parts it gets wired up to w/ links to related sections
- make it so you can walk through the processing of an event by moving between these doc sections


## Messages

Messages are central to all parts of Pedestal in this section we
discuss message format.


### Component identifiers

Every message is either sent from something or to something. This
means that we need some way to identify the thing that is sending or
receiving the message.

Everything that be changed or can report having been changed has a
unique identifier. This includes user interface widgets, specific
services and individual values in the information model. All
identifiers have the same format. They are all paths represented as
vectors. Some valid identifiers are:

```clj
[:ui :button :a]
[:ui :list "bca345-b3ba-bc38a"]
[:info :counter :a]
[:services :auth]
```

By convention, identifiers are hierarchical meaning that two
identifiers with the same prefix are in some way related to each
other. For example all widgets have an identifier which starts with
`:ui`. All identifiers which start with `:info` identify a value in
the info model.

The identifiers

```clj
[:ui :list "bca345-b3ba-bc38a" :name]
[:ui :list "bca345-b3ba-bc38a" :age]
```

may refer to two different things in the same list.

Except for widgets, every component in the system makes some use of
this identifier format. Dispatch maps pattern match on the identifiers
to find matching functions to transform. The router pattern matches on
identifiers to find out which channel to route the message to. The
info model uses the identifier to apply a change to a specific part of
the model.


### Inform messages

An inform message describes how something has changed. Each inform
message is a vector of multiple event entries.

```clj
[event-entry1 event-entry2 event-entry3]
```

Each event entry describes an event as a vector with `source-id`,
`event`, and zero or more `states`.

```clj
[source-id event states]
```

Examples of inform messages include:

```clj
[[:ui :button :a] :click]
[[:ui :form :register] :submit {:name "James" :age 25 :location "France"}]
[[:info :counter :a] :updated {:info {:counter {:a 1}} {:info {:counter {:a 1}}]
```

The specifics of these examples are described below.

Q: why is an inform message a vector?


### Transform messages

A transform message tells something how to change. Each transform
message is a vector of multiple transformations.

```clj
[transformation1 transformation2]
```

Each transformation is a vector with `target-id`, `op` and zero or
more `arguments`.

```clj
[target-id op args]
```

Examples of transform messages include:

```clj
[[:ui :form :register] :display-errors {:age "Error! Age must be a positive integer."}]
[[:info :counter :a] inc]
[[:info :counter :a] + 100]
```

The specifics of these examples are described below.

Even though we have a common format for inform and transform messages,
each component which either sends or receives them may have different
requirements for what can be sent to it or how it will report
changes. Each component will have its own API.


### Comments

Q: how do transform message set a value other than using (constantly 42)?
Q: why is transform message a vector?
Q: why do we need to have transactions?

This section describes transform and inform messages, including the
definition of the format and the use of hierarchy in ids, why they are
the way they are; use of fns as op in transform, the fact that inform
messages can have 0..n states -- inform message is a vector of event
entries, a transform message is any number of transformations in a
vector.

- there are identified parts of the app - named with paths - everything that changes has a unique id: widgets, data, services - the way we report change is the same for widgets, data, service
- change propagated using messages, messages always have a path, which are hierarchical
- hierarchy and wildcards for pattern matching and routing

Reiterate that inform msgs go inform channels and transform msgs go on transform channels

### Introduce match patterns here

## Dispatch Map

- what it does / why it's here
- any pertinent points about it's design
- could go all the way to API for each part

## Info Model

### Flow

## Widgets

## Router


# Putting the pieces together

This section talks about assembling the pieces to achieve something - basically prose around the walkthrough




# Open issues

Does there need to be a way for a UI element to request data from the
data model w/o changing it?

Is `transform` really the right term? You can send a message to a
service to get data from it, that's not really a transformation.
Should you be able to read data from the info model without changing
it, or do you always have to transform it to see its state? A related
question is, how should services really work?

How would you save/load UI state to/from localstore? - current
thinking is that relevant info would have to be in information model
to save/restore UI control state.

How would you implement paging when UI can display 100 items, data
model can hold 10,000 and backed service has 10,000,000? - current
thinking is that notion of which page UI control is showing is in info
model.

If a message is sent to a map/router/dispatcher and the
src,event/target,op pair is not recognized, what should happen - i.e.,
drop the message, log an error, burn everything down, configurable?

What's the error handling model?

Are we using channels well? Policy choices - e.g. buffer size, channel
type - and assembly, meaning should channel structure be assembled
around core functions as opposed to integrated with them?

When can a function in a dispatch map make a call to an external
service?

We need to provide a way for functions in dispatch maps to do
asynchronous work. This may remove the need to interact with services
through messages. Maybe they can return channels or they could always
do work on their own go block.
