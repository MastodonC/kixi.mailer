(ns kixi.mailer-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [clojure.spec :as s]
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
         (m/render-templates test-body))))
