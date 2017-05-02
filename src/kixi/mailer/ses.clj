(ns kixi.mailer.ses
  (:require [amazonica.aws.simpleemail :as email]
            [com.stuartsierra.component :as component]
            [clostache.parser :as parser]
            [clojure.spec :as s]
            [kixi.comms :as c]
            [taoensso.timbre :as timbre :refer [error]]
            [clojure.java.io :as io]))

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

(def angle-delimiters (partial str "{{=<< >>=}}"))

(def html-template-vars
  {:env {:default_header (slurp (io/resource "emails/default-html-header.html"))
         :default_footer (slurp (io/resource "emails/default-html-footer.html"))
         :base_url "http://www.witanforcities.com"}})

(def text-template-vars
  {:env {:default_header (slurp (io/resource "emails/default-text-header.txt"))
         :default_footer (slurp (io/resource "emails/default-text-footer.txt"))
         :base_url "http://www.witanforcities.com"}})

(defn render
  [vars]
  (fn [txt]
    (parser/render txt vars)))

(defn xupdate-in
  "update if present"
  [m ks f]
  (if (get-in m ks)
    (update-in m ks f)
    m))

(defn render-templates
  [message]
  (-> message
      (xupdate-in [:body :text] (comp (render text-template-vars) angle-delimiters))
      (xupdate-in [:body :html] (comp (render html-template-vars) angle-delimiters))))

(defn send-email
  [endpoint payload]
  (try
    (email/send-email {:endpoint endpoint}
                      :destination (:destination payload)
                      :source (:source payload)
                      :message (render-templates (:message payload)))
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
