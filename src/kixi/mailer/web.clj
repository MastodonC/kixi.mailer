(ns kixi.mailer.web
  (:require [bidi
             [bidi :refer [tag]]
             [vhosts :refer [vhosts-model]]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre :refer [error info infof]]
            [yada
             [resource :as yr]
             [yada :as yada]]))

(defn healthcheck
  [ctx]
  ;Return truthy for now, but later check dependancies
  (assoc (:response ctx)
         :status 200
         :body "All is well"))

(defn routes
  "Create the URI route structure for our application."
  []
  [""
   [["/healthcheck" healthcheck]

    ;; This is a backstop. Always produce a 404 if we ge there. This
    ;; ensures we never pass nil back to Aleph.
    [true (yada/handler nil)]]])

(defrecord Web
    [port vhost listener]
    component/Lifecycle
    (start [component]
      (if listener
        component
        (let [vhosts-model (vhosts-model
                            [{:scheme :http
                              :host (str vhost ":" port)}
                             (routes)])
              listener (yada/listener vhosts-model {:port port})]
          (infof "Started web-server on port %s" port)
          (assoc component :listener listener))))
    (stop [component]
      (when-let [close (get-in component [:listener :close])]
        (infof "Stopping web-server on port %s" port)
        (close))
      (assoc component :listener nil)))
