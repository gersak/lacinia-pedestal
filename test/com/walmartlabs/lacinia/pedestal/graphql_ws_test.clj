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

(ns com.walmartlabs.lacinia.pedestal.graphql-ws-test
  "Tests for graphql-transport-ws protocol support and the :context-initializer hook in
  subscriptions2 (the pedestal3 connector path).

  Kept in its own namespace so the upstream pedestal3-test fixture and tests stay untouched:
  this namespace stands up its own subscription server (on a separate port) wired with a
  :context-initializer, and opens raw WebSocket connections so it can control the negotiated
  subprotocol and the upgrade-request headers."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.walmartlabs.lacinia.pedestal.subscriptions2 :as subscriptions2]
            [com.walmartlabs.lacinia.test-utils :as tu
             :refer [*ping-context *echo-context *session*
                     send-data send-init <message!! *subscriber-id]]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.http.jetty :as jetty]
            [charred.api :as json]
            [hato.websocket :as ws]
            [clojure.core.async :refer [chan put!]]
            [io.pedestal.log :as log]
            [com.walmartlabs.test-reporting :refer [reporting]]
            [io.pedestal.connector :as conn]))

;; Own port, so this server coexists with the upstream pedestal3-test server (8888).
(def port 8889)

(def ws-url (str "ws://localhost:" port "/ws"))

;; ---------------------------------------------------------------------------
;; User-context wiring for the subscription endpoint.
;;
;; This demonstrates the intended use of :context-initializer: authenticate the
;; WebSocket upgrade once, stash the resulting principal on the per-connection
;; context, and -- via a small user-land interceptor -- make it visible to every
;; query and subscription resolver on that connection. The library stays free of
;; any "user context" opinion; the bridge lives entirely in application code.

(def ^:private auth-header "authorization")

