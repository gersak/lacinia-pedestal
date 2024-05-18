## 1.3 -- UNRELEASED

Bumped many dependencies to latest.

Bumped Pedestal dependency to 0.7.0-beta-1.

## 1.2 -- 23 Jun 2023

Logging from the `com.walmartlabs.lacinia.pedestal.subscriptions` namespace
now is at level TRACE, not DEBUG.

Dependency on Pedestal updated to 0.6.0.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/issues?q=is%3Aclosed+milestone%3A1.2)

## 1.1 -- 14 Jan 2020

It is common for a GraphQL service to frequently execute the same query with
different variables plugged in; it is now possible to provide a cache
to `com.walmartlabs.lacinia.pedestal2/query-parser-interceptor`, which will bypass
query parsing; this can reduce execution time and memory churn.

A subtle bug in subscriptions was fixed; a streamer that passes nil to
the source stream callback could prevent any values currently being executed
from being sent back to the client.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/18?closed=1)

## 1.0 -- 9 Oct 2021

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/17?closed=1)

## 0.16.1 -- 1 Jul 2021

Fixes a bug that prevented the request headers supplied in GraphiQL IDE from being
included in the request.  Request headers are now also persisted in the IDE URL, along
with the query and query variables.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/16?closed=1)

## 0.16 -- 18 Jun 2021

Updated the version of the packaged GraphiQL.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/15?closed=1)

## 0.15.0 -- 22 Jan 2021

Requests for GraphQL IDE assets will now return a 304 Not Modified response when the client
already has the current content.

Support for [Lacinia request tracing](https://lacinia.readthedocs.io/en/latest/tracing.html) has been added; 
it is enabled by default when using `com.walmartlabs.lacinia.pedestal2/default-service`.

Several interceptors from the `com.walmartlabs.lacinia.pedestal2` namespace have been modified to
clean up the context on exit (either `:leave`, or `:error`).

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/14?closed=1)

## 0.14.0 -- 30 Jun 2020

New `com.walmartlabs.lacinia.pedestal2` namespace de-complects
configuration.
The new namespace provides the same basic building blocks, but (beyond a very simple
`default-service` function) encourages you to assemble the system from the basics
as you see fit; the prior approach had become overrun with confusing options.

These changes make it far easier to support multiple schemas, or versions of schemas,
on different endpoints within the same server.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/13?closed=1)

## 0.13.0 -- 13 Feb 2020

The `inject-app-context-interceptor` function has moved to the new
`com.walmartlabs.lacinia.pedestal.interceptors` namespace. This merges
and replaces the versions from the `pedestal` and `pedestal.subscriptions` namespaces.

Exceptions while executing a query (asynchronously or otherwise) are now caught,
logged as an error, and converted into a 500 failure response.

New option `:ide-connection-params` supports setting connection parameters for subscriptions
in the GraphiQL IDE.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/12?closed=1)

## 0.12.0 -- 23 May 2019

GraphiQL assets are now bundled in this library, and are no longer
downloaded from a content distribution network.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/11?closed=1)

## 0.11.0 -- 4 Jan 2019

Update version of GraphiQL to current, 0.12.0.

Problems with compatibility with Clojure 1.8 were resolved.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/10?closed=1)

## 0.10.0 -- 14 Sep 2018

A spec for the `service-map` function is now provided.

Changes were made to allow for the updated structure of error maps in Lacinia 0.29.0.

The default set of interceptors has been split up slightly, in advance
of support for server-stored queries in an upcoming release.
In particular, the `:com.walmartlabs.lacinia.pedestal/query-parser` interceptor
was split in two: `query-parser` now *only* parses the query, and 
the new `:com.walmartlabs.lacinia.pedestal/prepare-query` interceptor prepares
the query itself (that is, applies query variables).

The signature of `com.walmartlabs.lacinia.pedestal/routes-from-interceptors`
has changed in an incompatible way; it now expects the compiled schema
as the first argument (previously it was the root path); this function
is not generally invoked from user code.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/9?closed=1)

## 0.9.0 -- 21 Jun 2018

