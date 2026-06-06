# com.walmartlabs/lacinia-pedestal

[![Clojars Project](https://img.shields.io/clojars/v/com.walmartlabs/lacinia-pedestal.svg)](https://clojars.org/com.walmartlabs/lacinia-pedestal)
[![CI](https://github.com/walmartlabs/lacinia-pedestal/actions/workflows/config.yml/badge.svg)](https://github.com/walmartlabs/lacinia-pedestal/actions/workflows/config.yml)
[![API DOCS](https://cljdoc.org/badge/com.walmartlabs/lacinia-pedestal)](https://cljdoc.org/d/com.walmartlabs/lacinia-pedestal)

A library that adds the
[Pedestal](https://github.com/pedestal/pedestal) underpinnings needed when exposing
[Lacinia](https://github.com/walmartlabs/lacinia) as an HTTP endpoint.

Lacinia-Pedestal also supports GraphQL subscriptions, using the same protocol
as [Apollo GraphQL](https://github.com/apollographql/subscriptions-transport-ws).

[Lacinia-Pedestal Manual](http://lacinia-pedestal.readthedocs.io/en/latest/) |
[API Documentation](http://walmartlabs.github.io/apidocs/lacinia-pedestal/)

## Usage

### Pedestal 0.8 Connector API (recommended)

For new applications, use `com.walmartlabs.lacinia.pedestal3` with the Pedestal 0.8
connector API:

```clojure
(ns graphql-demo.server
  (:require [io.pedestal.connector :as conn]
            [io.pedestal.http.jetty :as jetty]
            [com.walmartlabs.lacinia.pedestal3 :as lp]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]))

(def hello-schema
  (-> {:objects {:Query {:fields {:hello {:type 'String}}}}}
      (util/attach-resolvers {:Query/hello (constantly "hello")})
      schema/compile))

(def connector
  (-> (conn/default-connector-map 8888)
      (conn/with-routes #{["/api" :post (lp/default-interceptors hello-schema nil)
                           :route-name ::graphql-api]}
                        (lp/subscription-routes hello-schema))
      (jetty/create-connector nil)
      conn/start!))
```

Lacinia will handle POST requests at the `/api` endpoint:

```bash
$ curl localhost:8888/api -X POST -H "content-type: application/json" -d '{"query": "{ hello }"}'
{"data":{"hello":"world"}}
```

`pedestal3` does not include a `default-service` convenience function; applications
are expected to construct the connector map directly using the building blocks provided.

### Pedestal Legacy API (pedestal2)

For applications using the legacy `io.pedestal.http` API, use
`com.walmartlabs.lacinia.pedestal2/default-service`:

```clojure
(ns graphql-demo.server
  (:require [io.pedestal.http :as http]
            [com.walmartlabs.lacinia.pedestal2 :as lp]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]))

(def hello-schema
  (-> {:objects {:Query {:fields {:hello {:type 'String}}}}}
      (util/attach-resolvers {:Query/hello (constantly "hello")})
      schema/compile))

(def service (lp/default-service hello-schema nil))

(defonce runnable-service (http/create-server service))

(defn -main
  [& args]
  (println "\nCreating your server...")
  (http/start runnable-service))
```

You can also access the GraphQL IDE at `http://localhost:8888/ide`.

## Development Mode

When developing an application, it is desirable to be able to change the schema
without restarting.
Lacinia-Pedestal supports this: the schema passed to `default-interceptors` (or
`default-service` in `pedestal2`) can be a _function_ that returns the compiled schema,
or even a Var containing such a function.

In this way, the Pedestal stack continues to run, but each request rebuilds
the compiled schema based on the latest code you've loaded into the REPL.

## Beyond the defaults

`default-interceptors` (and `pedestal2/default-service`) are intentionally limited
scaffolding to help you get started. Once you add anything more sophisticated — such
as authentication, multiple schemas, or custom interceptors — you will want to
construct your routes and connector directly, using the building-blocks provided by
`com.walmartlabs.lacinia.pedestal3` (or `pedestal2` for the legacy API).

### GraphiQL

The GraphiQL packaged inside the library is built using `npm`, from
version `1.7.1`.

If you are including lacinia-pedestal via Git coordinate (rather than a published version
of the library by using a :mvn/version coordinate), then the library will need to be prepped for use 
via `clj -X:deps prep`.
 
The prep action for lacinia-pedestal requires that you have `npm` installed.  
The prep action generates the CSS and JavaScript files that are used
to execute GraphiQL.

## License

Copyright © 2017-2024 Walmart

Distributed under the Apache Software License 2.0.

GraphiQL has its own [license](https://raw.githubusercontent.com/graphql/graphiql/master/LICENSE).
