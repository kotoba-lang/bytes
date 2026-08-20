(ns kotoba.bytes-test
  "`kotoba.bytes` says every value it handles is a `vector<int 0..255>`. These
   are the tests for the one function that has to make that true of values
   arriving from outside."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.bytes :as b]))

(defn- host-bytes
  "A platform byte container holding 1, 0xFF, 0 — the shape a store hands back.

   On the JVM that is a signed `byte[]`, where 0xFF is -1; under ClojureScript
   a `Uint8Array`, where it is 255. The divergence is the reason this function
   exists."
  []
  #?(:clj  (byte-array [1 -1 0])
     :cljs (js/Uint8Array.from #js [1 255 0])))

(deftest absent-is-not-empty
  (testing "nil survives as nil"
    (is (nil? (b/->bytes nil))))
  (testing "and an empty container is empty, not nil"
    (is (= [] (b/->bytes [])))
    (is (some? (b/->bytes [])))))

(deftest host-containers-become-unsigned-vectors
  (testing "a platform byte container, whatever its signedness"
    (is (= [1 255 0] (b/->bytes (host-bytes)))))
  (testing "a vector already in range is unchanged in value"
    (is (= [0 127 255] (b/->bytes [0 127 255]))))
  (testing "a lazy seq, which is neither a vector nor an array"
    (is (= [0 1 2 3] (b/->bytes (range 4)))))
  (testing "and the result is always a vector, never the input container"
    (is (vector? (b/->bytes (host-bytes))))
    (is (vector? (b/->bytes (range 4))))))

(deftest strings-are-utf8-encoded-not-passed-through
  (testing "ascii"
    (is (= [104 105] (b/->bytes "hi"))))
  (testing "multi-byte, agreeing with utf8-encode rather than with UTF-16"
    (is (= (b/utf8-encode "日本語") (b/->bytes "日本語")))
    (is (= 9 (count (b/->bytes "日本語")))
        "three 3-byte codepoints; a UTF-16 passthrough would be 3"))
  (testing "a string does not leave this function as a string"
    (is (vector? (b/->bytes "hi")))))

(deftest out-of-range-is-masked-like-u8
  (is (= [44] (b/->bytes [300])) "300 & 0xff")
  (is (= [255] (b/->bytes [-1])))
  (is (= (mapv b/u8 [300 -1 256]) (b/->bytes [300 -1 256]))
      "whatever u8 does, this does"))

(deftest nothing-unreadable-slips-through-as-a-value
  (testing "a value with no byte reading throws rather than returning nil,"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                          #"not convertible to bytes"
                          (b/->bytes 42))))
  (testing "because nil is already taken by the absent case"
    (is (nil? (b/->bytes nil)))))

(deftest round-trips-with-the-rest-of-the-library
  (testing "coerced host bytes are usable by every other function here"
    (let [bs (b/->bytes (host-bytes))]
      (is (= "01ff00" (b/hex bs)))
      (is (= bs (b/unhex (b/hex bs))))
      (is (= bs (b/base64-decode (b/base64-encode bs))))))
  (testing "and a coerced string equals the same string encoded directly"
    (is (b/constant-time-eq (b/->bytes "Jefe") (b/utf8-encode "Jefe")))))

(deftest utf8-decode-round-trips-and-refuses-malformed
  (testing "round-trips with utf8-encode"
    (doseq [s ["" "hi" "a\nb" "日本語" "\u00e9" "emoji \ud83d\ude00 here"]]
      (is (= s (b/utf8-decode (b/utf8-encode s))) (str "round-trip: " (pr-str s)))))
  (testing "ASCII decodes to itself"
    (is (= "abc" (b/utf8-decode [97 98 99]))))
  (testing "malformed input returns nil rather than a plausible string"
    ;; Each of these is what a lenient decoder turns into U+FFFD -- and a
    ;; string that looks fine is exactly the failure this contract avoids.
    (is (nil? (b/utf8-decode [0xC0 0x80])) "overlong NUL")
    (is (nil? (b/utf8-decode [0xE0 0x80 0xAF])) "overlong '/'")
    (is (nil? (b/utf8-decode [0xED 0xA0 0x80])) "surrogate half U+D800")
    (is (nil? (b/utf8-decode [0xF5 0x80 0x80 0x80])) "above U+10FFFF")
    (is (nil? (b/utf8-decode [0x80])) "bare continuation byte")
    (is (nil? (b/utf8-decode [0xE2 0x82])) "truncated 3-byte sequence")
    (is (nil? (b/utf8-decode [0xC2 0x41])) "bad continuation byte")))
