(ns kixi.integration.ses-test
  (:require [kixi.mailer.system :as sys]
            [amazonica.aws.dynamodbv2 :as ddb]
            [clj-http.client :as client]
            [clojure
             [test :refer :all]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.core.async :as async]
            [environ.core :refer [env]]
            [kixi.comms.components.kinesis :as kinesis]
            [kixi.comms.components.coreasync :as coreasync]
            [kixi.mailer.heimdall :as h]
            [kixi.comms :as c]
            [gniazdo.core :as ws]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan close! put!
                     alts! alts!! timeout]]
            [user :as user]))

(def wait-tries (Integer/parseInt (env :wait-tries "80")))
(def wait-per-try (Integer/parseInt (env :wait-per-try "1000")))
(def run-against-staging (Boolean/parseBoolean (env :run-against-staging "false")))
(def service-url (env :service-url "localhost:8080"))
(def profile (env :system-profile "local"))

(defn uuid
  []
  (str (java.util.UUID/randomUUID)))

(defn vec-if-not
  [x]
  (if (vector? x)
    x
    (vector x)))

(defn table-exists?
  [endpoint table]
  (try
    (ddb/describe-table {:endpoint endpoint} table)
    (catch Exception e false)))

(defn delete-tables
  [endpoint table-names]
  (doseq [sub-table-names (partition-all 10 table-names)]
    (doseq [table-name sub-table-names]
      (ddb/delete-table {:endpoint endpoint} :table-name table-name))
    (loop [tables sub-table-names]
      (when (not-empty tables)
        (recur (doall (filter (partial table-exists? endpoint) tables)))))))

(defn cycle-system-fixture
  [all-tests]
  (if run-against-staging
    (user/start {} [:communications])
    (user/start {:communications (coreasync/map->CoreAsync
                                  {:profile profile})} nil))
  (try (stest/instrument)
       (all-tests)
       (finally
         (user/stop))))

(def comms (atom nil))
(def event-channel (atom nil))

(defn sink-to
  [a]
  #(do (async/>!! @a %)
       nil))

(defn extract-comms
  [all-tests]
  (reset! comms (:communications @user/system))
  (let [_ (reset! event-channel (async/chan 100))
        handler-1 (c/attach-event-with-key-handler!
                   @comms
                   :mailer-integration-tests-1
                   :kixi.comms.event/id
                   (sink-to event-channel))
        handler-2 (c/attach-event-with-key-handler!
                   @comms
                   :mailer-integration-tests-2
                   :kixi.event/id
                   (sink-to event-channel))]
    (try
      (all-tests)
      (finally
        (c/detach-handler! @comms handler-1)
        (c/detach-handler! @comms handler-2)
        (async/close! @event-channel)
        (reset! event-channel nil))))
  (reset! comms nil))


(defn event-for
  [uid event]
  (or (= uid
         (get-in event [:kixi.comms.event/payload :kixi/user :kixi.user/id]))
      (= uid
         (get-in event [:kixi/user :kixi.user/id]))))

(defn wait-for-events
  [uid & event-types]
  (let [event-types (set event-types)]
    (first
     (async/alts!!
      (mapv (fn [c]
              (async/go-loop
                  [event (async/<! c)]
                (if (and (event-for uid event)
                         (or (event-types (:kixi.comms.event/key event))
                             (event-types (:kixi.event/type event))))
                  event
                  (when event
                    (recur (async/<! c))))))
            [@event-channel
             (async/timeout (* wait-tries
                               wait-per-try))])))))

(defn send-mail-cmd
  ([uid mail]
   (send-mail-cmd uid uid mail))
  ([uid ugroup mail]
   (c/send-command!
    @comms
    :kixi.mailer/send-mail
    "1.0.0"
    {:kixi.user/id uid
     :kixi.user/groups (vec-if-not ugroup)}
    mail
    {:kixi.comms.command/partition-key uid})))

(defn send-group-mail-cmd
  ([uid mail]
   (send-mail-cmd uid uid mail))
  ([uid ugroup mail]
   (c/send-valid-command!
    @comms
    (merge
     {:kixi.command/type :kixi.mailer/send-group-mail
      :kixi.command/version "1.0.0"
      :kixi/user {:kixi.user/id uid
                  :kixi.user/groups (vec-if-not ugroup)}}
     mail)
    {:partition-key uid})))

(defn send-mail
  ([uid mail]
   (send-mail uid uid mail))
  ([uid ugroup mail]
   (send-mail-cmd uid ugroup mail)
   (wait-for-events uid :kixi.mailer/mail-rejected :kixi.mailer/mail-accepted)))

