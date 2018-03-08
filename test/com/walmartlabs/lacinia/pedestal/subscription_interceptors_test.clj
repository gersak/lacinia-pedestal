(ns com.walmartlabs.lacinia.pedestal.subscription-interceptors-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [clojure.core.async :refer [chan alt!! put! timeout]]
    [com.walmartlabs.lacinia.pedestal :refer [inject]]
    [com.walmartlabs.lacinia.test-utils
     :refer [test-server-fixture *ping-subscribes *ping-cleanups
             ws-uri
             subscriptions-fixture
             send-data send-init <message!! expect-message]]
    [io.pedestal.interceptor :refer [interceptor]]
    [com.walmartlabs.lacinia.pedestal.subscriptions :as s]
    [cheshire.core :as cheshire]
    [clojure.string :as str])
  (:import [org.eclipse.jetty.websocket.servlet ServletUpgradeRequest]))

(def ^:private *invoke-count (atom 0))

(def ^:private invoke-count-interceptor
  "Used to demonstrate that subscription interceptor customization works."
  (interceptor
    {:name ::invoke-count
     :enter (fn [context]
              (swap! *invoke-count inc)
              context)}))

(def ^:private *user-agent (atom nil))

(def ^:private user-agent-interceptor
  "Used to demonstrate that the init-context option works"
  (interceptor
   {:name ::user-agent
    :enter (fn [context]
             (reset! *user-agent (get-in context [:request :user-agent]))
             context)}))

(defn ^:private options-builder
  [schema]
  {:subscription-interceptors
   ;; Add ::invoke-count, and ensure it executes before ::execute-operation.
   (-> (s/default-subscription-interceptors schema nil)
       (inject invoke-count-interceptor :before ::s/execute-operation)
       (inject user-agent-interceptor :before ::s/execute-operation))

   :init-context
   (fn [ctx ^ServletUpgradeRequest req resp]
     (reset! *invoke-count 0)
     (reset! *user-agent nil)
     (assoc-in ctx [:request :user-agent] (.getHeader (.getHttpServletRequest req) "User-Agent")))})

(use-fixtures :once (test-server-fixture {:subscriptions true
                                          :keep-alive-ms 200}
                                         options-builder))

(use-fixtures :each subscriptions-fixture)

(deftest added-interceptor-is-invoked
  (send-init)
  (expect-message {:type "connection_ack"})

  (send-data {:id 987
              :type :start
              :payload
              {:query "subscription { ping(message: \"short\", count: 2 ) { message }}"}})

  (expect-message {:id 987
                   :payload {:data {:ping {:message "short #1"}}}
                   :type "data"})

  (is (= 1 @*invoke-count)
      "The added interceptor has been executed."))

(deftest context-is-created-by-init-context
  (send-init)
  (expect-message {:type "connection_ack"})

  (send-data {:id 987
              :type :start
              :payload
              {:query "subscription { ping(message: \"short\", count: 2 ) { message }}"}})

  (expect-message {:id 987
                   :payload {:data {:ping {:message "short #1"}}}
                   :type "data"})

  (is (not-empty @*user-agent)
      "The user agent was set by the init-context function."))
