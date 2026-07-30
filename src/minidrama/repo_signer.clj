(ns minidrama.repo-signer
  "no-server-key repo signing key for the aozora PDS — the JVM counterpart of
  the browser's `yoro-ui.interop.repo-signer` (ADR-2605231525 / ADR-2606251700).

  Two keys, two jobs — the same split the browser wallet uses:

    minidrama.cacao   Ed25519 IDENTITY key. Issues the CACAO that proves who we
                      are (createSession / createAccount). Stays exactly as it
                      is; this namespace does not touch it.
    this ns           secp256k1 REPO SIGNING key. Signs the repo commit, and
                      nothing else. Its PUBLIC half is published to the PDS
                      (app.aozora.repo.publishKey) so a Relay can verify our
                      commits; the private half never leaves this machine.

  Why a second key at all: a repo commit signature must be secp256k1
  (aozora.pds.didkey/multikey->pub only accepts multicodec 0xe7 0x01), while a
  CACAO issuer must be Ed25519 (aozora.pds.auth/verify-cacao-claims resolves
  :iss with did-key->ed25519-pub). One key cannot be both, which is why the
  account DID is a did:web whose *controller* is the Ed25519 key — collapsing
  the two into a single did:key would leave us unable to authenticate at all.

  secp256k1 is not available from SunEC on JDK 16+ (verified: JDK 21 answers
  \"Curve not supported: secp256k1\"), so this uses BouncyCastle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest SecureRandom]
           [java.math BigInteger]
           [org.bouncycastle.asn1.sec SECNamedCurves]
           [org.bouncycastle.crypto.params ECDomainParameters ECPrivateKeyParameters]
           [org.bouncycastle.crypto.signers ECDSASigner HMacDSAKCalculator]
           [org.bouncycastle.crypto.digests SHA256Digest]))

(def ^:private curve (SECNamedCurves/getByName "secp256k1"))
(def ^:private domain
  (ECDomainParameters. (.getCurve curve) (.getG curve) (.getN curve) (.getH curve)))
(def ^:private half-n (.shiftRight (.getN curve) 1))

;; ── encodings ────────────────────────────────────────────────────────────────

(def ^:private b58 "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(defn- base58btc [^bytes data]
  (let [zeros (count (take-while zero? data))
        sb (StringBuilder.) fifty8 (BigInteger/valueOf 58)]
    (loop [n (BigInteger. 1 data)]
      (when (pos? (.signum n))
        (.append sb (.charAt b58 (.intValue (.mod n fifty8))))
        (recur (.divide n fifty8))))
    (dotimes [_ zeros] (.append sb \1))
    (.toString (.reverse sb))))

(defn- hex->bytes ^bytes [^String s]
  (let [n (/ (count s) 2) out (byte-array n)]
    (dotimes [i n]
      (aset-byte out i (unchecked-byte (Integer/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16))))
    out))

(defn- bytes->hex ^String [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(defn- ->32 ^bytes [^BigInteger n]
  ;; BigInteger drops leading zero bytes and may prepend a sign byte; a
  ;; compact ECDSA signature needs exactly 32 bytes per scalar.
  (let [b (.toByteArray n)
        len (alength b)]
    (cond
      (= len 32) b
      (> len 32) (java.util.Arrays/copyOfRange b (- len 32) len)
      :else (let [out (byte-array 32)]
              (System/arraycopy b 0 out (- 32 len) len)
              out))))

;; ── key lifecycle ────────────────────────────────────────────────────────────

(defn- gen-priv ^BigInteger []
  (let [rnd (SecureRandom.)]
    (loop []
      (let [d (BigInteger. 256 rnd)]
        (if (and (pos? (.signum d)) (neg? (.compareTo d (.getN curve)))) d (recur))))))

(defn load-or-create-key!
  "Load the persisted secp256k1 repo signing key at `path`, or generate and
  persist one on first run. Never overwrites an existing key — it may already
  have signed commits under this repo, and replacing it would orphan them.
  Returns {:priv BigInteger :priv-hex :pub-compressed bytes :multikey}."
  [path]
  (let [f (io/file path)
        d (if (.exists f)
            (let [raw (str/trim (slurp f))]
              ;; A present-but-unreadable key file (interrupted write, truncated
              ;; copy) must FAIL, never fall through to generating a fresh key:
              ;; a new key silently orphans every commit already signed under
              ;; this repo, and the repo would drop out of listRepos with no
              ;; error anywhere. Restore the key or move the file aside
              ;; deliberately.
              (when-not (re-matches #"[0-9a-fA-F]{64}" raw)
                (throw (ex-info (str "repo signing key at " path
                                     " is present but not 64 hex chars — refusing to"
                                     " generate a replacement, which would orphan commits"
                                     " already signed by the original key")
                                {:path path :length (count raw)})))
              (BigInteger. raw 16))
            (let [d (gen-priv)
                  parent (.getParentFile (.getAbsoluteFile f))]
              (when parent (.mkdirs parent))
              (spit f (bytes->hex (->32 d)))
              d))
        pub (.getEncoded (.multiply (.getG curve) d) true)] ; true = compressed (33 bytes)
    {:priv d
     :priv-hex (bytes->hex (->32 d))
     :pub-compressed pub
     :pub-hex (bytes->hex pub)
     ;; Multikey = z + base58btc(0xe7 0x01 || pub33) — the encoding
     ;; com.atproto.server.createAccount's :signingKey expects. publishKey
     ;; accepts either this or the raw hex.
     :multikey (str "z" (base58btc (byte-array (concat [(unchecked-byte 0xe7) (unchecked-byte 0x01)]
                                                       (seq pub)))))}))

;; ── signing ──────────────────────────────────────────────────────────────────

(defn sign-compact
  "low-S ECDSA over sha256(`msg`) → 64-byte compact r||s, base64.

  Three things the PDS's verifier (@noble/curves via
  aozora.pds.noserverkey/commit-signed) requires and DER would fail:
  compact r||s rather than DER, exactly 32 bytes per scalar, and low-S
  (s > n/2 is rejected as a malleable duplicate of its complement)."
  [{:keys [priv]} ^bytes msg]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") msg)
        signer (doto (ECDSASigner. (HMacDSAKCalculator. (SHA256Digest.)))
                 (.init true (ECPrivateKeyParameters. priv domain)))
        sig (.generateSignature signer digest)
        r (aget sig 0)
        s0 (aget sig 1)
        s (if (pos? (.compareTo s0 half-n)) (.subtract (.getN curve) s0) s0)]
    (.encodeToString (java.util.Base64/getEncoder)
                     (byte-array (concat (seq (->32 r)) (seq (->32 s)))))))
