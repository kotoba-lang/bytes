# kotoba-bytes

[![CI](https://github.com/kotoba-lang/bytes/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/bytes/actions/workflows/ci.yml)

Portable byte-vector primitives and pure SHA-1/HMAC-SHA1 for kotoba-lang
protocol libraries — no platform crypto API, identical code on JVM and
ClojureScript.

## Contract

- **`kotoba.bytes`** — portable byte-vector primitives (u16/u32 big-endian
  codecs, XOR, right-padding, base64 encode/decode, UTF-8 encode,
  constant-time equality). Every "bytes" value in this library is a plain
  `vector<int 0..255>`, never a platform byte-array, so the exact same code
  runs on the JVM and in ClojureScript with nothing to reader-conditional.
- **`kotoba.bytes.sha1`** — pure SHA-1 (FIPS 180-4) + HMAC-SHA1 (RFC 2104), no
  platform crypto API. Depends only on `kotoba.bytes`.

```clojure
(require '[kotoba.bytes :as b])

(b/u16->bytes 0x2112)          ;=> [0x21 0x12]
(b/bytes->u32 [0 0 0 42])      ;=> 42
(b/xor-bytes [1 2 3] [1 1 1])  ;=> [0 3 2]
(b/utf8-encode "kotoba")       ;=> [107 111 116 111 98 97]
(b/base64-encode (b/utf8-encode "hi"))  ;=> "aGk="
(b/constant-time-eq "abc" "abc")        ;=> true
```

```clojure
(require '[kotoba.bytes.sha1 :as sha1]
         '[kotoba.bytes :as b])

(sha1/sha1-bytes (b/utf8-encode "abc"))
;; => [0xa9 0x99 0x3e 0x36 ...]  (20-byte vector, FIPS 180-1 vector)

(sha1/hmac-sha1 (b/utf8-encode "key") (b/utf8-encode "The quick brown fox"))
;; => 20-byte vector (RFC 2104 HMAC-SHA1)
```

## Background

This repo was extracted from
[`kotoba-lang/turn`](https://github.com/kotoba-lang/turn) (Phase 1 of a
shared-lib consolidation across kotoba-lang protocol libraries): `turn`
originally owned `kotoba.turn.bytes` and `kotoba.turn.sha1` as internal
implementation details of its RFC 8656 TURN/STUN codec, but neither namespace
is actually TURN/STUN-specific — they are generic low-level primitives that
any protocol-message codec or credential/HMAC path needs. Pulling them out
into their own repo lets other kotoba-lang libraries depend on the same
byte-vector/hash primitives instead of re-implementing them.

Intended/likely consumers: `kotoba-lang/turn` (STUN/TURN message codec,
ephemeral-credential mint/verify) and `kotoba-lang/dtn` (which currently has
ad-hoc, non-portable length-prefix framing code that this library is meant to
replace).

## Test

```
clojure -M:test
clojure -M:lint
```

## License

Apache License 2.0. See `LICENSE`.
