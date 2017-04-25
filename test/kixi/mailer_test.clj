(ns kixi.mailer-test
  (:require [clojure.test :refer :all]
            [clojure.spec :as s]
            [kixi.mailer.ses :as m]))

(def example-payload {:destination {:to-addresses ["example@example.com"]}
                      :source "no-reply@example.com"
                      :message {:subject "Test Subject"
                                :body {:html "testing 1-2-3-4"
                                       :text "testing 1-2-3-4"}}})

(deftest valid-payload-passes
  (is (nil? (s/explain-data ::m/payload example-payload))))
