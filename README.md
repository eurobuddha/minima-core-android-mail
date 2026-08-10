# Minima Mail (native Android)

A native Android **on-chain encrypted messenger** for [Minima](https://minima.global) — a faithful clone of the
ChainMail MiniDapp. The Minima node is a **dumb transport**: there is no Maxima, no MDS, no server. Every message is
a tiny coin on the chain carrying an end-to-end-encrypted blob, so only the recipient can read it. Package
`com.eurobuddha.mail`.

## How it works

- **Identity** is derived **once** from the node seed (`vault action:seed`) via HKDF-SHA256 into an **X25519** box
  keypair (encryption) + an **Ed25519** signing keypair — your `publicId` is `0x` + the two public keys. It's cached
  locally and re-derived only on a seed restore; the raw seed is never stored.
- **Sending** posts a `0.000000001` MINIMA coin to the shared sentinel address **`0x434841494E4D41494C`**
  ("CHAINMAIL") with the message sealed (`crypto_box_seal`) into coin **state port 99**.
- **Receiving** scans the CHAINMAIL address (`coins address:…`) and **trial-decrypts** each blob with your box key —
  only your own messages open. Sender authenticity is Ed25519-signed.

Because everything is on-chain and seed-derived, the **same identity and inbox appear on any device** running Minima
Mail against a node restored from the same seed, and it **interoperates with the desktop minimaMail module** (same
wire types + sentinel + crypto).

## Features

Since 0.5.0 the app is styled as **on-chain email** (delivery takes a block, so it behaves like email, not
instant chat) while conversations keep familiar chat bubbles:

- **Email shell** — drawer navigation (Inbox · Outbox · Sent · Archive · Contacts · Your key · Settings),
  optional **subjects** (a subject = its own thread; blank = one running thread per contact — the wire
  format and `threadKey` were always subject-aware, so old threads are untouched), a real **compose**
  screen (To / Subject / Body), and a custom icon set (no emoji/stock glyphs).
- **Honest delivery status** — sends land in the **Outbox** instantly as *Posting to chain…*, move to
  Sent as *On-chain · block N*, then *Confirmed*; failures stay in the Outbox with one-tap **Retry**
  instead of vanishing into a toast. The inbox shows *"Checked block N · Xm ago"* (pull-to-refresh),
  and compose states up front that delivery rides the next block (~1–3 min).
- **Themes** — light "paper" default + the family dark theme, one toggle in Settings (token engine).
- **Message types** — text, images, and **in-chat payments** (with a payaddr request/reply handshake so you can send
  funds to a contact's own receive address, confirmed and irreversible).
- **QR** — scan a `publicId` to add a contact, or show yours to be added.
- **Backup / restore** — passphrase-encrypted backup (PBKDF2-HMAC-SHA256 + AES-256-GCM), byte-compatible with the
  desktop module so a backup cross-restores (0.5.0 additionally backs up subjects, payments and images).
- OS notifications, day grouping, copyable receiving address.

## Build

Requires a **JDK 17/21** (the Android Studio JBR works):

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

Install, then enable **Minima Mail** in Minima Core → Apps to authorize the IPC (needed to read/derive the seed and
post the transport coins).

## Releases

Versioned APKs are published to the [PandaApps catalog](https://github.com/eurobuddha/minima-core-apks)
(`apks.json`). Current: **v0.5.0**.

The reusable crypto/transport layer lives in `com/eurobuddha/comms/` (`CommsIdentity`, sealed-box send/scan) and is
shared, byte-for-byte, with the other native apps and the desktop minimaMail module.
