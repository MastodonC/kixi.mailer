(ns kixi.mailer.ses
  (:require [amazonica.aws.simpleemail :as email]
            [com.stuartsierra.component :as component]
            [clostache.parser :as parser]
            [clojure.spec.alpha :as s]
            [kixi.comms :as c]
            [kixi.mailer :as m]
            [kixi.mailer.destination :as md]
            [kixi.mailer.message :as mm]
            [kixi.mailer.heimdall :as h]
            [kixi.spec.conformers :as sc]
            [kixi.log.timbre.appenders.logstash :as l]
            [taoensso.timbre :as log :refer [error]]
            [clojure.java.io :as io]))

(s/def ::to-addresses
  (s/coll-of sc/email? :min-count 1 :max-count 50))

(s/def ::source sc/email?)

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod c/command-payload
  [:kixi.mailer/send-group-mail "1.0.0"]
  [_]
  (s/keys :req [::m/destination
                ::m/message]
          :opt [::m/source]))

(defmethod c/event-payload
  [:kixi.mailer/group-mail-accepted "1.0.0"]
  [_]
  (s/keys :req [::m/destination]
          :opt [::m/source]))

(defmethod c/event-payload
  [:kixi.mailer/group-mail-rejected "1.0.0"]
  [_]
  (s/keys :req [::m/destination
                ::m/reason]
          :opt [::m/source
                ::m/explain]))

(defmethod c/command-type->event-types
  [:kixi.mailer/send-group-mail "1.0.0"]
  [_]
  #{[:kixi.mailer/group-mail-accepted "1.0.0"]
    [:kixi.mailer/group-mail-rejected "1.0.0"]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn group-accepted
  [{:keys [::m/destination ::m/source] :as cmd}]
  [(merge {:kixi.event/type :kixi.mailer/group-mail-accepted
           :kixi.event/version "1.0.0"
           ::m/destination destination}
          (when source
            {::m/source source}))
   {:partition-key (get-in cmd [:kixi/user :kixi.user/id])}])

(defn group-rejected
  ([cmd reason]
   (group-rejected cmd reason nil))
  ([{:keys [::m/destination ::m/source] :as cmd} reason message]
   [(merge {:kixi.event/type :kixi.mailer/group-mail-rejected
            :kixi.event/version "1.0.0"
            :kixi.mailer.reject/reason reason
            :kixi.mailer/destination destination}
           (when source
             {:kixi.mailer/source source})
           (when message
             {:kixi.mailer.reject/message message}))
    {:partition-key (get-in cmd [:kixi/user :kixi.user/id])}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn send-group-email
  [directory endpoint render-vars {:keys [::m/destination ::m/source ::m/message :kixi/user]}]
  (let [emails (h/resolve-group-emails user directory (::md/to-groups destination))
        _ (println "GROUPS -> EMAILS ")
        _ (println (::md/to-groups destination))
        _ (println emails)
        {:keys [::mm/body ::mm/subject]} message
        {:keys [::mm/html ::mm/text]} body]
    (send-email endpoint render-vars {:destination {:to-addresses emails}
                                      :source source
                                      :message {:subject subject
                                                :body (merge {}
                                                             (when html {:html html})
                                                             (when text {:text text}))}})))

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

(defn create-group-mail-sender
  [directory endpoint base-url]
  (let [render-vars (merge-in-render-vars base-url)]
    (validate-configuration endpoint render-vars)
    (fn [cmd]
      (if (s/valid? :kixi/command cmd)
        (let [send-resp (send-group-email directory endpoint render-vars cmd)]
          (if-not (:error send-resp)
            (group-accepted cmd)
            (group-rejected cmd :service-error (pr-str send-resp))))
        (group-rejected cmd :invalid-cmd (pr-str (s/explain-data :kixi/command cmd)))))))

(defrecord Mailer
    [directory communications
     endpoint base-url
     send-mail-handler
     send-group-mail-handler]
  component/Lifecycle
  (start [component]
    (log/info "Starting SES mailer" directory)
    (merge component
           (when-not send-mail-handler
             {:send-mail-handler
              (c/attach-command-handler!
               communications
               :kixi.mailer/mailer
               :kixi.mailer/send-mail
               "1.0.0" (create-mail-sender endpoint base-url))})
           (when-not send-group-mail-handler
             {:send-group-mail-handler
              (c/attach-validating-command-handler!
               communications
               :kixi.mailer/mailer-group
               :kixi.mailer/send-group-mail
               "1.0.0" (create-group-mail-sender directory endpoint base-url))})))
  (stop [component]
    (log/info "Stopping SES mailer")
    (-> component
        (update :send-mail-handler
                #(when %
                   (c/detach-handler! communications %)
                   nil))
        (update :send-group-mail-handler
                #(when %
                   (c/detach-handler! communications %)
                   nil)))))
