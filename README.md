# rainboots [![Clojars Project](https://img.shields.io/clojars/v/rainboots.svg?style=flat)](https://clojars.org/rainboots) [![Build Status](http://img.shields.io/travis/dhleong/rainboots.svg?style=flat)](https://travis-ci.org/dhleong/rainboots)

*A more elegant way to make [MUD][1]*

## What

Rainboots is super barebones, providing some important core
functionality without getting in the way of making any sort of MUD
experience you can imagine. It aims to be flexible and extensible, so
that any number of features can be plugged in and shared, but is
unopinionated about the ultimate experience. Almost every aspect---from
low-level input handling to user authentication---can be replaced and
redesigned.

Rainboots doesn't even initially provide a room or navigation system,
or even any basic commands---though it does provide some excellent
tools for developing them!

Rainboots is implemented in Clojure for flexibility and rapid
development.  New commands (or updated versions of existing commands!)
can easily be swapped in with a REPL on the fly, without needing to
restart the server.

At its core, Rainboots is a fancy telnet server, capable of sending and
receiving raw telnet signals, so if you want to make a telnet server
for whatever reason, Rainboots can be used for that, as well. Some (but
not all) telnet commands will be parsed into a nice, friendly Keyword,
but it will fall back to the integer value for any it doesn't
know---feel free to send a PR with any missing signals!

## How

A [sample][2] is included with Rainboots that shows
some basic usage, which we will describe in more detail here:

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

That's it! Your basic MUD server is running. Before any commands can be
accepted, however, you'll need to implement that `on-auth` handler.

### Client Objects

Before we talk about auth, let's learn about client objects. A client object
is simply a clojure map wrapped in an [atom][3].  There are a few "reserved"
keywords (enumerated below) which you should not overwrite (rainboots won't
stop you; do so at your own peril!), and any keywords in the `rainboots.core`
namespace should probably also be left alone, but otherwise feel free to store
any transient data in the client object/atom.

"Reserved" keywords:

- `:stream` holds the connection object, used by `(send!)`
- `:ch` holds your character data, once auth'd
- `:input-stack` is used by the default command handler for command sets
- `:term-types` is a set of strings reported by the client

Other keywords:

- `:rainboots.core/closed?` is `true` if an only if the client is disconnected
- `:rainboots.core/remote` is a map describing the remote connection (includes
    keys like `:remote-user`)

Character data management and formatting is totally up to you. As long as
there is *some* non-`nil` value stored in `:ch`, it will assume you are logged
in.

### Basic Auth

Now that we know what a client object is (typically abbreviated `cli`
in handlers), let's look at how to implement auth. Here is a minimal
example:

```clojure
(defn on-auth
  [cli username]
  (swap! cli assoc :ch username)
  (send! cli "Welcome back, " username "!")
```

This will be a very simple MUD, with no stats or attributes storable in
the user, but rainboots doesn't mind. As stated above, so long as `:ch`
is non-`nil`, the user is "logged in," and the `on-cmd` handler will be
called (see below) instead of `on-auth`.

A more interesting `on-auth` will probably want to use the client atom
as temporary storage for checking credentials. Here's a more complete
example, where each character has their own username and password.
Persistence is left to the user.

```clojure
(defn on-auth
  [cli line]
  (if-let [u (:user @cli)]
    ;; they've already provided a username
    (do
      ;; probably load the character by username from a db or flat file,
      ;;  then compare the (hashed) password
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

Because `on-auth` is plug-and-play, you can implement it however you
like. You could have a single login with multiple characters, or even
support two-factor auth!  The sky is the limit.

### Communicating with clients

You've seen some examples of the `send!` function up there, but it can do
a lot more than you might have thought. For example, instead of embedding
colors in the string with escape sequences, why not use a [hiccup][4]-inspired
syntax?

```clojure
(send! cli "Logged in as " [:W (-> ch :name)])
```

All the same colors are supported as keyword tags, but with hiccup they
automatically close themselves. They even support nesting:

```clojure
; these two lines are functionally identical:
(send! cli "{YCaptain {rMal{Y Reynolds{n")
(send! cli [:Y "Captain " [:r "Mal"] " Reynolds"])
```

Notice how with hiccup syntax, we didn't need to remember to re-set the yellow
color after the inner red color block!

#### Customizing Hiccup

Of course, in addition to the colors built-in, you can provide your own
hiccup handlers. A hiccup handler in Rainboots is just a function that
takes the client receiving the message and whatever arguments you passed
to it. For example:

```clojure
(defn upper [cli text]
  (str/upper-case text))

