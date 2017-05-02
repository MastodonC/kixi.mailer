(ns kixi.mailer.ses
  (:require [amazonica.aws.simpleemail :as email]
            [com.stuartsierra.component :as component]
            [clojure.spec :as s]
            [kixi.comms :as c]
            [taoensso.timbre :as timbre :refer [error]]))

(defn invalid-rejected
  [cmd explaination]
  {:kixi.comms.event/key :kixi.mailer/mail-rejected
   :kixi.comms.event/version "1.0.0"
   :kixi.comms.event/payload {:reason :mail-invalid
                              :explain explaination
                              :kixi/user (:kixi.comms.command/user cmd)}})

(defn aws-rejected
  [cmd explaination]
  {:kixi.comms.event/key :kixi.mailer/mail-rejected
   :kixi.comms.event/version "1.0.0"
   :kixi.comms.event/payload {:reason :aws-rejected
                              :explain explaination
                              :kixi/user (:kixi.comms.command/user cmd)}})

(defn accepted
  [cmd ses-resp]
  {:kixi.comms.event/key :kixi.mailer/mail-accepted
   :kixi.comms.event/version "1.0.0"
   :kixi.comms.event/payload {:kixi/user (:kixi.comms.command/user cmd)
                              :ses-response ses-resp}})

(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")
(def email? (s/and string? #(re-matches email-regex %)))

(s/def ::to-addresses 
  (s/coll-of email? :min-count 1 :max-count 50))

(s/def ::source email?)

(s/def ::destination
  (s/keys :req-un [::to-addresses]))

(s/def ::subject string?)
(s/def ::text string?)
(s/def ::html string?)

(s/def ::body
  (s/keys :req-un [::text]
          :opt-un [::html]))

(s/def ::message
  (s/keys :req-un [::subject ::body]))

(s/def ::payload
  (s/keys :req-un [::destination
                   ::message]
          :opt-un [::source]))

(def default-payload
  {:source "support@mastodonc.com"})

(defn send-email
  [endpoint payload]
  (try
    (email/send-email {:endpoint endpoint}
                      :destination (:destination payload)
                      :source (:source payload)
                      :message (:message payload))
    (catch Exception e
      (error e "Exception sending email")
      {:error true
       :exception e})))

(defn create-mail-sender
  [endpoint]
  (fn [{:keys [kixi.comms.command/payload] :as cmd}]
    (let [payload (merge default-payload
                         payload)]
      (if (s/valid? ::payload payload)
        (let [send-resp (send-email endpoint payload)]
          (if-not (:error send-resp)
            (accepted cmd send-resp)
            (aws-rejected cmd send-resp)))
        (invalid-rejected cmd (s/explain-data ::payload payload))))))

(defrecord Mailer
    [communications send-mail-handler endpoint]
    component/Lifecycle
    (start [component]
      (merge component
             (when-not send-mail-handler
               {:send-mail-handler
                (c/attach-command-handler!
                 communications
                 :kixi.mailer/mailer
                 :kixi.mailer/send-mail
                 "1.0.0" (create-mail-sender endpoint))})))
    (stop [component]
      (-> component
          (update component :send-mail-handler
                  #(when %
                     (c/detach-handler! communications %)
                     nil)))))
