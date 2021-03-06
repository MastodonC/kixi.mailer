(def metrics-version "2.7.0")
(def slf4j-version "1.7.21")
(defproject kixi.mailer "0.1.0-SNAPSHOT"
  :description "Command driven Simple Email Service integration"
  :url ""
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[aero "1.0.0"]
                 [aleph "0.4.2-alpha8"]
                 [bidi "2.0.12"]
                 [clj-http "3.5.0"]
                 [com.amazonaws/aws-java-sdk "1.11.53" :exclusions [joda-time]]
                 [com.taoensso/timbre "4.8.0"]
                 [de.ubercode.clostache/clostache "1.4.0"]
                 [kixi/kixi.comms "0.2.35" :upgrade :kixi]
                 [kixi/kixi.log "0.1.6" :upgrade :kixi]
                 [kixi/kixi.metrics "0.4.0" :upgrade :kixi]
                 [kixi/kixi.spec "0.1.20" :upgrade :kixi]
                 [org.clojure/clojure "1.9.0"]
                 [yada/lean "1.2.2"]]
  :repl-options {:init-ns user}
  :global-vars {*warn-on-reflection* true
                *assert* false}
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[stylefruits/gniazdo "1.0.1"]]}
             :uberjar {:aot [kixi.mailer.bootstrap]
                       :uberjar-name "kixi.mailer-standalone.jar"}})