(send! cli "You yell, " [upper "Where'd you go?"])
```

If you'd like to declare your handler as a keyword, you can do that too:

```clojure
(rainboots.hiccup/defhandler :upper
  [cli text]
  (str/upper-case text))

(send! cli "You yell, " [:upper "Where'd you go?"])
```

Since you're given the receiving client, you can do neat things like customize
the output depending on who's receiving it:

```clojure
(rainboots.hiccup/defhandler :name
  [cli person]
  (if (ch-knows? (:ch @cli) person)
    (:name person)
    "Someone"))

(send! cli [:name sender] " yells, " [:upper "I'm over here!"])
```


### Command Handling

Rainboots comes with a pretty powerful `on-cmd` handler installed by
default.  You should rarely need to replace the default `on-cmd`
handler, but you are more than welcome to. As with `on-auth`, the
`on-cmd` handler is called with a client object, and a line of input.
Since there are many ways to handle commands, and most of them annoying
to manage, rainboots provides a convenient way to get started:

```clojure
(defcmd broadcast
  "Send a message to everybody online"
  [cli ^:rest text]
  (send-all! (-> @cli :ch :name) " broadcasts: " text))
```

This demonstrates a couple things. First, the `(defcmd)` macro, which
installs a command into the default set with a familiar syntax, and
"argument types." Any logged-in user will be able to type `broadcast
Hi!` to greet the whole mud. In fact, we automatically build out
shortcuts, so with no other commands def'd, you could just type `b
Hi!`. The `:rest` argument type that annotates the `text` argument to
get the entire rest of the input does not come with rainboots, but
would be easy enough to implement. See below for more on "argument
types."

By default, user input is destructured into arguments based on
whitespace. For example, imagine this command:

```clojure
(defcmd put
  "Put something somewhere"
  [cli what where]
  ; this part is up to you!
  )
```

A user-input line "put gun holster" will be automatically destructured
for you.

Commands don't always follow a single format, however, so `defcmd`
supports multi-arity out of the box:

```clojure
(defcmd look
  "Look around you, or at something"
  ([cli]
   (send! cli "You're on a run-down old ship (But don't call it that!)"))
  ([cli thing]
   (send! cli thing " isn't very interesting to look at")))
```

To make this really convenient, however, you'll want to make some
argument types.

#### Argument Types

A powerful feature built into rainboots is the notion of "argument
types." These are basically keyword annotations for command arguments
which transform the user's input into the appropriate object, so
commands can just declare what they expect, and rainboots can handle
validating the user's input and providing the objects.

Here's how to implement that `^:rest` type shown above:

```clojure
(defargtype :rest
  "A string of text"
  [cli input]
  [input nil])
```

What? That's it!?

`defargtype` installs the argument type handler globally, and also uses
`(defn)`-like syntax. Every handler MUST return a vector, whose first
item is the resulting object, and whose second item is the remaining
part of the input to parse. Handlers are provided a client object and
the remaining input line at that point. This lets you do fancy things
like supporting "sword in stone" as a single `^:item` argument.

For more specificity per-command, you may use the map annotation style
to provide a parameter to your argtype. For example, you could annotate
a param as `^{:item :on-ground}` if you only want the item if it is on
the ground. The argtype def should then look something like:

```clojure
(defargtype :item
  "An item somewhere"
  [cli input & [param]]
  (cond
    (= :on-ground param)
    ;; .. etc
    :else ;; ...
    ))
