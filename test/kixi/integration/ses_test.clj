(ns kixi.integration.ses-test
  (:require [amazonica.aws.dynamodbv2 :as ddb]
            [clj-http.client :as client]
            [clojure
             [test :refer :all]]
            [clojure.spec.test :as stest]
            [clojure.core.async :as async]
            [environ.core :refer [env]]
            [kixi.comms.components.kinesis :as kinesis]
            [kixi.comms :as c]
            [user :as user]))

(def wait-tries (Integer/parseInt (env :wait-tries "80")))
(def wait-per-try (Integer/parseInt (env :wait-per-try "1000")))
(def run-against-staging (Boolean/parseBoolean (env :run-against-staging "false")))
(def teardown-kinesis (Boolean/parseBoolean (env :teardown-kinesis "false")))
(def teardown-dynamo (Boolean/parseBoolean (env :teardown-dynamo "false")))
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

(defn tear-down-kinesis
  [{:keys [endpoint dynamodb-endpoint streams
           profile app]}]
  (when teardown-dynamo
    (delete-tables dynamodb-endpoint [(kinesis/event-worker-app-name app profile)
                                      (kinesis/command-worker-app-name app profile)]))
  (when teardown-kinesis
    (kinesis/delete-streams! endpoint (vals streams))))

(defn cycle-system-fixture
  [all-tests]
  (if run-against-staging
    (user/start {} [:communications])
    (user/start))
  (try (stest/instrument)
       (all-tests)
       (finally
         (let [kinesis-conf (select-keys (:communications @user/system)
                                         [:endpoint :dynamodb-endpoint :streams
                                          :profile :app])]
           (user/stop)
           (tear-down-kinesis kinesis-conf)))))

(def comms (atom nil))
(def event-channel (atom nil))

(defn attach-event-handler!
  [group-id event handler]
  (c/attach-event-handler!
   @comms
   group-id
   event
   "1.0.0"
   handler))

(defn detach-handler
  [handler]
  (c/detach-handler!
   @comms
   handler))

(defn sink-to
  [a]
  #(do (async/>!! @a %)
       nil))

(defn extract-comms
  [all-tests]
  (reset! comms (:communications @user/system))
  (let [_ (reset! event-channel (async/chan 100))
        handler (c/attach-event-with-key-handler!
                 @comms
                 :mailer-integration-tests
                 :kixi.comms.event/id
                 (sink-to event-channel))]
    (try
      (all-tests)
      (finally
        (detach-handler handler)
        (async/close! @event-channel)
        (reset! event-channel nil))))
  (reset! comms nil))


(defn event-for
  [uid event]
  (= uid
     (or (get-in event [:kixi.comms.event/payload :kixi/user :kixi.user/id]))))

(defn wait-for-events
  [uid & event-types]
  (first
   (async/alts!!
    (mapv (fn [c]
            (async/go-loop
                [event (async/<! c)]
              (if (and (event-for uid event)
                       ((set event-types)
                        (:kixi.comms.event/key event)))
                event
                (when event
                  (recur (async/<! c))))))
          [@event-channel
           (async/timeout (* wait-tries
                             wait-per-try))]))))

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
    mail)))

(defn send-mail
  ([uid mail]
   (send-mail uid uid mail))
  ([uid ugroup mail]
   (send-mail-cmd uid ugroup mail)
   (wait-for-events uid :kixi.mailer/mail-rejected :kixi.mailer/mail-accepted)))


(use-fixtures :once cycle-system-fixture extract-comms)

(deftest healthcheck-check
  (let [hc-resp (client/get (str "http://" service-url "/healthcheck"))]
    (is (= (:status hc-resp)
           200))))

(def test-mail {:destination {:to-addresses ["support@mastodonc.com"]}
                :source "support@mastodonc.com"
                :message {:subject (str "kixi.mailer - " profile " - Integration Test Mail")
                          :body {:text "<<&env.default_header>>This is an email from the integration tests for kixi.mailer.<<&env.default_footer>>"}}})

(deftest send-acceptable-mail
  (let [uid (uuid)
        event (send-mail uid test-mail)]
    (is (= :kixi.mailer/mail-accepted
           (:kixi.comms.event/key event)))
    (is (= uid
           (get-in event [:kixi.comms.event/payload :kixi/user :kixi.user/id])))))

(deftest send-unacceptable-mail
  (let [uid (uuid)
        event (send-mail uid {:mail ""})]
    (is (= :kixi.mailer/mail-rejected
           (:kixi.comms.event/key event)))
    (is (= uid
           (get-in event [:kixi.comms.event/payload :kixi/user :kixi.user/id])))))