Invalid JSON sent to lacinia-pedestal now results in proper 400 reponse,
rather than throwing an exception (and a 500 response).

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/8?closed=1)

## 0.8.0 -- 6 Jun 2018

The `com.walmartlabs.lacinia.pedestal.interceptors` namespaces has been
removed outright, and with it, support for interceptor dependency maps.
A scattering of other functions related to dependency maps have also been removed.


Added a number of options to control the creation of core.async channels used
to manage the flow of data for subscriptions.

Dependencies have been updated to latest.

For subscription requests, the connection parameters are now captured at connection time,
and available as the `:com.walmartlabs.lacinia/connection-params` key of the application context.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/7?closed=1)

## 0.7.0 -- 2 Mar 2018

The `com.walmartlabs.lacina.pedestal.interceptors` namespace has been deprecated; it will be removed
in the 0.8.0 release.
Instead, use the new `inject` function, which adds (or replaces) an interceptor to a seq of
interceptors.

The `interceptors` namespace, and other functions related to interceptor maps, will be removed
in the 0.8.0 release.  All functions scheduled for removal have been so marked in their docstrings.

The :api-key option has been removed, replaced with the :ide-headers option.
This is a more general approach that supports multiple headers with arbitrary names.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/6?closed=1)

## 0.6.0 -- 30 Jan 2018

It is now possible to configure the paths used to access the GraphQL endpoint and
the GraphiQL IDE.

A few functions that were inadventently made public have been made private.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/5?closed=1)

## 0.5.0 -- 5 Dec 2017

New function `com.walmartlabs.lacinia.pedestal/service-map` is now preferred
over `pedestal-service` (which has been deprecated).
`service-map` does *not* create the server; that is now the responsibility
of the calling code,
which makes it far easier to customize the service map before creating a server
from it.

Pedestal 0.5.3 includes a `Content-Security-Policy` header by default that disables
GraphiQL.
This header is now disabled when GraphiQL is enabled.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/4?closed=1)

## 0.4.0 -- 24 Oct 2017

The compiled-schema passed to `com.walmartlabs.lacinia.pedestal/pedestal-service` may now
be a function that _returns_ the compiled schema, which is useful when during testing
and development.

Added `com.walmartlabs.lacinia.pedestal.interceptors/splice`, which is
used to substitute an existing interceptor with a replacement.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/3?closed=1)

## 0.3.0 -- 7 Aug 2017

Added support for GraphQL subscriptions!
Subscriptions are patterned after [Apollo GraphQL](http://dev.apollodata.com/tools/graphql-subscriptions/index.html).
lacinia-pedestal should be a drop-in replacement for Apollo GraphQL server.

The default interceptor stack has been reordered, slightly.
New functions have been added to get the interceptors as a map
annotated with dependency meta-data, and to extract
the ordered interceptors from such a map.

`com.walmartlabs.lacinia.pedestal/graphql-routes` has been refactored:
it can be used as before, but its logic has been largely refactored
into smaller, reusable functions that are used when the default
interceptor stack must be modified for the application.

Error during request processing are now reported, in the response, in a more
consistent fashion.

This version of lacinia-pedestal only works with com.walmartlabs/lacinia **0.19.0** and above.

[Closed Issues](https://github.com/walmartlabs/lacinia-pedestal/milestone/2?closed=1)

## 0.2.0 -- 1 Jun 2017

The library and GitHub project have been renamed from `pedestal-lacinia` to
`lacinia-pedestal`.

Updated to com.walmartlabs/lacinia dependency to version 0.17.0.

Added an option to execute the GraphQL request asynchronously; when enabled,
the handler returns a channel that conveys the Pedestal context containing
the response, once the query has finished executing.

Introduced function `com.walmartlabs.lacinia.pedestal/inject-app-context-interceptor` and
converted `query-executor-handler` from a function to constant.

A new namespace, `com.walmartlabs.lacinia.async`, includes functions to adapt
field resolvers that return clojure.core.async channels to Lacinia.

## 0.1.1 -- 19 Apr 2017

Update dependency on com.walmartlabs/lacinia to latest version, 0.15.0.

## 0.1.0 -- 19 Apr 2017

First release.