```

Argtypes may not always "work," however. Even if the user provided
parse-able input, it might be invalid. Having to handle that everywhere
you use an argtype is problematic, so you may return a `Throwable`
instead of a value. If any argument is parsed to a `Throwable`, the
message in the first `Throwable` found will be sent to the user, and
your command handler will not be called. For example:

```clojure
(defargtype :item
  "An item somewhere"
  [cli input]
  (if-let [[item etc] (find-item cli input)]
    ;; found it!
    [item etc]
    ;; no such item found:
    [(Exception. (str "I don't see any " input)), etc]))
```

Sometimes, you might want an argtype that isn't directly supplied by
the user's input, for example if you have combat commands that require
a previously-specified target. Using an argtype is a convenient way to
access this, and have the logic to verify the existence of that target
be unified in a single place. For such an argtype, the input is
generally not necessary, so you can mark it as `:nilable`, meaning that
it's okay if it's `nil`—normally, an argtype is only called if there's
some input left to handle. For example:

```clojure
(defargtype :target
  "Your current target"
  [cli ^:nilable input]
  [(if-let [target (:target @cli)]
    target
    (Exception. "You must target something first"))
    input])  ; return the input unchanged
```


### Command sets

Normally, all commands are added to a default "command set." Sometimes,
however, you may wish to put the user into a special mode where they
have access to only specific commands. You can do this via
`(push-cmds!)` and `(pop-cmds!)`.

`(push-cmds!)` takes a client object, and the command set. You can
define a command set using, you guessed it:

```clojure
(defcmdset combat-commands
  (defcmd punch
    [cli]
    (send! cli "You swing a punch!")))
```

Any `defcmd`s inside a `defcmdset` will be bound to that set, and only
visible after a call to `(push-cmds! cli combat-commands)`.

In fact, a cmdset is just a function, which looks like `(fn [on-404 cli
input])`. `on-404` is the registered "unknown command" function
installed on the server, and the rest is as you expect. So, if you want
full control over the input and don't wish to use `defcmd` or
`defcmdset`, you can just `(push-cmds!)` your own function!

### Hooks

Hooks allow you to provide information throughout the system without
having any direct dependencies. For example, you might have a hook for
"wearing" an item.  There might be different types of effects
associated with an item, such as armor points, or magical properties.
You could store these properties as keywords on the item, and apply
them by *hooking into* the "wear" event.

For example:

```clojure
;;
;; Register hooks
;;

; magic-items.clj:
(hook! :wear-item
  ; Note the use of a named fn here: It's not
  ; required, but your repl experience will be
  ; better if you do.
  (fn wear-magic-item [{:keys [cli item] :as arg}]
    (when-let [magic (:magic item)]
      (apply-magic! cli magic))
    arg))

; armor.clj:
(hook! :wear-item
  (fn wear-armor [{:keys [cli item] :as arg}]
    (when-let [armor (:armor item)]
      (apply-armor! cli armor))
    arg))

;;
;; Execute hooks
;;

