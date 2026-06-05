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
;
(ns com.walmartlabs.lacinia.pedestal.internal2
  "More internal utiltiies.  Isolating use of io.pedestal.http namespace."
  (:require [com.walmartlabs.lacinia.pedestal.subscriptions :as subscriptions]
            [io.pedestal.http :as http]))

(defn add-subscriptions-support
  [service-map compiled-schema subscriptions-path subscription-options]
  (assoc-in service-map [::http/websockets subscriptions-path]
            (subscriptions/subscription-websocket-endpoint compiled-schema subscription-options)))
