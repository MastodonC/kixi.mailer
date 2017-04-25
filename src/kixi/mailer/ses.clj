(ns kixi.mailer.ses
  (:require [com.stuartsierra.component :as component]
            [clojure.spec :as s]
            [kixi.comms :as c]))

(defn rejected
  [cmd explaination]
  {:kixi.comms.event/key :kixi.mailer/mail-rejected
   :kixi.comms.event/version "1.0.0"
   :kixi.comms.event/payload {:reason :mail-invalid
                              :explain explaination
                              :kixi/user (:kixi.comms.command/user cmd)}})

(defn accepted
  [cmd]
  {:kixi.comms.event/key :kixi.mailer/mail-accepted
   :kixi.comms.event/version "1.0.0"
   :kixi.comms.event/payload {:kixi/user (:kixi.comms.command/user cmd)}})

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
  {:source "donotreply@mastodonc.com"})

(defn create-mail-sender
  []
  (fn [{:keys [kixi.comms.command/payload] :as cmd}]
    (let [payload (merge default-payload
                         payload)]
      (if (s/valid? ::payload payload)
        (accepted cmd)
        (rejected cmd (s/explain-data ::payload payload))))))

(defrecord Mailer
    [communications sent-mail-handler]
  component/Lifecycle
  (start [component]
    (merge component
           (when-not sent-mail-handler
             {:sent-mail-handler
              (c/attach-command-handler!
               communications
               :kixi.mailer/mailer
               :kixi.mailer/sent-mail
               "1.0.0" (create-mail-sender))})))
  (stop [component]
    (-> component
        (update component :sent-mail-handler
                #(when %
                   (c/detach-handler! communications %)
                   nil)))))