(defcmd wear
  "Wear an item"
  [cli ^:item item]
  (when (trigger! :wear-item {:cli cli :item item})
    ;; You could potentially support returning nil
    ;;  to indicate that the item couldn't be worn;
    ;;  the semantics of each hook is up to you!
    (add-equip! cli item))
```

#### Hook ordering

By default, the order in which hooks are triggered is undefined, since
the order in which they are added depends on when you load the
namespace they're declared in, but you can provide a specific priority
if you need to make sure that some hook is executed before another:

```clojure
(hook! :wear-item
  {:priority 10}
  (fn wear-magic-item [{:keys [cli item] :as arg}]
    ; etc
    )
```

If unspecified, a hook's priority will be `0`. Higher-priority hooks
will be executed before lower-priority hooks.

#### Stopping early

If the semantics of your hook are that not every registered hook fn
needs to see the input, you may wrap the returned value with `reduced`
to indicate that the given value **is** the *result*, and that no other
hook fn needs to run:

```clojure
(hook! :wear-item
  {:priority 10}
  (fn prevent-wearing-unwearables [{:keys [cli item] :as arg}]
    (if (wearable? item)
      arg ; proceed

      (do
        (send! cli "You can't wear that!")
        (reduced {})))))
```

#### Default hooks

Sometimes you want to provide a "fallback" hook that only gets called
when nobody else was interested. Rainboots has a couple options here:

- `:when-only`  With this option, the hook fn will *only* be called if
  no other fn is registered for this hook.
- `:when-no-result`  With this option, the hook fn will *only* be
  called if no other fn has produced a *result* (see above).

If you're only providing one of these options and not priority (they
are essentially mutually exclusive, so this should be the normal case)
you can use a set instead of a map, for example:

```clojure
(hook! :perform-action
  #{:when-no-result}
  (fn default-perform-action [arg]
    ; etc
    ))
```

#### Builtin hooks

Rainboots uses hooks internally to provide ways for you to extend or replace
default behaviors.

Hook | Description
---- | -----------
`:process-send!` | All strings sent with `(send!)` are processed by triggering the `:process-send!` hook with a map containing the recipient as `:cli` and the text to send as `:text`.  This is how the built-in colorization is applied, which means you can fully disable it by doing `(unhook! :process-send! rainbooks.comms/default-colorize-hook)`.<br><br>In fact, `(send!)`, `(send-if!)`, and `(send-all!)` all support an optional first parameter `process-extras` which is a map whose keys and values will be included in the argument to the `:process-send!` hook, so you can provide extra information to your own custom output processing functions.

### Colors

When using the built-in `(send!)` method, strings will automatically be colorized
with ansi using some built-in escape sequences. The foreground color can be
changed using the `{` character, followed by a color. Here's a table:

Symbol | Color   | Symbol | Color
-------|---------|--------|------
`{d`   | ![Dark](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/43c72287b5a987b545b2a81480767527b4d8dab1/ansi-d-dark.svg)    | `{D`   | ![Less-Dark](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/82dad12dbfe04766120883be5d74dd2f87df57fd/ansi-b-dark.svg)
`{r`   | ![Red](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/43c72287b5a987b545b2a81480767527b4d8dab1/ansi-d-red.svg)     | `{R`   | ![Bright Red](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/2e3d51c6bb96b9f20ead14161edba4c66be57818/ansi-b-red.svg)
`{g`   | ![Green](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/43c72287b5a987b545b2a81480767527b4d8dab1/ansi-d-green.svg)   | `{G`   | ![Bright-green](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/1ce48bb5af4ba4207ef7469e99a4ef9e3882a677/ansi-b-green.svg)
`{y`   | ![Yellow](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/43c72287b5a987b545b2a81480767527b4d8dab1/ansi-d-yellow.svg)  | `{Y`   | ![Bright-yellow](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/1ce48bb5af4ba4207ef7469e99a4ef9e3882a677/ansi-b-yellow.svg)
`{b`   | ![Blue](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/43c72287b5a987b545b2a81480767527b4d8dab1/ansi-d-blue.svg)    | `{B`   | ![Bright-blue](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/2a2b5b6583abe9e60e1eaed141fc585d4db74095/ansi-b-blue.svg)
`{p`   | ![Magenta](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/43c72287b5a987b545b2a81480767527b4d8dab1/ansi-d-magenta.svg) | `{P`   | ![Bright-Magenta](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/2a2b5b6583abe9e60e1eaed141fc585d4db74095/ansi-b-magenta.svg)
`{c`   | ![Cyan](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/43c72287b5a987b545b2a81480767527b4d8dab1/ansi-d-cyan.svg)    | `{C`   | ![Bright-cyan](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/2a2b5b6583abe9e60e1eaed141fc585d4db74095/ansi-b-cyan.svg)
`{w`   | ![Gray](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/43c72287b5a987b545b2a81480767527b4d8dab1/ansi-d-white.svg)    | `{W`   | ![White](https://cdn.rawgit.com/dhleong/cd96df9cb4c58e9db7d2f88fac4a3d29/raw/2a2b5b6583abe9e60e1eaed141fc585d4db74095/ansi-b-white.svg)
`{n`   | (Reset) | |

## License

Copyright © 2016-2019 Daniel Leong

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

[1]: https://en.wikipedia.org/wiki/MUD
[2]: src/rainboots/sample.clj
[3]: http://clojure.org/reference/atoms
[4]: https://github.com/weavejester/hiccup/
