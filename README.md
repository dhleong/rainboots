# rainboots

Protect yourself from the grossness of writing a MUD server

## What

Rainboots is super barebones, providing some important core functionality
without getting in the way of making any sort of MUD experience you can
imagine. It aims to be flexible and extensible, so that any number of
features can be plugged in and shared, but is unopinionated about the
ultimate experience. Almost every aspect---from low-level input routing
to user authentication---can be replaced and redesigned. 

Rainboots doesn't even initially provide a room or navigation system, 
or even any basic commands---though it does provide some excellent tools 
for developing them!

Rainboots is implemented in Clojure for flexibility and rapid development.
New commands (or updated versions of existing commands!) can easily be 
swapped in with a REPL on the fly, without needing to restart the server.

## How

A [sample](src/rainboots/sample.clj) is included with Rainboots that shows
some basic usage, which we will go into here.

### Starting the server

```clojure
;; you can refer specific functions if you like, but this is
;;  the easiest way to get started:
(ns your.awesome.game
  (:require [rainboots
             [command :refer :all]
             [core :refer :all]]))

(defn start-sample
  []
  (def svr 
    ;; Handlers and configuration are provided via keyword args.
    ;; Rainboots provides some sane defaults, but there are a few
    ;;  handlers you must provide yourself
    (start-server
      ;; Since rainboots doesn't provide its own auth mechanism,
      ;;  you must install your own. The handler is just a function,
      ;;  called with a client object and the user's input line (see below)
      :on-auth on-auth
      ;; The :on-connect handler is called as soon as a client connects,
      ;;  with a client object as its arg. Sure, we could provide a default
      ;;  for this, as well, but you'll want to customize the experience anyway
      :on-connect on-connect)))
```

That's it! Your basic MUD server is running. Before any commands can be accepted,
however, you'll need to implement that `on-auth` handler.

### Client Objects

Before we talk about auth, let's learn about client objects. A client object is 
simply a clojure map wrapped in an [atom](http://clojure.org/reference/atoms).
There are a few "reserved" keywords (enumerated below) which you should not overwrite 
(rainboots won't stop you; do so at your own peril!), but otherwise feel free
to store any transient data in the client object/atom.

"Reserved" keywords:

- `:stream` holds the connection object
- `:ch` holds your character data, 
- `:in` should be used for the current room object, if you're into that

Character data management and formatting is totally up to you. You may want to put
another `atom` there for more easy swapping, but rainboots doesn't care. As long as
there is *some* non-`nil` value stored there, it will assume you are logged in.

### Basic Auth

Now that we know what a client object is (typically abbreviated `cli` in handlers),
let's look at how to implement auth. Here is a minimal example:

```clojure
(defn on-auth
  [cli username]
  (swap! cli assoc :ch username)
  (send! cli "Welcome back, " username "!")
```

This will be a very simple MUD, with no stats or attributes storable in the user,
but rainboots doesn't mind. As long as `:ch` is non-`nil`, the user is logged in,
and the `on-cmd` handler will be called (see below). 

A more interesting `on-auth` will probably want to use the client atom as temporary
storage for checking auth. Here's a more complete example, where each character has
their own username and password. Persistence is left to the user.

```clojure
(defn on-auth
  [cli line]
  (if-let [u (:user @cli)] 
    ;; they've already provided a username
    (do
      ;; probably load the character by username from a db or flat file,
      ;;  then compare the password
      (if-let [ch (validate-and-load u line)]
        (do
          (swap! cli assoc :ch ch)
          ;; note the {W escape sequence; this is for colors! (see below)
          (send! cli "Logged in as {W" (-> ch :name) "{n"))))
        (do
          (swap! cli dissoc :user)
          (send! cli "Invalid username/password combo"))
    ;; first input; store as a username and prompt for a password
    (do
      (swap! cli assoc :user line)
      (send! cli "Password:"))))
```

Because `on-auth` is plug-and-play, you can implement it however you like. You
could have a single login with multiple characters, or even support two-factor auth!
The sky is the limit.

### Command Handling

Rainboots comes with a pretty powerful `on-cmd` handler installed by default.
You should rarely need to replace the default `on-cmd` handler, but you are more
than welcome to. As with `on-auth`, the `on-cmd` handler is called with a client
object, and a line of input. Since there are many ways to handle commands, and most
of them annoying to manage, rainboots provides a convenient way to get started:

```clojure
(defcmd broadcast
  "Send a message to everybody online"
  [cli ^:rest text]
  (send-all! (-> @cli :ch :name) " broadcasts: " text))
```

This demonstrates a couple things. First, the `(defcmd)` macro, which installs a
command into the default set with a familiar syntax, and "argument types." Any
logged-in user will be able to type `broadcast Hi!` to greet the whole mud. In
fact, we automatically build out shortcuts, so with no other commands def'd, you
could just type `b Hi!`. The `:rest` argument type that annotates the `text`
argument  to get the entire rest of the input does not come with rainboots,
but would be easy enough to implement. See below for more on "argument types."

By default, user input is destructured into arguments based on whitespace. For
example, imagine this command:

```clojure
(defcmd put
  "Put something somewhere"
  [cli what where]
  ;; this part is up to you
  )
```

A user-input line "put gun holster" will be automatically destructured for you.
To make this really convenient, however, you'll want to make some argument types.

#### Argument Types

A powerful feature built into rainboots is the notion of "argument types." These
are basically keyword annotations for command argument which transform the user's
input into the appropriate object, so commands can just declare what they expect,
and rainboots can handle validating the user's input and providing the objects.

Here's how to implement that `^:rest` type shown above:

```clojure
(defargtype :rest
  "A string of text"
  [cli input]
  [input nil])
```

What? That's it!? 

`defargtype` installs the argument type handler globally, and also uses `(defn)`-like
syntax. Every handler MUST return a vector, whose first item is the resulting object,
and whose second item is the remaining part of the input to parse. Handlers are provided
a client object and the remaining input line at that point. This lets you do fancy things
like supporting "sword in stone" as a single `^:item` argument.

### Command sets

TODO (or, put it in the wiki)

### Colors

When using the built-in `(send!)` method, strings will automatically be colorized
with ansi using some built-in escape sequences.

TODO document color sequences (or, put this in the wiki)

## License

Copyright © 2016 Daniel Leong

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
