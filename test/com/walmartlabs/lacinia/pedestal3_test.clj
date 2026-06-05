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

(ns com.walmartlabs.lacinia.pedestal3-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.walmartlabs.lacinia.pedestal3 :as p3]
            [com.walmartlabs.lacinia.pedestal.subscriptions2 :as subscriptions2]
            [com.walmartlabs.lacinia.parser :refer [parse-query]]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.test-utils :as tu
             :refer [*ping-subscribes *ping-cleanups *ping-context *echo-context
                     *session* send-data send-init <message!! expect-message
                     *subscriber-id]]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.http.jetty :as jetty]
            [charred.api :as json]
            [clj-http.client :as client]
            [hato.websocket :as ws]
            [clojure.string :as str]
            [com.walmartlabs.test-reporting :refer [reporting]]
            [io.pedestal.connector :as conn]
            [com.walmartlabs.lacinia.pedestal.cache :as cache]))

(defn prune
  [response]
  (select-keys response [:status :body]))

(defn server-fixture
  [f]
  (reset! *ping-subscribes 0)
  (reset! *ping-cleanups 0)
  (let [schema            (tu/compile-schema)
        interceptors      (p3/default-interceptors schema nil {:parsed-query-cache (cache/parsed-query-cache 20)})
        subscription-interceptor (subscriptions2/subscription-interceptor schema {:keep-alive-ms 200})
        subscription-routes (table/table-routes
                              [["/ws" :get subscription-interceptor
                                :route-name ::subscriptions]])
        connector         (-> (conn/default-connector-map 8888)
                              (conn/with-routes #{["/api" :post interceptors
                                                   :route-name ::api]}
                                                subscription-routes)
                              (jetty/create-connector nil)
                              (conn/start!))]
    (try
      (f)
      (finally
        (conn/stop! connector)))))

(use-fixtures :once server-fixture)
(use-fixtures :each (tu/subscriptions-fixture "ws://localhost:8888/ws"))

(defn send-request
  "Sends a GraphQL request to the server and returns the response."
  ([query]
   (send-request query nil))
  ([query options]
   (let [{:keys [vars headers op]} options
         body (cond-> {:query query}
                op (assoc :operationName op)
                vars (assoc :variables vars))]
     (-> {:method           :post
          :url              "http://localhost:8888/api"
          :headers          (merge {"Content-Type" "application/json"} headers)
          :throw-exceptions false
          :body             (json/write-json-str body)}
         client/request
         (update :body #(try
                          (json/read-json % :key-fn keyword)
                          (catch Exception _
                            %)))))))

(deftest basic-request
  (let [response (send-request "{ echo(value: \"hello\") { value method }}")]
    (reporting response
               (is (= {:status 200
                       :body   {:data {:echo {:method "post"
                                              :value  "hello"}}}}
                      (prune response)))
               (is (= {:data {:echo {:method "post"
                                     :value  "hello"}}}
                      (:body response))))))

(deftest query-is-cached
  (let [*count           (atom 0)
        parse-query-impl parse-query
        parse-query-spy  (fn [schema query-document operation-name timing-start]
                           (swap! *count inc)
                           (parse-query-impl schema query-document operation-name timing-start))]
    (with-redefs [parse-query parse-query-spy]
      (let [q         "
      query Short ($value: String!) {
        short: echo(value: $value) { value }
      }

      query Long ($value: String!) {
        long: echo(value: $value) { value method }
      }"
            response1 (send-request q {:vars {:value "first"} :op "Short"})
            _         (is (= 1 @*count))
            response2 (send-request q {:vars {:value "second"} :op "Short"})
            ;; Same query and op: served from cache, parse not called
            _         (is (= 1 @*count))
            response3 (send-request q {:vars {:value "third"} :op "Long"})
            ;; Same query but different op means a new parse takes place.
            _         (is (= 2 @*count))]
        (reporting [response1 response2 response3]
                   (is (= [{:short {:value "first"}}
                           {:short {:value "second"}}
                           {:long {:value  "third"
                                   :method "post"}}]
                          (map #(get-in % [:body :data])
                               [response1 response2 response3]))))))))

(deftest missing-query
  (let [response (send-request nil)]
    (reporting response
               (is (= {:body   "JSON 'query' key is missing or blank"
                       :status 400}
                      (prune response))))))

(deftest must-be-json
  (let [response (client/post "http://localhost:8888/api"
                              {:headers          {"Content-Type" "text/plain"}
                               :body             "does not matter"
                               :throw-exceptions false})]
    (reporting response
               (is (= {:body   "Must be application/json"
                       :status 400}
                      (prune response))))))

(deftest can-return-failure-response
  (let [response (send-request "{ fail }")]
    (is (= {:status 500
            :body   {:errors [{:extensions {:arguments  nil
                                            :field-name "Query/fail"
                                            :location   {:column 3
                                                         :line   1}
                                            :path       ["fail"]}
                               :message    "Exception in resolver for `Query/fail': resolver exception"}]}}
           (select-keys response [:status :body])))))

