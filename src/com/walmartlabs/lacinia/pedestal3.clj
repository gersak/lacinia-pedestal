; Copyright (c) 2025-present Walmart, Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns com.walmartlabs.lacinia.pedestal3
  "Utilities for creating handlers, interceptors, and routes maps needed by a Pedestal service
that exposes a GraphQL API and GraphiQL IDE."
  {:added "1.4.0"}
  (:require [com.walmartlabs.lacinia.pedestal.interceptors :as interceptors]
            [io.pedestal.interceptor :refer [interceptor]]
            [com.walmartlabs.lacinia.pedestal.internal :as internal]))

(def json-response-interceptor
  "An interceptor that sees if the response body is a map and, if so,
  converts the map to JSON and sets the response Content-Type header."
  (interceptor
    {:name  ::json-response
     :leave internal/on-leave-json-response}))

(def error-response-interceptor
  "Returns an internal server error response when an exception was not handled in prior interceptors.

   This must come after [[json-response-interceptor]], as the error still needs to be converted to json."
  (interceptor
    {:name  ::error-response
     :error internal/on-error-error-response}))

(def body-data-interceptor
  "Converts the POSTed body from a input stream into a string, or rejects the request
  with a 400 response if the content type is not application/json."
  (interceptor
    {:name  ::body-data
     :enter (internal/enter-body-data :json-body)}))

(def graphql-data-interceptor
  "Comes after the request body has been parsed, extracts the JSON query and other data into request keys
  :graphql-query (the query document as a string),
  :graphql-vars (a map)
  and :graphql-operation-name (a string).

  These keys are dissoc'ed on leave, or on error."
  (interceptor
    {:name  ::graphql-data
     :enter (internal/enter-graphql-data :json-body)
     :leave internal/clear-graphql-data
     :error internal/error-graphql-data}))

(def missing-query-interceptor
  "Rejects the request with a 400 response is the JSON query variable is missing or blank.

  Comes after [[graphql-data-interceptor]]."
  (interceptor
    {:name  ::missing-query
     :enter internal/enter-missing-query}))

(defn query-parser-interceptor
  "Given a compiled schema, returns an interceptor that parses the query.

   `compiled-schema` may be the actual compiled schema, or a no-arguments function
   that returns the compiled schema.

   Expected to come after [[missing-query-interceptor]] in the interceptor chain.

   Adds a new request key, :parsed-lacinia-query, containing the parsed query.
   This key is removed on leave or on error.

   `cache` defaults to nil, it should implement [[ParsedQueryCache]].

   Before execution, [[prepare-query-interceptor]] injects query variables and performs
   validations."
  ([compiled-schema]
   (query-parser-interceptor compiled-schema nil))
  ([compiled-schema cache]
   (interceptor
     {:name  ::query-parser
      :enter (fn [context]
               (internal/on-enter-query-parser context compiled-schema cache (get-in context [:request ::timing-start])))
      :leave internal/on-leave-query-parser
      :error internal/on-error-query-parser})))

(def prepare-query-interceptor
  "Prepares (with query variables) and validates the query, previously parsed
  by [[query-parser-interceptor]]."
  (interceptor
    {:name  ::prepare-query
     :enter internal/on-enter-prepare-query}))

(def status-conversion-interceptor
  "Checks to see if any error map in the :errors key of the response
  contains a :status value (under it's :extensions key).
  If so, the maximum status value of such errors is found and used as the status of the overall response, and the
  :status key is dissoc'ed from all errors."
  (interceptor
    {:name  ::status-conversion
     :leave internal/on-leave-status-conversion}))

(def disallow-subscriptions-interceptor
  "Handles requests for subscriptions.  Subscription requests must only be sent to the subscriptions web-socket, not the
  general query endpoint, so any subscription request received in this pipeline is a bad request."
  (interceptor
    {:name  ::disallow-subscriptions
     :enter internal/on-enter-disallow-subscriptions}))

(defn inject-app-context-interceptor
  "Adds a :lacinia-app-context key to the request, used when executing the query.

  The provided app-context map is augmented with the request map, as key :request.

  On leave (or error), the :lacinia-app-context key is dissoc'ed.

  It is not uncommon to replace this interceptor with one that constructs
  the application context dynamically; for example, to extract authentication information
  from the request and expose that as app-context keys."
  [app-context]
  (interceptor
    {:name  ::inject-app-context
     :enter (interceptors/on-enter-app-context-interceptor app-context)
     :leave interceptors/on-leave-app-context-interceptor
     :error interceptors/on-error-app-context-interceptor}))

(def query-executor-handler
  "The handler at the end of interceptor chain, invokes Lacinia to
  execute the query and return the main response.

  This comes last in the interceptor chain."
  (interceptor
    {:name  ::query-executor
     :enter (internal/on-enter-query-executor ::query-executor)}))

(def initialize-tracing-interceptor
  "Initializes timing information for the request; largely, this captures the earliest
  possible start time for the request (before any other interceptors), just in case
  tracing is enabled for this request (that decision is made by [[enable-tracing-interceptor]])."
  (interceptor
    {:name  ::initialize-tracing
     :enter (internal/enter-initialize-tracing ::timing-start)}))

(def enable-tracing-interceptor
  "Enables tracing if the `lacinia-tracing` header is present."
  (interceptor
    {:name  ::enable-tracing
     :enter internal/enter-enable-tracing}))

(defn default-interceptors
  "Returns the default set of GraphQL interceptors, as a seq.

  This should be considered *scaffolding*, suitable only for the initial stages of development,
  and should be replaced in an actively maintained code base with direct calls to the desired interceptors.

    * ::initialize-tracing [[initialize-tracing-interceptor]]
    * ::json-response [[json-response-interceptor]]
    * ::error-response [[error-response-interceptor]]
    * ::body-data [[body-data-interceptor]]
    * ::graphql-data [[graphql-data-interceptor]]
    * ::status-conversion [[status-conversion-interceptor]]
    * ::missing-query [[missing-query-interceptor]]
    * ::query-parser [[query-parser-interceptor]]
    * ::disallow-subscriptions [[disallow-subscriptions-interceptor]]
    * ::prepare-query [[prepare-query-interceptor]]
    * ::inject-app-context [[inject-app-context-interceptor]]
    * ::enable-tracing [[enable-tracing-interceptor]]
    * ::query-executor [[query-executor-handler]]

  `compiled-schema` may be the actual compiled schema, or a no-arguments function that returns the compiled schema.

  `app-context` is the application context that will be passed into all resolvers
  (the [[inject-app-context-interceptor]] adds a :request key to this map).

  The options map may contain key :parsed-query-cache, which will be used by the ::query-parser interceptor."
  ([compiled-schema]
   (default-interceptors compiled-schema nil))
  ([compiled-schema app-context]
   (default-interceptors compiled-schema app-context nil))
  ([compiled-schema app-context options]
   [initialize-tracing-interceptor
    json-response-interceptor
    error-response-interceptor
    body-data-interceptor
    graphql-data-interceptor
    status-conversion-interceptor
    missing-query-interceptor
    (query-parser-interceptor compiled-schema (:parsed-query-cache options))
    disallow-subscriptions-interceptor
    prepare-query-interceptor
    (inject-app-context-interceptor app-context)
    enable-tracing-interceptor
    query-executor-handler]))

(defn subscription-routes
  [compiled-schema ])
