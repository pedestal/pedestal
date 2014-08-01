# What is Pedestal?

Pedestal is a collection of
interacting libraries that together create a pathway for developing
a specific kind of application. It empowers developers to use
Clojure to build internet applications requiring low-latency, streaming
(soft real-time) collaboration and targeting multiple platforms.

In short: Pedestal provides a better, cohesive way to build
rich client web applications in Clojure.

# Who is it for?

Clojurists looking for a standard way to build internet
applications will love Pedestal. Rather than composing art
out of found objects, they will now be able to mold a single,
consistent form to match their vision.

Pedestal may also appeal to developers who have been nervously
approaching a functional language but who haven't yet mustered the
courage to ask it out on a date. It provides a sterling example
of how to use the Clojure ecosystem to its best advantage, reducing
the friction usually associated with a language switch.

# Where do I start?

In [Hello World Service](hello-world-service.md), you will find a
walk-through that introduces you to all of Pedestal's moving parts via
the creation of a new server-side application.

# What about API Documentation?

To generate literate-programming-style documentation for the `app` and
`service` libraries, add the [lein plugin for
marginalia](https://github.com/fogus/lein-marginalia) to your lein user
profile. After installing the pedestal libraries you can then `cd` into the
`app` or `service` directories and run `lein marg`.

```bash
cat ~/.lein/profiles.clj
# {:user {:plugins [[lein-marginalia "0.7.1"]]}}

git clone https://github.com/pedestal/pedestal.git
cd pedestal
lein sub install
( cd service && lein marg )
```

This will create the documentation for `pedestal.service` in its `docs`
directory.
