;; kotoba.bytes — portable byte-vector primitives shared by kotoba-lang
;; protocol libraries (TURN/STUN codec, credential HMAC-SHA1 path, and other
;; consumers). Every "bytes" value in this library is a plain Clojure vector
;; of ints in [0,255] (NOT a platform byte-array), so almost all of this
;; code runs unmodified on the JVM and in ClojureScript. The one exception
;; is `utf8-encode`'s single `code-unit-at` accessor (below), a reader
;; conditional needed because :clj and :cljs disagree on how to pull a
;; UTF-16 code unit's numeric value out of an indexed string character —
;; discovered as a real bug (silently encoded every string as all-zero
;; bytes under :cljs) while building kotoba-lang/wire, this library's first
;; consumer actually exercised under a ClojureScript runtime (nbb).
(ns kotoba.bytes)

(defn u8 [n] (bit-and n 0xff))

(defn u16->bytes
  "Big-endian 2-byte encoding of a 16-bit unsigned int."
  [n]
  [(u8 (bit-shift-right n 8)) (u8 n)])

(defn bytes->u16
  "Decode 2 big-endian bytes (a 2-element byte vector) to an unsigned int."
  [[hi lo]]
  (bit-or (bit-shift-left (bit-and hi 0xff) 8) (bit-and lo 0xff)))

(defn u32->bytes
  "Big-endian 4-byte encoding of a 32-bit unsigned int."
  [n]
  [(u8 (bit-shift-right n 24)) (u8 (bit-shift-right n 16)) (u8 (bit-shift-right n 8)) (u8 n)])

(defn bytes->u32
  "Decode 4 big-endian bytes to an unsigned int (0..2^32-1)."
  [[b0 b1 b2 b3]]
  (bit-or (bit-shift-left (bit-and b0 0xff) 24)
          (bit-shift-left (bit-and b1 0xff) 16)
          (bit-shift-left (bit-and b2 0xff) 8)
          (bit-and b3 0xff)))