(defn ^:private authenticate
  "Stand-in authentication: resolves the upgrade request to a principal map, or nil when the
  request carries no recognized credentials (an anonymous connection)."
  [request]
  (case (get-in request [:headers auth-header])
    "token-alice" {:user "alice" :roles #{:admin}}
    "token-bob"   {:user "bob"   :roles #{:viewer}}
    nil))

(def ^:private principal-key ::principal)

(defn ^:private inject-app-context+principal-interceptor
  "Like the library's app-context injector, but also merges the connection-scoped principal
  (placed on the context by the :context-initializer) into the resolver app-context. This is
  the user-land bridge that carries the authenticated user into every resolver. A nil principal
  (anonymous connection) merges to nothing."
  [app-context]
  (interceptor
    {:name  ::inject-app-context+principal
     :enter (fn [context]
              (assoc-in context [:request :lacinia-app-context]
                        (-> (or app-context {})
                            (assoc :request (:request context))
                            (merge (get context principal-key)))))}))

(defn ^:private user-context-subscription-interceptor
  "Builds the subscription interceptor used by the test server: a :context-initializer that
  authenticates the upgrade request, plus a custom interceptor chain (identical to the library
  default except that it swaps in [[inject-app-context+principal-interceptor]])."
  [schema]
  (subscriptions2/subscription-interceptor
    schema
    {:keep-alive-ms       200
     :context-initializer (fn [context request]
                            (assoc context principal-key (authenticate request)))
     :subscription-interceptors
     [subscriptions2/exception-handler-interceptor
      subscriptions2/send-operation-response-interceptor
      (subscriptions2/query-parser-interceptor schema)
      (inject-app-context+principal-interceptor nil)
      subscriptions2/execute-operation-interceptor]}))

(defn server-fixture
  [f]
  (let [schema              (tu/compile-schema)
        subscription-routes (table/table-routes
                              [["/ws" :get (user-context-subscription-interceptor schema)
                                :route-name ::subscriptions]])
        connector           (-> (conn/default-connector-map port)
                                (conn/with-routes subscription-routes)
                                (jetty/create-connector nil)
                                (conn/start!))]
    (try
      (f)
      (finally
        (conn/stop! connector)))))

(use-fixtures :once server-fixture)

;; ---------------------------------------------------------------------------
;; Raw WebSocket helpers.
;;
;; These open their own connections so the tests can control the negotiated subprotocol
;; and the upgrade-request headers, then rebind the test-utils dynamic vars so the existing
;; send-*/<message!! helpers can be reused.

(defn open-connection
  "Opens a raw WebSocket connection to the subscription endpoint and returns a map of
  {:session, :messages-ch}. `opts` may include :subprotocols and :headers."
  [{:keys [subprotocols headers]
    :or   {subprotocols ["graphql-ws"]}}]
  (let [messages-ch (chan 10)
        session     @(ws/websocket
                       ws-url
                       (cond-> {:subprotocols subprotocols
                                :on-message   (fn [_ msg _last?]
                                                (put! messages-ch
                                                      (json/read-json (str msg) :key-fn keyword)))
                                :on-error     (fn [_ error]
                                                (log/error :reason ::ws-error :exception error))}
                         headers (assoc :headers headers)))]
    {:session session :messages-ch messages-ch}))

(defmacro with-connection
  "Opens a connection with `opts`, binds tu/*session* and tu/*messages-ch* to it so the
  test-utils helpers operate on it, and closes it afterwards."
  [opts & body]
  `(let [conn# (open-connection ~opts)]
     (binding [*session*        (:session conn#)
               tu/*messages-ch* (:messages-ch conn#)]
       (try
         ~@body
         (finally
           (ws/close! (:session conn#)))))))

(defn <non-ka!!
  "Reads the next message, skipping over server keep-alive (:ka) messages."
  ([] (<non-ka!! 250))
  ([timeout-ms]
   (loop []
     (let [message (<message!! timeout-ms)]
       (if (= message {:type "ka"})
         (recur)
         message)))))

;; ---------------------------------------------------------------------------
;; graphql-transport-ws protocol tests.

(deftest transport-ws-ping-pong
  ;; A graphql-transport-ws client may send "ping" at any time and must receive "pong".
  (with-connection {:subprotocols ["graphql-transport-ws"]}
    (send-init)
    (is (= {:type "connection_ack"} (<non-ka!!)))

    (send-data {:type :ping})
    (is (= {:type "pong"} (<non-ka!!)))))

(deftest transport-ws-subscribe-and-complete
  ;; Over graphql-transport-ws, the client uses "subscribe"/"complete" and the server
  ;; replies with "next" messages (not the legacy "data").
  (with-connection {:subprotocols ["graphql-transport-ws"]}
    (send-init)
    (is (= {:type "connection_ack"} (<non-ka!!)))

    (let [id (swap! *subscriber-id inc)]
      (send-data {:id      id
                  :type    :subscribe
                  :payload {:query "subscription { ping(message: \"modern\", count: 2 ) { message }}"}})

      (is (= {:id      id
              :payload {:data {:ping {:message "modern #1"}}}
              :type    "next"}
             (<non-ka!!)))

      (is (= {:id      id
              :payload {:data {:ping {:message "modern #2"}}}
              :type    "next"}
             (<non-ka!!)))

      (is (= {:id   id
              :type "complete"}
             (<non-ka!!))))))

(deftest transport-ws-operation-uses-next
  ;; A plain query over graphql-transport-ws is delivered as a "next" message followed by "complete".
  (with-connection {:subprotocols ["graphql-transport-ws"]}
    (send-init)
    (is (= {:type "connection_ack"} (<non-ka!!)))

    (let [id (swap! *subscriber-id inc)]
      (send-data {:id      id
                  :type    :subscribe
                  :payload {:query "{ echo(value: \"modern\") { value }}"}})

      (is (= {:id      id
              :payload {:data {:echo {:value "modern"}}}
              :type    "next"}
             (<non-ka!!)))

      (is (= {:id   id
              :type "complete"}
             (<non-ka!!))))))

;; ---------------------------------------------------------------------------
;; User-context tests.
;;
;; These exercise the intended use of :context-initializer (see the wiring above):
;; the connection authenticates from the upgrade request, and the authenticated
;; principal becomes visible to every resolver on the connection. The test resolvers
;; (resolve-echo / stream-ping) capture their app-context into *echo-context /
;; *ping-context, which is what we assert against.

(deftest subscription-resolver-sees-authenticated-principal
  ;; The principal established at connection open is visible to the subscription streamer.
  (with-connection {:headers {auth-header "token-alice"}}
    (send-init)
    (is (= {:type "connection_ack"} (<non-ka!!)))

    (let [id (swap! *subscriber-id inc)]
      (send-data {:id      id
                  :type    :start
                  :payload {:query "subscription { ping(message: \"hi\", count: 1 ) { message }}"}})
      (<non-ka!!)
      (reporting {:context @*ping-context}
        (is (= {:user "alice" :roles #{:admin}}
               (select-keys @*ping-context [:user :roles]))
            "The streamer's app-context carries the authenticated principal.")))))

(deftest operation-resolver-sees-authenticated-principal
  ;; The same principal is visible to plain query/mutation operations on the connection.
  (with-connection {:headers {auth-header "token-alice"}}
    (send-init)
    (is (= {:type "connection_ack"} (<non-ka!!)))

    (let [id (swap! *subscriber-id inc)]
      (send-data {:id      id
                  :type    :start
                  :payload {:query "{ echo(value: \"ws\") { value }}"}})
      (<non-ka!!)
      (reporting {:context @*echo-context}
        (is (= {:user "alice" :roles #{:admin}}
               (select-keys @*echo-context [:user :roles]))
            "The resolver's app-context carries the authenticated principal.")))))

(deftest authenticated-principal-survives-multiple-operations
  ;; The per-message app-context injection must not drop the connection principal; it must be
  ;; present on every operation, not just the first.
  (with-connection {:headers {auth-header "token-bob"}}
    (send-init)
    (is (= {:type "connection_ack"} (<non-ka!!)))

    (dotimes [_ 2]
      (reset! *echo-context nil)
      (let [id (swap! *subscriber-id inc)]
        (send-data {:id      id
                    :type    :start
                    :payload {:query "{ echo(value: \"ws\") { value }}"}})
        (<non-ka!!)                                ; data/next
        (<non-ka!!)                                ; complete
        (is (= "bob" (:user @*echo-context)))))))

(deftest anonymous-connection-has-no-principal
  ;; Without credentials, authenticate returns nil and nothing is merged into the app-context.
  (with-connection {}
    (send-init)
    (is (= {:type "connection_ack"} (<non-ka!!)))

    (let [id (swap! *subscriber-id inc)]
      (send-data {:id      id
                  :type    :start
                  :payload {:query "{ echo(value: \"ws\") { value }}"}})
      (<non-ka!!)
      (reporting {:context @*echo-context}
        (is (nil? (:user @*echo-context)))
        (is (nil? (:roles @*echo-context)))))))
