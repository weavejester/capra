# Capra [![Build Status](https://github.com/weavejester/capra/actions/workflows/test.yml/badge.svg)](https://github.com/weavejester/capra/actions/workflows/test.yml)

Capra is a Correct and Adequantly Performant [Ring][] Adapter. It's
written entirely in Clojure and depends only on [TeensyP][], a TCP
server that's also written in Clojure.

Capra supports HTTP/1.1 only and will not support older versions.
WebSocket support is planned in the near future. HTTP/2 support is
planned after that.

[Ring]: https://github.com/ring-clojure/ring
[TeensyP]: https://github.com/weavejester/teensyp

## Installation

Add the following dependency to your deps.edn file:

    dev.weavejester/capra {:mvn/version "0.1.0-SNAPSHOT"}

Or to your Leiningen project file:

    [dev.weavejester/capra "0.1.0-SNAPSHOT"]

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