(defn xor-bytes
  "XOR two equal-length byte vectors elementwise."
  [a b]
  (mapv #(bit-xor %1 %2) a b))

(defn pad-right
  "Pad `bs` with `n` zero bytes on the right."
  [bs n]
  (into (vec bs) (repeat n 0)))

(defn- code-unit-at
  "The UTF-16 code unit (0..0xFFFF) at index i of string s. `(int (nth s i))`
   gives this on the JVM (String/nth -> Character, int coerces a Character
   to its code point) but NOT under ClojureScript — a JS string indexed via
   `nth` yields a length-1 *string*, and cljs's `int` does not coerce a
   string to a numeric code point (it silently returns 0 for any non-numeric
   input), so every call site that did `(int (nth s i))` here silently
   encoded every character as a NUL byte under :cljs — verified empirically
   under nbb (kotoba-lang/wire's Step 1, 2026-07). `.charAt`/`.charCodeAt`
   are genuinely platform-divergent accessors for the same UTF-16 code-unit
   value, so this one seam uses a reader conditional (same pattern already
   used elsewhere in this library's own test suite, e.g.
   kotoba.bytes.sha1-test's `hex` helper) rather than silently producing
   wrong bytes under one platform."
  [^String s i]
  #?(:clj (int (.charAt s i))
     :cljs (.charCodeAt s i)))

(defn utf8-encode
  "Encode a string to a UTF-8 byte vector. Pure/portable: handles the BMP plus
   surrogate-pair codepoints (>0xFFFF), :clj and :cljs produce byte-identical
   output (see code-unit-at for the one platform-divergent accessor this
   needs to actually achieve that, rather than merely claim it)."
  [^String s]
  (let [len (count s)]
    (loop [i 0 out (transient [])]
      (if (>= i len)
        (persistent! out)
        (let [c1 (code-unit-at s i)]
          (cond
            ;; surrogate pair -> single codepoint > 0xFFFF
            (and (<= 0xD800 c1 0xDBFF) (< (inc i) len))
            (let [c2 (code-unit-at s (inc i))]
              (if (<= 0xDC00 c2 0xDFFF)
                (let [cp (+ 0x10000
                            (bit-shift-left (- c1 0xD800) 10)
                            (- c2 0xDC00))]
                  (recur (+ i 2)
                         (reduce conj! out
                                 [(u8 (bit-or 0xF0 (bit-shift-right cp 18)))
                                  (u8 (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3F)))
                                  (u8 (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F)))
                                  (u8 (bit-or 0x80 (bit-and cp 0x3F)))])))
                (recur (inc i) out))) ; lone high surrogate: skip (malformed input)

            (< c1 0x80)
            (recur (inc i) (conj! out c1))

            (< c1 0x800)
            (recur (inc i)
                   (reduce conj! out
                           [(u8 (bit-or 0xC0 (bit-shift-right c1 6)))
                            (u8 (bit-or 0x80 (bit-and c1 0x3F)))]))

            :else
            (recur (inc i)
                   (reduce conj! out
                           [(u8 (bit-or 0xE0 (bit-shift-right c1 12)))
                            (u8 (bit-or 0x80 (bit-and (bit-shift-right c1 6) 0x3F)))
                            (u8 (bit-or 0x80 (bit-and c1 0x3F)))]))))))))

(defn ->bytes
  "Whatever a host handed over → this library's byte vector.

   Everything above operates on `vector<int 0..255>` and says so; this is the
   boundary where a value that came from somewhere else becomes one. Host
   runtimes hand bytes over in their own shapes — a JVM `byte[]` (signed, so
   0xFF arrives as -1), a `js/Uint8Array`, a Node `Buffer`, a lazy seq — and a
   library whose stated contract is a vector has to be reachable from all of
   them or the contract is advice.

   The distinction worth keeping is absent versus empty: `nil` stays `nil`,
   because a caller asking a store for an object it does not have needs a
   different answer from one asking for an object that is zero bytes long, and
   collapsing them here would erase that upstream where it cannot be recovered.

   A string is UTF-8 encoded, which is what `constant-time-eq` already assumes
   a string means when it is used as bytes. It is not a passthrough: leaving
   strings alone would let a value that is not a byte vector out of a function
   whose whole job is that every value leaving it is one.

   Out-of-range ints are masked rather than refused, because `u8` is what every
   other function here does with them and two answers to that question in one
   library is worse than either answer.

   Anything with no byte reading throws. Returning `nil` would put it in the
   same bucket as a missing object, which is the one distinction this is
   careful to preserve."
  [x]
  (cond
    (nil? x)     nil
    (string? x)  (utf8-encode x)
    ;; `seqable?` rather than `sequential?`: it is the predicate that covers a
    ;; JVM byte[], a Uint8Array and a Buffer, none of which are `sequential?`
    ;; but all of which are exactly what a host store hands back (verified on
    ;; both runtimes — see scripts/verify-cljs.cljs).
    (seqable? x) (mapv u8 x)
    :else        (throw (ex-info "not convertible to bytes"
                                 {:value x :type (type x)}))))

(def ^:private b64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn base64-encode
  "Standard (RFC 4648) base64 encode of a byte vector, padded with `=`."
  [bs]
  (let [n (count bs)]
    (apply str
           (loop [i 0 out []]
             (if (>= i n)
               out
               (let [b0 (nth bs i)
                     b1 (when (< (inc i) n) (nth bs (inc i)))
                     b2 (when (< (+ i 2) n) (nth bs (+ i 2)))
                     triple (bit-or (bit-shift-left b0 16)
                                    (bit-shift-left (or b1 0) 8)
                                    (or b2 0))
                     c0 (bit-and (bit-shift-right triple 18) 0x3F)
                     c1 (bit-and (bit-shift-right triple 12) 0x3F)
                     c2 (bit-and (bit-shift-right triple 6) 0x3F)
                     c3 (bit-and triple 0x3F)]
                 (recur (+ i 3)
                        (conj out
                              (nth b64-alphabet c0)
                              (nth b64-alphabet c1)
                              (if b1 (nth b64-alphabet c2) \=)
                              (if b2 (nth b64-alphabet c3) \=)))))))))

(def ^:private b64-index
  (into {} (map-indexed (fn [i c] [c i]) b64-alphabet)))

(defn base64-decode
  "Standard (RFC 4648) base64 decode to a byte vector. Ignores a trailing `=`
   pad. Returns nil on malformed input (non-alphabet character)."
  [^String s]
  (let [chars (remove #(= % \=) (seq s))]
    (when (every? #(contains? b64-index %) chars)
      (let [vals (mapv b64-index chars)
            n (count vals)]
        (loop [i 0 out (transient [])]
          (if (>= i n)
            (persistent! out)
            (let [v0 (nth vals i)
                  v1 (when (< (inc i) n) (nth vals (inc i)))
                  v2 (when (< (+ i 2) n) (nth vals (+ i 2)))
                  v3 (when (< (+ i 3) n) (nth vals (+ i 3)))
                  triple (bit-or (bit-shift-left v0 18)
                                 (bit-shift-left (or v1 0) 12)
                                 (bit-shift-left (or v2 0) 6)
                                 (or v3 0))]
              (recur (+ i 4)
                     (cond-> out
                       true (conj! (u8 (bit-shift-right triple 16)))
                       v2   (conj! (u8 (bit-shift-right triple 8)))
                       v3   (conj! (u8 triple)))))))))))

(defn constant-time-eq
  "Constant-time equality for two byte vectors (or two strings). Always scans
   the full length of the longer input and folds every mismatch into a single
   accumulator, instead of short-circuiting on the first differing element —
   this avoids leaking a length/content-dependent timing signal, which is the
   whole point when comparing a caller-presented MESSAGE-INTEGRITY digest or
   TURN credential against the locally-computed value (a plain `=` here would
   be a timing side-channel on secret-derived data)."
  [a b]
  (let [a (if (string? a) (utf8-encode a) a)
        b (if (string? b) (utf8-encode b) b)
        n (max (count a) (count b))]
    (loop [i 0 diff (bit-xor (count a) (count b))]
      (if (>= i n)
        (zero? diff)
        (recur (inc i)
               (bit-or diff (bit-xor (int (get a i 0)) (int (get b i 0)))))))))

;; ── hex ──────────────────────────────────────────────────────────────────────
;;
;; A byte-vector's hex form is the lingua franca of digests, signatures and
;; content identifiers, and it was hand-rolled in 33 repos here (and parsed back
;; in 36). The implementations differ in ways that only show up at the edges:
;; some build the string with `(.toString b 16)` and forget to pad a byte below
;; 0x10, some emit uppercase where a protocol demands lowercase. Neither
;; mistake is visible until a signature is rejected.

(def ^:private hex-digits "0123456789abcdef")

(defn hex
  "Lowercase hex encoding of a byte-vector. Every byte becomes exactly two
   characters, including bytes below 0x10 — the padding a `toString(16)`-based
   encoder silently drops."
  [bs]
  (apply str (mapcat (fn [b]
                       (let [v (u8 b)]
                         [(nth hex-digits (bit-shift-right v 4))
                          (nth hex-digits (bit-and v 0xf))]))
                     bs)))

(defn- hex-val [c]
  (let [n #?(:clj (int ^char c) :cljs (.charCodeAt c 0))]
    (cond
      (<= 48 n 57)  (- n 48)          ; 0-9
      (<= 97 n 102) (- n 87)          ; a-f
      (<= 65 n 70)  (- n 55)          ; A-F
      :else nil)))

(defn unhex
  "Decode a hex string to a byte-vector. Accepts either case. Returns nil on an
   odd length or a non-hex character rather than guessing — a truncated or
   corrupted digest should fail loudly, not decode to something plausible."
  [s]
  (let [s (str s)]
    (when (even? (count s))
      (loop [i 0 out (transient [])]
        (if (>= i (count s))
          (persistent! out)
          (let [hi (hex-val (nth s i))
                lo (hex-val (nth s (inc i)))]
            (when (and hi lo)
              (recur (+ i 2) (conj! out (bit-or (bit-shift-left hi 4) lo))))))))))