(defn send-group-mail
  ([uid mail]
   (send-group-mail uid uid mail))
  ([uid ugroup mail]
   (send-group-mail-cmd uid ugroup mail)
   (wait-for-events uid :kixi.mailer/group-mail-rejected :kixi.mailer/group-mail-accepted)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn login
  [d]
  (let [uri (h/directory-url :heimdall d "create-auth-token")]
    (:body (client/post uri {:content-type :transit+json
                             :accept :transit+json
                             :throw-exceptions false
                             :as :transit+json
                             :form-params {:username "test@mastodonc.com"
                                           :password "Secret123"}}))))

(defn create-ws-connection
  [connected-fn received-fn]
  (let [url "wss://staging-api.witanforcities.com/ws"
        c (ws/client (java.net.URI. url))]
    (.setMaxTextMessageSize (.getPolicy c) 4194304)
    (.start c)
    (println "Opening websocket...")
    (ws/connect url
      :on-connect (fn [& _]
                    (println "Websocket on-connect called.")
                    (connected-fn))
      :on-receive (fn [x]
                    (println "Websocket received something!")
                    (when received-fn
                      (received-fn (h/transit-decode x))))
      :client c)))

(defn close-ws
  [c]
  (println "Closing websocket...")
  (ws/close c))

(defn send-msg-ws
  [w tkp msg]
  (ws/send-msg w (h/transit-encode {:kixi.comms.auth/token-pair tkp
                                    :kixi.comms.message/type "query"
                                    :kixi.comms.query/id (uuid)
                                    :kixi.comms.query/body msg})))

(defn wait-for-ws
  [received-ch]
  (let [connected? (atom false)
        w (create-ws-connection
           #(reset! connected? true)
           (comp (partial put! received-ch)))]
    (while (not @connected?)
      (Thread/sleep 1000))
    w))

(defn fetch-groups-from-heimdall
  []
  (let [r (chan)
        d (:directory @user/system)
        usr (:token-pair (login d))
        w (wait-for-ws r)]
    ;;
    (send-msg-ws w usr {:groups/search [[] []]})
    (let [results (map :kixi.group/id (->
                                       (<!! r)
                                       :kixi.comms.query/results
                                       (first)
                                       :groups/search
                                       :items
                                       (shuffle)))]
      (close! r)
      (close-ws w)
      results)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(use-fixtures :once cycle-system-fixture extract-comms)

(deftest healthcheck-check
  (let [hc-resp (client/get (str "http://" service-url "/healthcheck"))]
    (is (= (:status hc-resp)
           200))))

(def test-mail {:destination {:to-addresses ["developers@mastodonc.com"]}
                :source "support@mastodonc.com"
                :message {:subject (str "kixi.mailer - " profile " - Integration Test Mail")
                          :body {:text "<<&env.default_header>>This is an email from the integration tests for kixi.mailer.<<&env.default_footer>>"}}})

(defn test-group-mail
  []
  (let [g (set (take 5 (fetch-groups-from-heimdall)))]
    {:kixi.mailer/destination {:kixi.mailer.destination/to-groups g}
     :kixi.mailer/source "support@mastodonc.com"
     :kixi.mailer/message {:kixi.mailer.message/subject
                           (str "kixi.mailer - " profile " - Integration Test Mail #2")
                           :kixi.mailer.message/body
                           {:kixi.mailer.message/text
                            "<<&env.default_header>>This is an email from the integration tests for kixi.mailer.<<&env.default_footer>>"}}}))

(deftest send-acceptable-mail
  (let [uid (uuid)
        event (send-mail uid test-mail)]
    (is (= :kixi.mailer/mail-accepted
           (:kixi.comms.event/key event)) (pr-str event))
    (is (= uid
           (get-in event [:kixi.comms.event/payload :kixi/user :kixi.user/id])))))

(deftest send-unacceptable-mail
  (let [uid (uuid)
        event (send-mail uid {:mail ""})]
    (is (= :kixi.mailer/mail-rejected
           (:kixi.comms.event/key event)))
    (is (= uid
           (get-in event [:kixi.comms.event/payload :kixi/user :kixi.user/id])))))

(deftest send-acceptable-group-mail
  (let [uid (uuid)
        event (send-group-mail uid (test-group-mail))]
    (is (= :kixi.mailer/group-mail-accepted
           (:kixi.event/type event)))
    (is (= uid
           (get-in event [:kixi/user :kixi.user/id])))))

(deftest send-unacceptable-group-mail
  (let [uid (uuid)
        event (binding [c/*validate-commands* false]
                (send-group-mail uid (dissoc (test-group-mail) :kixi.mailer/destination)))]
    (is (= :kixi.mailer/group-mail-rejected
           (:kixi.event/type event)))
    (is (= uid
           (get-in event [:kixi/user :kixi.user/id])))))
