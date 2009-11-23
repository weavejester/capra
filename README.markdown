Capra
=====

Capra is a dependency manager and package repository for the Clojure
programming language.

Using Capra
-----------

To use it, add `capra.jar` to your classpath, and include this line in
your `user.clj` file:

    (require 'capra)

Once you've done this, you can declare dependencies in the metadata for
your namespace, and the dependencies will be automatically downloaded
and added to the classpath at runtime.

For example:

    (ns #^{:deps ("weavejester/hello-world/1.0")}
      example-app
      (:use weavejester.hello-world))

    (hello-world)

If you have Capra set up, the "weavejester/hello-world/1.0" package
will be downloaded and installed when you run the script.

If you do not have Capra installed, this script will still run, but you
will have to manually download and add the "hello-world" package to the
classpath. This means that people don't have to have Capra installed to
use your Capra-enabled script.


Uploading packages to Capra
---------------------------

Uploading packages to Capra is also very straightforward. Open up a
Clojure REPL and register a Capra account with a username and email
address

    user=> (require 'capra.account)
    user=> (capra.account/register "jbloggs" "jbloggs@example.com")

You now have a Capra account. The passkey is generated randomly, and
stored in `$CAPRA_HOME/account.keys`. The environmental variable
`CAPRA_HOME` defaults to `$HOME/.capra` if not explicitly defined.

You can now upload a new package. Here is an example:

    user=> (require 'capra.package)
    user=> (capra.package/create
             {:account "jbloggs"
              :name "hello-world"
              :version "1.0"
              :files ["/home/jbloggs/hello-world.jar"]})

People can now access your package at "jbloggs/hello-world/1.0". You
can include it in the metadata of any script.

Querying the repository
-----------------------

There are currently a few basic ways to query the Capra repository.

The `capra.account/list` function returns a set of all accounts
registered in the repository:

    user=> (capra.account/list)
    #{"jbloggs", "weavejester"}

The `capra.account/get` function returns the public account data:

    user=> (capra.account/get "jbloggs")
    {:name "jbloggs"
     :email "jbloggs@example.com"
     :packages {"hello-world" {:name "hello-world"
                               :version "1.0"}}}

You can get more information from a particular package with
`capra.package/get`:

    user=> (capra.package/get "jbloggs/hello-world/1.0")     
    {:account "jbloggs"
     :name "hello-world"
     :version "1.0"
     :files ({:href "http://capra.s3.amazonaws.com/xxxxxxxxxxx"
              :sha1 "xxxxxxxxxxxxxx"})}

You can then install this package explicitly by using
`capra.package/install`:

    user=> (capra.package/install "jbloggs/hello-world/1.0")
    user=> (use 'jbloggs.hello-world)
    user=> (hello-world)
    Hello World
