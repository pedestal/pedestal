# Introduction


# Tenets

- data, fns, macros
- clojure AND clojurescript
- awareness of address spaces - web workers, server processes - know where marshaling would have to happen

# Conceptual Model

Pedestal Applications are made up of three things: user interfaces,
services and data. User interfaces allow the application to
communicate with a user. Services allow the application to communicate
with external systems. Data stores the application's state. Elements
of the user interface, specific services and pieces of data are all
named using hierarchical paths. They communicate with each other using
messages. There are two types of messags:

- application logic a *transform* message causes a UI element or data
  in the data model to change, or a service request to be made (TBD:
  see open issue)

- *inform* messages notify application logic that a UI element or data
   in the data model has changed, or that a service has returned a
   result (TBD: what about async input from service via SSE or
   websocket?)

[image: put the new widgets + router canvas here]

*TBD: describe the widgets canvas here, including:

- the kinds of parts, e.g., red arrows are transform messages delivered via channels, what's the green thing
- the specific parts, e.g., the map for UI->info model, info model->info model (flow), and info model->UI
- the processing an event - from a single event, e.g., button press, through all the steps to UI update

# How it works

Break out the individual parts and explain how they work - input,
output formats - maybe include a subsection on parts it gets wired up
to w/ links to related sections - make it so you can walk through the
processing of an event by moving between these doc sections

## Messages

This section describes transform and inform messages, including the definition of the format and the use of hierarchy in ids, why they are the way they are; use of fns as op in transform, the fact that inform messages can have 0..n states -- inform message is a vector of event entries, a transform message is any number of transformations in a vector.

- there are identified parts of the app - named with paths - everything that changes has a unique id: widgets, data, services - the way we report change is the same for widgets, data, service
- change propagated using messages, messages always have a path, which are hierarchical
- hierarchy and wildcards for pattern matching and routing

Reiterate that inform msgs go inform channels and transform msgs go on transform channels

### Introduce match patterns here

## Map

- what it does / why it's here
- any pertitent points about it's design
- could go all the way to api for each part

## Info Model

### Flow

## Cogs

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