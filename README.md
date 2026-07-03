# Capra [![Build Status](https://github.com/weavejester/capra/actions/workflows/test.yml/badge.svg)](https://github.com/weavejester/capra/actions/workflows/test.yml)

Capra is a Correct[^1] and Adequantly Performant[^2] [Ring][] Adapter.

Capra supports HTTP/1.1 only and will not support older versions.
WebSocket support is planned in the near future. HTTP/2 support is
planned after that.

[^1]: 'Correct' because Capra aims to be a well-behaved HTTP server.
[^2]: 'Adequately Performant' because Capra aims to be more performant
than the [Ring Jetty Adapter][], the most commonly used adapter.

[ring]: https://github.com/ring-clojure/ring

## Installation

Add the following dependency to your deps.edn file:

    dev.weavejester/capra {:mvn/version "0.1.0-SNAPSHOT"}

Or to your Leiningen project file:

    [dev.weavejester/capra "0.1.0-SNAPSHOT"]

## Rationale

There are a number of web servers for Clojure, but all of them are
written in Java; either because they are wrappers around existing Java
web servers, such as the [Ring Jetty Adapter][], or because they are
heavily optimized, such as [http-kit][].

Capra is written entirely in Clojure, and has only one dependency,
[TeensyP][], which is also entirely[^3] written in Clojure. This has
several advantages:

1. It avoids the limitations and performance hit of wrapping Servlets.
2. It can be more easily ported to Clojure-like environments that don't
   use the JVM.
3. It can be used as a platform to experiment with future Ring features.

[^3]: Excepting a couple of interfaces that are used to avoid using
`proxy` when creating custom `InputStream` and `OutputStream` classes.

[ring jetty adapter]: https://ring-clojure.github.io/ring/ring.adapter.jetty.html
[http-kit]: https://github.com/http-kit/http-kit
[teensyp]: https://github.com/weavejester/teensyp

## Usage

TODO

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
