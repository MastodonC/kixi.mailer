(ns kixi.mailer.heimdall
  (:require [clj-http.client :as http]
            [taoensso.timbre :as log]
            [cognitect.transit :as tr])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.httpkit.BytesInputStream]))

;; copied from witan.gateway

(def transit-encoding-level :json-verbose) ;; DO NOT CHANGE
(defn transit-decode-bytes [in]
  (let [reader (tr/reader in transit-encoding-level)]
    (tr/read reader)))
(defn transit-decode [^String s]
  (let [sbytes (.getBytes s)
        in (ByteArrayInputStream. sbytes)
        reader (tr/reader in transit-encoding-level)]
    (tr/read reader)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn directory-url
  [k s & params]
  (apply str "http://"
         (get-in s [k :host]) ":"
         (get-in s [k :port]) "/"
         (clojure.string/join "/" params)))

(defn user-header
  [{:keys [kixi.user/id kixi.user/groups] :as u}]
  {"user-groups" (clojure.string/join "," groups)
   "user-id" id})

(defn error-response
  ([msg resp]
   (error-response msg resp true))
  ([msg {:keys [status body] :as resp} log?]
   (when log?
     (log/error "An error response was generated:" msg resp))
   {:error (str "Invalid status: " status)
    :error-info {:msg msg}}))

(defn get-elements
  [u d method elements & _]
  (let [url (directory-url :heimdall d method)
        resp (http/get url {:query-params {:id elements}
                            :headers (user-header u)})]
    (if (= 200 (:status resp))
      (:body (update resp
                     :body
                     #(when %
                        (transit-decode %))))
      (error-response "heimdall get-elements" resp))))

(defn get-users-info
  [u d users]
  (get-elements u d "users" (vec users)))

(defn get-groups-info
  [u d groups]
  (get-elements u d "groups" (vec groups)))

(defn get-all-groups
  [u d]
  (let [url (directory-url :heimdall d "groups" "search")
        resp @(http/get url {:headers (user-header u)})]
    (if (= 200 (:status resp))
      (:body (update resp
                     :body
                     #(when %
                        (transit-decode %))))
      (error-response "heimdall group-search" resp))))

(defn resolve-group-emails
  [u d groups]
  (->> groups
       (get-groups-info u d)
       :items
       (map :kixi.group/created-by)
       (get-users-info u d)
       :items
       (map :kixi.user/username)
       set))
