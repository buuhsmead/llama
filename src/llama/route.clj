(ns llama.route
  "A Clojure DSL for [Camel Routes](http://camel.apache.org/routes.html).
  Provides a framework from routing messages between endpoints.
  
  ### The DSL

  The DSL tries to be a close approximation of the [fluent
  DSL](http://camel.apache.org/java-dsl.html). This namespace provides Clojure
  translations of the routing DSL.
  
  [[route]] instantiates a [[RouteBuilder]], which is what you add to a
  *context*. These can be added to a context via [[defcontext]], or by calling
  the method `.addRoutes` on ctx directly.

  ```
  ;; read from (local) ActiveMQ queue hello, print the body of the incoming
  ;; message, write the exchange to a file in `dump/fromHello.txt`.
  (route (from \"activemq:hello\")
         (process (fn [x] (println (in x))))
         (to \"file:dump?fileName=fromhello.txt\"))
  ```
  

  ### Running routes

  After the routes have been added to the context, the context can be started
  with [[start]]. This is a non-blocking operation so if your program is simply
  doing something with Camel you need an infinite loop of sorts. It is a good
  idea to [[stop]] the context when your program starts. You could
  use [component](https://github.com/stuartsierra/component) for managing the
  lifecycle of your program.
  
  ```
  (defcontext ctx myroute)
  (-defn main [& args]
    (start ctx)
    ;; your app logic here
    (stop ctx))
  ```
  
  ### Where to start?
  
  See the [Introduction](./01-intro.html).

  It is a good idea to learn what [Apache Camel](http://camel.apache.org) is
  before trying to use Llama. [This StackOverflow thread](http://stackoverflow.com/questions/8845186/what-exactly-is-apache-camel)
  is a good place to start. To grok Llama, you need to understand the following:

  * [Endpoints](http://camel.apache.org/endpoint.html) -- sources and destinations of messages
  * [Exchanges](http://camel.apache.org/exchange.html) -- messagings between two components
  * [Routes](http://camel.apache.org/routes.html) -- how to wire exchanges between sources and destinations

  Once you have a basic understanding of those, you should be able to get going. Alternatively, dive in and 
  [read the tutorial](./02-tutorial.html).
  "
  (:refer-clojure :exclude [filter] :as core)
  (require [llama.core :refer :all])
  (:import [org.apache.camel CamelContext Predicate Processor]
           org.apache.camel.builder.RouteBuilder
           org.apache.camel.impl.DefaultCamelContext
           org.apache.camel.processor.aggregate.AggregationStrategy))

(defn fn->processor
  "Create a [Processor](http://camel.apache.org/processor.html) from `proc-fn`,
  a fn accepting one argument,
  an [Exchange](http://camel.apache.org/exchange.html). See [[process]]."
  [proc-fn]
  (proxy [Processor] []
    (process [xchg]
      (proc-fn xchg))))

(defn ^:no-doc ensure-fn-or-processor
  "Barks if `p` is not fn or Processor."
  [p]
  (cond
    (instance? clojure.lang.IFn p) (fn->processor p)
    (instance? Processor p) p
    :else (throw (IllegalArgumentException.
                  (format "Arg %s not fn or Processor" p)))))

(defmacro defcontext
  "Defines `name` to DefaultCamelContext, adding
  the [RouteBuilder](https://static.javadoc.io/org.apache.camel/camel-core/2.18.2/org/apache/camel/builder/RouteBuilder.html)
  in `routes`. Note, the routes won't start unless the context isn't already
  started. Sets `nameStragety` field of `ctx` to `name`. **Remember**, nothing will start unless you call
  `(start name)`. See [[start]] and [[stop]].

  ```
  (defcontext foobar
  (route (from \"activemq:queue:hi\")
         (process (fn [xchg] (println xchg)))
         (to \"file:blah\")))

  (start ctx) ; pump a message to activemq on queue hi, will be printed to *out*
              ; note: this does NOT block! use a loop if you want your program to
              ; run
  (stop ctx)  ; shut down
  ```
  "
  [name & routes]
  `(let [ctx# (DefaultCamelContext.)]
     (.setName ctx# (str '~name))
     (doseq [route# (list ~@routes)]
       (.addRoutes ctx# route#))
     (def ~name ctx#)))

(defmacro route
  "Build a Camel [Route](http://camel.apache.org/routes.html), using [[from]], [[to]], [[process]] etc.

  An expression of `(route (from a) (foo b) (xyz) (bar baz bax))` maps directly to a Java equivalent of
  `.from(a).foo(b).xyz().bar(baz, bax)`.

  Binds `this` to a `RouteBuilder`, using the definitions in `routes`. The first
  element in `route` should be a call to [[from]], followed by
  either [[process]] or [[to]]. Note, adding a route with just a from, i.e., an
  empty `rest`, results an error when you start the context.

  ```
  (route 
    (from \"activemq:queue:hi\")
    (process (fn [x] (println x)))
    (to \"rabbitmq:blah\"))
  ```"
  [& routes]
  (let [expanded# (map macroexpand-1 routes)]
    `(proxy [RouteBuilder] []
       (configure []
         (.. ~'this ~@expanded#)))))

(defmacro from
  "Read from `endpoints`. 

  Must be the first expression inside a [[route]] block. Can only be called once
  in a [[route]] block.

  Each endpoint in `endpoints` can be a collection of either [Camel
  URIs](http://camel.apache.org/uris.html) or
  an [Endpoints](http://camel.apache.org/endpoint.html).

  You need to have the Camel component in the classpath. For example,
  `rabbitmq://` requires the RabbitMQ component, available in `camel-rabbitmq`,
  `activemq:...` requires ActiveMQ and so on.

  Makes the `RouteBuilder` defined in [[route]] to read from `endpoint`. Binds
  `this` to a `RouteDefinition` so that calls to [[to]] and [[process]] will
  work. You can call
  the [methods](https://static.javadoc.io/org.apache.camel/camel-core/2.18.2/org/apache/camel/model/RouteDefinition.html)
  of `this` to alter its behaviour. See [[route]].

  ```
  ;; will aggregate data from thee endpoints, printing the exchanges
  (route (from \"vm:foo\" \"direct:hello\" \"activemq:queue:bar\")
         (process (fn [x] (println x))))
  ```
  "
  [& endpoints]
  `(~'from (into-array (list ~@endpoints))))

(defmacro to
  "Send data to `endpoints`.
  
  Can be chained multiple times at any point after [[from]].

  Each endpoint in `endpoints` can be a collection of either [Camel
  URIs](http://camel.apache.org/uris.html) or
  an [Endpoints](http://camel.apache.org/endpoint.html).

  See the note about components in the docs for [[from]].

  Adds `endpoints`, Endpoints or String URI, to the `RouteDefinition`, sending
  exchanges to those endpoints. Must be after a [[from]]. See [[route]].

```
(route (from \"activemq:hello\")
       (to \"file:blaa\")
       (to \"kafka:topic:bar\"))
```
"
  [& endpoints]
  `(~'to (into-array (list ~@endpoints))))


(defmacro process
  "Add `p` as a processing step to this `RouteDefinition`. Useful for
  transforming a message before sending it forward with [[to]], or to replying
  to it with [[reply]]. Must be invoked after a call to [[from]]. 

  See also [[in]], [[out]], [[set-in]] and [[set-out]].

  `p` can be a one argument fn accepting an fn accepting one argument or a
  Processor. See [[fn->processor]]. Keep in mind, altering the exchange will
  affect subsequent inputs [[to]], [[filter]], [[process]] calls.

  ```
  ;; creates a Jetty HTTP server
  ;; get in -> get body -> reverse -> print
  (route (from \"jetty://localhost:33221/hello\")
         (process (comp println clojure.string/reverse body in))
         (to \"log:hello\")
  ```
  "
  [p]
  `(~'process (ensure-fn-or-processor ~p)))

(defn fn->predicate
  "Turn `pred` into a Predicate. `pred` should be a 1 arg function."
  [pred]
  (cond (ifn? pred) (reify Predicate
                      (matches [this exchange] (pred exchange)))
        (instance? Predicate pred) pred
        :else (throw (IllegalArgumentException. "pred is not IFn or Predicate!"))))

(defmacro filter
  "Filter exchanges using `pred`.
  
  A filter needs a downstream component, like [[to]] or [[process]]. It would be
  pretty useless otherwise, right? Without it, starting the associated context
  will blow up during start-up.

  `pred` should be an 1-arg function accepting an exchange and returning a boolean, or a Camel 
  [Predicate](http://camel.apache.org/predicate.html).
  
```
(route (from \"direct:foo\")
       (filter (fn [x] (starts-with? \"hello\" (body (in x)))))
       (process (fn [x] (println (str \"Made it:\" (body (in x))))))
       (to \"mock:faa\"))
```
  "
  [pred]
  `(~'filter (fn->predicate ~pred)))

(defmacro aggregate
  "Aggregate messages into one message based on a *correlation expression* and *strategy*. 
  
  The correlation expression identifies how messages should be correlated for
  aggregation. The strategy defines how the messages should be combined.

  All of the Camel expressions are supported directly. For a list of
  expressions, see the [Camel
  documentation](http://camel.apache.org/aggregator2.html). See the example
  below for how the *header* expression is used.
  
  For the strategy fn, the arguments will be the previous and the current exchange, and the value
  returned should be the new exchange. The condition of the aggregation (amount,
  time, etc.) can be configured using the completion condition (e.g. wait for N
  elements).
  
  **Note.** When the aggregating fn is called, its first argument may be nil
  when the aggregator is called for the first time.
  
  #### Completion condition
  
  The completion condition defines *when* the aggregation should start. You
  could, e.g., wait for elements to come in set of three, or when they match a
  certain predicate, or after a timeout.
  
  For convenience, some of the Java method calls have been named to shorter names:
  
  | Macro | Usage | Java API | Function 
  |-|-|-|-|
  | [[size]]  | `(size n)` | `completionSize` | aggregate `n` elements together |
  | [[timeout]]  | `(timeout ms)` | `completionTimeout` | aggregate elements after `ms` milliseconds |
  | [[interval]]  | `(interval ms)` | `completionInterval` | aggregate elements every `ms` milliseconds |
  | [[predicate]]  | `(predicate p)` | `completionPredicate` | aggregate elements when `p` matches |

  For more information, see the the documentation [on
  completion](http://camel.apache.org/aggregator2.html#Aggregator2-Aboutcompletion).
  
  #### Example

  ```
  ;; group messages together that have the same value for 
  ;; the cheese header, wait for three elements, combine their bodies,
  ;; and send downstream
  (route (from \"direct:hello\")
         (aggregate (header \"cheese\") 
                    (fn [old new]
                      (if old
                        (do
                          (in! old (str (in old) (in new)))
                          old)
                        new)))
         (size 3)
         (to \"mock:hello\"))
  ```
  "
  [expr strat] `(~'aggregate (. ~'this ~@expr)
                 (let [strat# ~strat]
                   (if-not (instance? AggregationStrategy strat#)
                     (aggregator ~strat)
                     strat#))))

(defmacro header
  "Used with [[aggregate]] and [[filter]]. Creates a [header
  expression](http://camel.apache.org/header.html)."
  [name]
  `(. ~'this ~'header ~name))

(defmacro size
  "Used with [[aggregate]]. Expect `n` items in aggregation."
  [n]
  `(~'completionSize ~n))

(defmacro timeout
  "Used with [[aggregate]]. Aggregate after `ms` milliseconds."
  [ms]
  `(~'completionTimeout ~ms))

(defmacro predicate
  "Used with [[aggregate]]. Aggregate when `pred` matches."
  [pred]
  `(~'completionPredicate (fn->predicate ~pred)))

(defmacro interval
  "Used with [[aggregate]]. Aggregate every `interval` milliseconds."
  [interval]
  `(~'completionInterval ~interval))

(defn aggregator
  "Create an aggregation strategy. Takes a fn of two arguments that combines two
  exchanges into one. Return the modified exchange. The first exchange may be null when the aggregation
  starts, see note. You can use this with [[aggregate]]. 

  **Note**. When aggregation gets its first message, its content will be
  `nil`. So be sure to check this."
  [myfn]
  (reify AggregationStrategy
    (aggregate [this x1 x2]
      (myfn x1 x2))))

(defn start
  "Starts `ctx`, does not block."
  [^CamelContext ctx]
  (.start ctx))

(defn stop
  "Stops `ctx`, shutting down all routes that go along with it."
  [^CamelContext ctx]
  (.stop ctx))

(defn context
  "Create a CamelContext. Optionally pass a [JNDI
  Context](http://docs.oracle.com/javase/7/docs/api/javax/naming/Context.html?is-external=true)
  or [Registry](http://camel.apache.org/registry.html)."
  ([] (DefaultCamelContext.))
  ([ctx-or-reg] (DefaultCamelContext. ctx-or-reg)))

(defn add-routes
  "Add the routes in `routes` to `ctx`."
  [ctx routes]
  (.addRoutes ctx routes))

