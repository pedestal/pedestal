;; In Pedestal, applications receive input as inform messages. An
;; inform message is a vector of event-entries. Each event-entry has
;; the form

[source event state(s)]

;; Inform messages may be received from back-end services or from the
;; user interface. For example, a button click may generate an inform
;; message like the one shown below.

[[[:ui :button :a] :click]]

;; This inform message has one event-entry. The source is

[:ui :button :a]

;; and the event is

:click

;; There are no states included in this message.

;; An application will need to have some code which knows what this
;; particular event means for the application. Messages which tell
;; other things to change are called transform messages. Each
;; transform message contains one or more transformations. A
;; transformation has the form

[target op arg(s)]

;; For example, the value of a counter should be incremented when this
;; button is clicked. The tranform message which would cause this to
;; happen is shown below.

[[[:info :counter :a] inc]]

;; In this message, the target is

[:info :counter :a]

;; and the op is the `inc` function. This transformation has no
;; arguments.

;; Messages are conveyed on core.async channels.

(require '[clojure.core.async :refer [go chan <! >! put! alts!! timeout]])

;; Channels which convey infrom messages are called inform channels
;; and channels which convey transform messages are called transform
;; channels.

;; When an inform message is received, transform messages should be
;; generated which cause some part of the application to
;; change. To accomplish this we will need a function which receives
;; an inform message and produces a collection of transform messages.

(defn inc-counter-transform [inform-message]
  (let [[[source event]] inform-message
        [_ _ counter-id] source]
    [[[[:info :counter counter-id] inc]]]))

;; This function extracts the id of the counter to increment from the source
;; of the event-entry. For simplicilty this function assumes that it
;; will only have one event-entry in the inform message.

;; We need some way to map inform messages to this function and then
;; put the generated transform messages on a transform channel. That
;; is the purpose of the `io.pedestal.app.map` namespace.

(require '[io.pedestal.app.map :as map])

;; First we need to create a configuration that will describe how to
;; dispatch inform messages to functions. The following configuration
;; will dispatch inform messages from the source [:ui :button :*] with
;; a :click event to the function `inc-counter-transform`.

(def input-config [[inc-counter-transform [:ui :button :*] :click]])

;; This config is a vector of vectors and can contain any number of
;; vectors. Each vector can have any number or source event
;; pairs. Wildcards, :* and :**, can be used in the source path and :*
;; can be used to match any event. :* matches exactly one element and
;; :** matches 0 or more elements.

;; Now we will create a map that has an input inform channel and an
;; output transform channel and uses the above config.

;; Create the transform channel

(def transform-chan (chan 10))

;; Create the map passing the config and transform channel and
;; returning the inform channel.

(def inform-chan (map/inform->transforms input-config transform-chan))

;; We can now send an inform message on the inform channel

(put! inform-chan [[[:ui :button :a] :click]])

;; and see the transform message come out of the transform channel.

(println (first (alts!! [transform-chan (timeout 100)])))

;; So we now have a transform message which can be used to increment
;; a value in the information model.

;; To work with the information model we use the
;; `io.pedestal.app.model` namespace.

(require '[io.pedestal.app.model :as model])

;; A transform channel sends messages to the model and an inform
;; channel sends messages which describe changes to the model. We have
;; already seen an example of the transform message which is sent to
;; a model. What does a model inform message look like? An example is
;; shown below.

[[[:info :counter :a] :updated {:info {:counter {:a 0}}} {:info {:counter {:a 1}}}]]

;; The source is the path in the model which changed. The event is
;; either :added, :updated or :removed. The states are the entire
;; old and new model values.

;; To create a new model, we first create the inform channel.

(def model-inform-chan (chan 10))

;; We then call `transform->inform` passing the initial model value
;; and the inform channel. This returns the transform
;; channel. Remember, for the model, transform messages go in and
;; inform messages come out.

(def model-transform-chan (model/transform->inform {:info {:counter {:a 0}}} model-inform-chan))

;; We can now send a transform message to the model

(put! model-transform-chan [[[:info :counter :a] inc]])

;; and get the inform message which describes the change to the model.

(println (first (alts!! [model-inform-chan (timeout 100)])))

;; When building an application, we will combine these parts. We can
;; create a pipeline of channels where input inform messages go in one
;; end and model inform messages come out of the other end.

(def model-inform-chan (chan 10))

(def input-inform-chan (->> model-inform-chan
                            (model/transform->inform {:info {:counter {:a 0}}})
                            (map/inform->transforms input-config)))

;; We can now send a button click event on the input-inform-channel

(put! input-inform-chan [[[:ui :button :a] :click]])

;; and see the update to the model on the model-inform-channel

(println (first (alts!! [model-inform-chan (timeout 100)])))

;; So what should we do with model inform messages? Something in our
;; application will most likely need to change based on the changes to
;; the information model. Once again we need to generate transforms
;; based on inform messages. This time the transform message will go
;; out to the UI so that the counter value can be displayed.

(defn counter-text-transform [inform-message]
  (vector
   (mapv (fn [[source _ _ new-model]]
           (let [counter-id (last source)]
             [[:ui :number counter-id] :set-value (get-in new-model source)]))
         inform-message)))

;; In the function above, one transformation is created for each
;; event-entry. If more than one counter is updated during a
;; transaction on the info model then a single transform will be sent
;; out will all the updates to the UI. This could also be written so
;; that one transform is generated for each change.

;; Again we create a configuration which will map inform messages to
;; this function

(def output-config [[counter-text-transform [:info :counter :*] :updated]])

;; We can then create a map from this config

(def output-transform-chan (chan 10))
(def output-inform-chan (map/inform->transforms output-config output-transform-chan))

;; and send a model inform message to test it out

(put! output-inform-chan [[[:info :counter :a] :updated
                           {:info {:counter {:a 0}}}
                           {:info {:counter {:a 1}}}]])

(println (first (alts!! [output-transform-chan (timeout 100)])))

;; Now let's put it all together. Create the transform channel that
;; will send transform messages to the UI.

(def output-transform-chan (chan 10))

;; Build the pipeline that includes the model and the input and output
;; maps. This returns the inform channel that you will give to UI
;; componenets are anything which will send input to the application.

(def input-inform-chan (->> output-transform-chan
                            (map/inform->transforms output-config)
                            (model/transform->inform {:info {:counter {:a 0}}})
                            (map/inform->transforms input-config)))

;; Send the button click event on the input channel.

(put! input-inform-chan [[[:ui :button :a] :click]])

;; Consume ui transforms from the output channel.

(println (first (alts!! [output-transform-chan (timeout 100)])))

;; Try sending the :click event several times.

;; This is the basic application loop.

;; What if we had two counters and we wanted to have a third counter
;; which was always the sum of the other two? The
;; `io.pedestal.app.flow` namespace provides dataflow capabilities.

(require '[io.pedestal.app.flow :as flow])

;; Create a function that turns info model informs into info model transforms.

(defn sum-transform [inform-message]
  (let [[[_ _ _ n]] inform-message]
    [[[[:info :counter :c] (constantly (+ (get-in n [:info :counter :a])
                                          (get-in n [:info :counter :b])))]]]))

;; Create the flow config.

(def flow-config [[sum-transform [:info :counter :a] :updated [:info :counter :b] :updated]])

;; Call the `flow/transform->inform` function instead of
;; `model/transform->inform`. This function takes an additional config argument.

(def output-transform-chan (chan 10))
(def input-inform-chan
  (->> output-transform-chan
       (map/inform->transforms output-config)
       (flow/transform->inform {:info {:counter {:a 0 :b 0 :c 0}}} flow-config)
       (map/inform->transforms input-config)))

;; Send inform messages as if two buttons where clicked.

(put! input-inform-chan [[[:ui :button :a] :click]])
(put! input-inform-chan [[[:ui :button :b] :click]])

;; Read two inform messages from the output channel.

(println (first (alts!! [output-transform-chan (timeout 100)])))
(println (first (alts!! [output-transform-chan (timeout 100)])))