(deftest subscriptions-ws-request
  (send-init)
  (expect-message {:type "connection_ack"}))

(deftest ordinary-operation
  (send-init)
  (expect-message {:type "connection_ack"})

  (let [id (swap! *subscriber-id inc)]
    (send-data {:id      id
                :type    :start
                :payload {:query "{ echo(value: \"ws\") { value }}"}})
    (expect-message {:id      id
                     :payload {:data {:echo {:value "ws"}}}
                     :type    "data"})
    (expect-message {:id   id
                     :type "complete"})))

(deftest operation-with-resolved-value-and-errors
  (send-init)
  (expect-message {:type "connection_ack"})

  (let [id (swap! *subscriber-id inc)]
    (send-data {:id      id
                :type    :start
                :payload {:query "subscription { ping(message: \"bad arg\", count: 0) { message }}"}})
    (expect-message {:id      id
                     :payload {:data   {:ping nil}
                               :errors [{:extensions {:arguments {:count   0
                                                                  :message "bad arg"}}
                                         :locations  [{:column 16
                                                       :line   1}]
                                         :message    "count must be at least 1"
                                         :path       ["ping"]}]}
                     :type    "data"})
    (expect-message {:id   id
                     :type "complete"})))

(deftest short-subscription
  (send-init)
  (expect-message {:type "connection_ack"})

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [id (swap! *subscriber-id inc)]
    (send-data {:id      id
                :type    :start
                :payload {:query "subscription { ping(message: \"short\", count: 2 ) { message }}"}})

    (expect-message {:id      id
                     :payload {:data {:ping {:message "short #1"}}}
                     :type    "data"})

    (is (> @*ping-subscribes @*ping-cleanups)
        "A subscribe is active, but has not been cleaned up.")

    (expect-message {:id      id
                     :payload {:data {:ping {:message "short #2"}}}
                     :type    "data"})

    (expect-message {:id   id
                     :type "complete"})

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscription has been cleaned up.")))

(deftest client-query-validation-error
  (send-init)
  (expect-message {:type "connection_ack"})

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [id (swap! *subscriber-id inc)]
    (send-data {:id      id
                :type    :start
                :payload ;; Note: missing selections inside ping field
                {:query "subscription { ping(message: \"short\", count: 2 ) }"}})

    (expect-message {:id      id
                     :payload {:message   "Failed to parse GraphQL query. Field `subscription/ping' must have at least one selection."
                               :locations [{:column 16
                                            :line   1}]}
                     :type    "error"})

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscription has been cleaned up.")))

(deftest client-stop
  (send-init)
  (expect-message {:type "connection_ack"})

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [id (swap! *subscriber-id inc)]
    (send-data {:id      id
                :type    :start
                :payload {:query "subscription { ping(message: \"stop\", count: 20 ) { message }}"}})

    (expect-message {:id      id
                     :payload {:data {:ping {:message "stop #1"}}}
                     :type    "data"})

    (is (> @*ping-subscribes @*ping-cleanups)
        "A subscribe is active, but has not been cleaned up.")

    (expect-message {:id      id
                     :payload {:data {:ping {:message "stop #2"}}}
                     :type    "data"})

    (send-data {:id id :type :stop})

    (expect-message ::tu/timed-out)

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscription has been cleaned up.")))

(deftest client-parallel
  (send-init)
  (expect-message {:type "connection_ack"})

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [init-subs @*ping-subscribes
        left-id   (swap! *subscriber-id inc)
        right-id  (swap! *subscriber-id inc)]

    (send-data {:id      left-id
                :type    :start
                :payload {:query "subscription { ping(message: \"left\", count: 2 ) { message }}"}})

    (expect-message {:id      left-id
                     :payload {:data {:ping {:message "left #1"}}}
                     :type    "data"})

    (Thread/sleep 20)

    (send-data {:id      right-id
                :type    :start
                :payload {:query "subscription { ping(message: \"right\", count: 2 ) { message }}"}})

    (expect-message {:id      right-id
                     :payload {:data {:ping {:message "right #1"}}}
                     :type    "data"})

    (is (= 2 (- @*ping-subscribes init-subs)))

    (expect-message {:id      left-id
                     :payload {:data {:ping {:message "left #2"}}}
                     :type    "data"})

    (expect-message {:id      right-id
                     :payload {:data {:ping {:message "right #2"}}}
                     :type    "data"})

    (expect-message {:id   left-id
                     :type "complete"})

    (expect-message {:id   right-id
                     :type "complete"})

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscriptions have been cleaned up.")))

(deftest client-terminates-connection
  (send-init)
  (expect-message {:type "connection_ack"})

  (let [left-id  (swap! *subscriber-id inc)
        right-id (swap! *subscriber-id inc)]

    (send-data {:id      left-id
                :type    :start
                :payload {:query "subscription { ping(message: \"left\", count: 2 ) { message }}"}})

    (expect-message {:id      left-id
                     :payload {:data {:ping {:message "left #1"}}}
                     :type    "data"})

    (send-data {:id      right-id
                :type    :start
                :payload {:query "subscription { ping(message: \"right\", count: 2 ) { message }}"}})

    (expect-message {:id      right-id
                     :payload {:data {:ping {:message "right #1"}}}
                     :type    "data"})

    (send-data {:type :connection_terminate})

    (expect-message ::tu/timed-out)

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscriptions have been cleaned up.")))

(deftest client-closes-connection
  (send-init)
  (expect-message {:type "connection_ack"})

  (let [left-id  (swap! *subscriber-id inc)
        right-id (swap! *subscriber-id inc)]

    (send-data {:id      left-id
                :type    :start
                :payload {:query "subscription { ping(message: \"left\", count: 2 ) { message }}"}})

    (expect-message {:id      left-id
                     :payload {:data {:ping {:message "left #1"}}}
                     :type    "data"})

    (send-data {:id      right-id
                :type    :start
                :payload {:query "subscription { ping(message: \"right\", count: 2 ) { message }}"}})

    (expect-message {:id      right-id
                     :payload {:data {:ping {:message "right #1"}}}
                     :type    "data"})

    (ws/close! *session*)

    (expect-message ::tu/timed-out)

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscriptions have been cleaned up.")))

(deftest client-invalid-message
  (send-init)
  (expect-message {:type "connection_ack"})

  (ws/send! *session* "~~~")

  (let [message (<message!!)]
    (is (= "connection_error" (:type message)))
    (is (str/includes? (-> message :payload :message)
                       "Unexpected character"))))

(deftest client-query-parse-error
  (send-init)
  (expect-message {:type "connection_ack"})

  (let [id (swap! *subscriber-id inc)]
    (send-data {:id      id
                :type    :start
                :payload {:query "~~~"}})

    (expect-message {:id      id
                     :payload {:message   "Failed to parse GraphQL query. Token recognition error at: '~'; Mismatched input '<eof>' expecting {'query', 'mutation', 'subscription', '{', 'fragment'}."
                               :locations [{:column nil
                                            :line   1}]}
                     :type    "error"})))

(deftest client-keep-alive
  (send-init)
  (expect-message {:type "connection_ack"})

  (dotimes [_ 2]
    (is (= {:type "ka"}
           (<message!! 250)))))

(deftest client-duplicates-subscription
  (send-init)
  (expect-message {:type "connection_ack"})

  (is (= @*ping-subscribes @*ping-cleanups)
      "Any prior subscribes have been cleaned up.")

  (let [init-subs @*ping-subscribes
        sub-id    (swap! *subscriber-id inc)]

    (send-data {:id      sub-id
                :type    :start
                :payload {:query "subscription { ping(message: \"original\", count: 2 ) { message }}"}})

    (expect-message {:id      sub-id
                     :payload {:data {:ping {:message "original #1"}}}
                     :type    "data"})

    (Thread/sleep 20)

    (send-data {:id      sub-id
                :type    :start
                :payload {:query "subscription { ping(message: \"duplicate\", count: 2 ) { message }}"}})

    (is (= 1 (- @*ping-subscribes init-subs)))

    (expect-message {:id      sub-id
                     :payload {:data {:ping {:message "original #2"}}}
                     :type    "data"})

    (expect-message {:id   sub-id
                     :type "complete"})

    (is (= @*ping-subscribes @*ping-cleanups)
        "The completed subscriptions have been cleaned up.")))

(deftest connection-params
  (let [connection-params {:authentication "token"}
        id                (swap! *subscriber-id inc)
        query             {:id      id
                           :type    :start
                           :payload {:query "{ echo(value: \"ws\") { value }}"}}
        response          {:id      id
                           :payload {:data {:echo {:value "ws"}}}
                           :type    "data"}
        complete          {:id   id
                           :type "complete"}]
    (send-init connection-params)
    (expect-message {:type "connection_ack"})

    (send-data query)
    (expect-message response)
    (expect-message complete)
    (reporting {:context @*echo-context}
      (is (= connection-params (::lacinia/connection-params @*echo-context))))

    (send-data query)
    (expect-message response)
    (expect-message complete)
    (reporting {:context @*echo-context}
      (is (= connection-params (::lacinia/connection-params @*echo-context))))

    (send-data {:id      (swap! *subscriber-id inc)
                :type    :start
                :payload {:query "subscription { ping(message: \"stop\", count: 1 ) { message }}"}})
    (<message!! 250)
    (reporting {:context @*ping-context}
      (is (= connection-params (::lacinia/connection-params @*ping-context))))))


(deftest can-provide-tracing-information
  (let [query    "{ echo(value: \"hello\") { value method }}"
        response (send-request query {:headers {"lacinia-tracing" "true"}})]
    (reporting [response]
               (is (= 1 (get-in response [:body :extensions :tracing :version]))))))
