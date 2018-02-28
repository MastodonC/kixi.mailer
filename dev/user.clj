(ns user
  (:require [com.stuartsierra.component :as component]
            [kixi.mailer.system :as sys]
            [environ.core :refer [env]]))

(defonce system (atom nil))
(defonce profile (atom nil))

(defn start
  ([]
   (start {} nil))
  ([overrides component-subset]
   (let [system-profile (keyword (env :system-profile "local"))]
     (when-not @system
       (try
         (prn "Starting system" system-profile)
         (->> (sys/new-system system-profile)
              (#(merge % overrides))
              (#(if component-subset
                  (select-keys % component-subset)
                  %))
              component/start-system
              (reset! system))
         (reset! profile system-profile)
         (catch Exception e
           (reset! system (:system (ex-data e)))
           (throw e)))))))

(defn stop
  []
  (when @system
    (prn "Stopping system")
    (component/stop-system @system)
    (reset! system nil)
    (reset! profile nil)))

(defn restart
  []
  (stop)
  (start))
