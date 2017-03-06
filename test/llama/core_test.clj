(ns llama.core-test
  (:require [clojure.test :refer :all]
            [llama
             [core :refer :all]
             [route :refer :all]])
  (:import org.apache.camel.ExchangePattern
           [org.apache.camel.impl DefaultCamelContext DefaultExchange]))

(deftest replying
  (testing "with Message: reply works as expected"
    (let [ctx (DefaultCamelContext.)
          xchg (DefaultExchange. ctx ExchangePattern/InOut)]
      (reply xchg "hi there" :id "313" :headers {"foo" "bar"})
      (let [msg (out xchg)]
        (is (= (body msg) "hi there"))
        (is (= (id msg) "313"))
        (is (= (.get (headers msg) "foo") "bar"))))))


(deftest requesting
  (testing "requesting a reply works"
    (let [ctx (DefaultCamelContext.)
          routes (route (from "direct:foo")
                        (processor (fn [x] (reply x "haha"))))]
      (.addRoutes ctx routes)
      (start ctx)
      (is (= "haha" (request-body ctx "direct:foo" "hehe")))
      (stop ctx))))
