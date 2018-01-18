(ns kixi.mailer-test
  (:require [amazonica.aws.simpleemail :as email]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [kixi.mailer.ses :as m]))

(def example-payload {:destination {:to-addresses ["example@example.com"]}
                      :source "no-reply@example.com"
                      :message {:subject "Test Subject"
                                :body {:html "testing 1-2-3-4"
                                       :text "testing 1-2-3-4"}}})

(deftest valid-payload-passes
  (is (nil? (s/explain-data ::m/payload example-payload))))

(def test-text "This is an email from the integration tests for kixi.mailer.")

(def test-body {:body {:text (str "<<&env.default_header>>" test-text "<<&env.default_footer>>")
                       :html (str "<<&env.default_header>>" test-text "<<&env.default_footer>>")}})

(deftest templating-text
  (is (= {:body {:text (str (slurp (io/resource "emails/default-text-header.txt")) test-text (slurp (io/resource "emails/default-text-footer.txt")))
                 :html (str (slurp (io/resource "emails/default-html-header.html")) test-text (slurp (io/resource "emails/default-html-footer.html")))}}
         (m/render-templates (m/merge-in-render-vars "rendered-base-url") test-body))))

(st/instrument)

(deftest mail-sender
  (with-redefs [email/send-email (constantly :redefed-accept)]
    (let [sender (m/create-mail-sender "endpoint" "base-url")]
      (is (= (m/accepted {} :redefed-accept)
             (sender {:kixi.comms.command/payload example-payload}))))))

(def new-example-payload {:kixi.mailer/destination {:kixi.mailer.destination/to-groups [(str (java.util.UUID/randomUUID))]}
                          :kixi.mailer/source "no-reply@example.com"
                          :kixi/mailer/message {:kixi.mailer.message/subject "Test Subject"
                                                :kixi.mailer.message/body {:html "testing 1-2-3-4"
                                                                           :text "testing 1-2-3-4"}}})

(deftest group-mail-sender
  (with-redefs [email/send-email (constantly :redefed-accept)]
    (let [sender (m/create-group-mail-sender "endpoint" "base-url")]
      (is (= (m/group-accepted new-example-payload)
             (sender new-example-payload)))
      (is (= (:kixi.mailer/destination new-example-payload)
             (:kixi.mailer/destination (first (sender new-example-payload)))
             (:kixi.mailer/destination (first (m/group-accepted new-example-payload))))))))
