(ns minidrama.repo-signer-test
  "The repo signing key's encodings are a wire contract with the aozora PDS, and
  every one of them fails CLOSED and silently if it drifts: createAccount
  rejects a signingKey whose multicodec is not secp256k1, and commitSigned
  rejects a signature that is DER, wrong-length, or high-S — all as a generic
  InvalidSignature with no hint which of the three it was. These pin the shapes
  so a regression shows up here instead of as an actor that quietly stops
  federating."
  (:require [clojure.test :refer [deftest is testing]]
            [minidrama.repo-signer :as rs])
  (:import [java.io File]))

(defn- tmp-key-path
  "A path that does NOT yet exist — createTempFile would leave an empty file,
  which load-or-create-key! correctly refuses rather than silently re-keying."
  []
  (let [f (File/createTempFile "minidrama-signer-test" ".hex")]
    (.delete f)
    (str f)))

(deftest key-is-persistent-and-never-regenerates
  (testing "a second load returns the SAME key"
    (let [p (tmp-key-path)
          a (rs/load-or-create-key! p)
          b (rs/load-or-create-key! p)]
      (is (= (:priv-hex a) (:priv-hex b))
          "regenerating would orphan every commit already signed under this repo")
      (is (= (:multikey a) (:multikey b))))))

(deftest public-key-encodings-match-what-the-pds-accepts
  (let [k (rs/load-or-create-key! (tmp-key-path))]
    (testing "compressed secp256k1 point"
      (is (= 33 (alength ^bytes (:pub-compressed k))))
      (is (contains? #{0x02 0x03} (bit-and (aget ^bytes (:pub-compressed k) 0) 0xff))
          "compressed points start 0x02/0x03; 0x04 would be uncompressed (65 bytes)"))
    (testing "Multikey is z + base58btc(0xe7 0x01 || pub33)"
      ;; aozora.pds.didkey/multikey->pub returns nil for any other multicodec,
      ;; and createAccount then answers "valid secp256k1 signingKey required".
      (is (.startsWith ^String (:multikey k) "zQ3sh")
          "secp256k1 Multikeys start zQ3sh; an Ed25519 one would start z6Mk"))
    (testing "hex form publishKey takes"
      (is (= 66 (count (:pub-hex k))) "33 bytes = 66 hex chars"))))

(deftest signature-is-compact-64-byte-low-s
  (let [k (rs/load-or-create-key! (tmp-key-path))
        raw (fn [msg] (.decode (java.util.Base64/getDecoder)
                               ^String (rs/sign-compact k (.getBytes ^String msg "UTF-8"))))]
    (testing "64-byte compact r||s, not DER"
      (let [s (raw "hello")]
        (is (= 64 (alength s)))
        (is (not= 0x30 (bit-and (aget s 0) 0xff))
            "0x30 would mean a DER SEQUENCE — @noble/curves expects compact")))
    (testing "low-S: s <= n/2 for many messages"
      ;; A high-S signature is a valid-but-malleable twin that the verifier
      ;; rejects, so this must hold for every signature, not on average.
      (let [half (.shiftRight (java.math.BigInteger. "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141" 16) 1)]
        (doseq [i (range 20)]
          (let [s (raw (str "msg-" i))
                s-int (java.math.BigInteger. 1 (java.util.Arrays/copyOfRange s 32 64))]
            (is (<= (.compareTo s-int half) 0) (str "high-S for msg-" i))))))
    (testing "deterministic (RFC 6979): same message, same signature"
      (is (= (seq (raw "same")) (seq (raw "same")))))))

(deftest a-corrupt-key-file-fails-loudly-instead-of-re-keying
  (testing "present but unreadable -> throw, never generate a replacement"
    ;; The dangerous outcome is not the exception; it is silently minting a new
    ;; key, which orphans every commit the original signed and drops the repo
    ;; out of listRepos with nothing logged anywhere.
    (let [p (tmp-key-path)]
      (spit p "")
      (is (thrown? clojure.lang.ExceptionInfo (rs/load-or-create-key! p)))
      (spit p "not-hex")
      (is (thrown? clojure.lang.ExceptionInfo (rs/load-or-create-key! p))))))
