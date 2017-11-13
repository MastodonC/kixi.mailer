(ns kixi.mailer.ses
  (:require [amazonica.aws.simpleemail :as email]
            [com.stuartsierra.component :as component]
            [clostache.parser :as parser]
            [clojure.spec.alpha :as s]
            [kixi.comms :as c]
            [kixi.log.timbre.appenders.logstash :as l]
            [taoensso.timbre :as timbre :refer [error]]
            [clojure.java.io :as io]))

(defn invalid-rejected
  [cmd explaination]
  {:kixi.comms.event/key :kixi.mailer/mail-rejected
   :kixi.comms.event/version "1.0.0"
   :kixi.comms.event/payload {:reason :mail-invalid
                              :explain explaination
                              :kixi/user (:kixi.comms.command/user cmd)}
   :kixi.comms.event/partition-key (get-in cmd [:kixi.comms.command/user :kixi.user/id])})

(defn aws-rejected
  [cmd explaination]
  {:kixi.comms.event/key :kixi.mailer/mail-rejected
   :kixi.comms.event/version "1.0.0"
   :kixi.comms.event/payload {:reason :aws-rejected
                              :explain explaination
                              :kixi/user (:kixi.comms.command/user cmd)}
   :kixi.comms.event/partition-key (get-in cmd [:kixi.comms.command/user :kixi.user/id])})

(defn accepted
  [cmd ses-resp]
  {:kixi.comms.event/key :kixi.mailer/mail-accepted
   :kixi.comms.event/version "1.0.0"
   :kixi.comms.event/payload {:kixi/user (:kixi.comms.command/user cmd)
                              :ses-response ses-resp}
   :kixi.comms.event/partition-key (get-in cmd [:kixi.comms.command/user :kixi.user/id])})

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

(s/def ::endpoint string?)
(s/def ::default_header string?)
(s/def ::default_footer string?)
(s/def ::base_url (s/and string?
                         #(not (clojure.string/ends-with? % "/"))))

(s/def ::env
  (s/keys :un-req [::default_header ::default_footer ::base_url]))

(s/def ::render-var
  (s/keys :un-req [::env]))

(s/def ::text-vars ::render-var)
(s/def ::html-vars ::render-var)

(s/def ::render-vars
  (s/keys :req [::text-vars ::html-vars]))

(def default-payload
  {:source "support@mastodonc.com"})

(def angle-delimiters (partial str "{{=<< >>=}}"))

(def base-html-template-vars
  {:env {:default_header (slurp (io/resource "emails/default-html-header.html"))
         :default_footer (slurp (io/resource "emails/default-html-footer.html"))}})

(def base-text-template-vars
  {:env {:default_header (slurp (io/resource "emails/default-text-header.txt"))
         :default_footer (slurp (io/resource "emails/default-text-footer.txt"))}})

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
  [render-vars message]
  (-> message
      (xupdate-in [:body :text] (comp (render (::text-vars render-vars)) angle-delimiters))
      (xupdate-in [:body :html] (comp (render (::html-vars render-vars)) angle-delimiters))))

(s/fdef send-email
        :args (s/cat :endpoint ::endpoint
                     :render-vars ::render-vars
                     :payload ::payload))

(defn send-email
  [endpoint render-vars payload]
  (try
    (email/send-email {:endpoint endpoint}
                      :destination (:destination payload)
                      :source (:source payload)
                      :message (render-templates render-vars (:message payload)))
    (catch Exception e
      (error e "Exception sending email")
      {:error true
       :exception (l/exception->map e)})))

(defn merge-in-render-vars
  [base-url]
  {::text-vars (merge-with merge base-text-template-vars
                     {:env {:base_url base-url}})
   ::html-vars (merge-with merge base-html-template-vars
                     {:env {:base_url base-url}})})

(defn validate-configuration
  [endpoint render-vars]
  (when-not (s/valid? ::endpoint endpoint)
    (throw (ex-info "Config invalid, endpoint" (s/explain-data ::endpoint endpoint))))
  (when-not (s/valid? ::render-vars render-vars)
    (throw (ex-info "Config invalid" (s/explain-data ::render-vars render-vars)))))

(defn create-mail-sender
  [endpoint base-url]
  (let [render-vars (merge-in-render-vars base-url)]
    (validate-configuration endpoint render-vars)
    (fn [{:keys [kixi.comms.command/payload] :as cmd}]
      (let [payload (merge default-payload
                           payload)]
        (if (s/valid? ::payload payload)
          (let [send-resp (send-email endpoint render-vars payload)]
            (if-not (:error send-resp)
              (accepted cmd send-resp)
              (aws-rejected cmd send-resp)))
          (invalid-rejected cmd (s/explain-data ::payload payload)))))))

(defrecord Mailer
    [communications send-mail-handler endpoint base-url]
    component/Lifecycle
    (start [component]
      (merge component
             (when-not send-mail-handler
               {:send-mail-handler
                (c/attach-command-handler!
                 communications
                 :kixi.mailer/mailer
                 :kixi.mailer/send-mail
                 "1.0.0" (create-mail-sender endpoint base-url))})))
    (stop [component]
      (-> component
          (update component :send-mail-handler
                  #(when %
                     (c/detach-handler! communications %)
                     nil)))))
