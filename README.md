# Capra [![Build Status](https://github.com/weavejester/capra/actions/workflows/test.yml/badge.svg)](https://github.com/weavejester/capra/actions/workflows/test.yml)

Capra is a Correct[^1] and Adequantly Performant[^2] [Ring][] Adapter.

Capra supports HTTP/1.1 only and will not support older versions.
WebSocket support is planned in the near future. HTTP/2 support is
planned after that.

[^1]: 'Correct' because Capra aims to be a well-behaved HTTP server.
[^2]: 'Adequately Performant' because Capra aims to be at least more
performant than the [Ring Jetty Adapter][], the most commonly used
adapter.

[ring]: https://github.com/ring-clojure/ring

## Installation

Add the following dependency to your deps.edn file:

    dev.weavejester/capra {:mvn/version "0.1.0-SNAPSHOT"}

Or to your Leiningen project file:

    [dev.weavejester/capra "0.1.0-SNAPSHOT"]

## Rationale

A number of web servers already exist for Clojure, but all of them are
predominantly written in Java; either because they are wrappers around
existing Java web servers, such as the [Ring Jetty Adapter][], or
because they are heavily optimized, such as [http-kit][].

Capra is written entirely in Clojure, and has only two dependencies:
Ring Core Protocols and [TeensyP][], which is also written entirely[^3]
in Clojure. This has several advantages:

1. The codebase is more concise, making it a flexible platform for
   trying out experimental Ring features.
2. It avoids the limitations and performance hit of wrapping an existing
   Java API, such as Jetty.
3. It can be more easily ported to Clojure-like environments that don't
   use the JVM.

[^3]: Excepting a couple of interfaces that are used to avoid using
`proxy` when creating custom `InputStream` and `OutputStream` classes.

[ring jetty adapter]: https://ring-clojure.github.io/ring/ring.adapter.jetty.html
[http-kit]: https://github.com/http-kit/http-kit
[teensyp]: https://github.com/weavejester/teensyp

## Usage

Given a Ring handler function:

```clojure
(defn handler [_request]
  {:status  200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    "Hello World"})
```

We can start a web server using the `capra.server/run-server` function:

```clojure
(require '[capra.server :as capra])

(def server (capra/run-server handler :port 4000))
```

The server will run in a separate thread until closed:

```clojure
(.close server)
```

## Performance

The following benchmark tests different adapters running a Ring handler
that returns a simple 'Hello World' response. The default options for
each adapter are used, and they're tested with `wrk` using 2 threads and
128 connections on a CCX13 cloud server from Hetzner.

![Capra benchmark results](docs/benchmarks.png)

## License

Copyright © 2026 James Reeves

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
