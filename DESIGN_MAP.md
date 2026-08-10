# Minima Mail — design & architecture map

A build map for the native **Minima Mail** app (see `README.md` for the user-facing overview). On-chain encrypted
messenger; the node is a dumb transport (no Maxima/MDS/server).

## Screens (0.5.0 — email shell, chat heart)

Drawer navigation: **Inbox · Outbox · Sent · Archive · Contacts · Your key · Settings.**

- **Inbox** — email rows (sender+time / subject / snippet; unread = bold + orange dot), a freshness line
  ("Checked block N · Xm ago", pull-to-refresh), FAB → Compose. One row per (contact, subject) thread.
- **Outbox** — outgoing messages not yet on-chain: *Posting…* or *Failed* (+ Retry, which re-seals+re-posts).
- **Sent** — on-chain/confirmed outgoing messages with their block numbers.
- **Compose** — To (paste / QR / contact picker) · optional Subject · Body; attach chips (photo, send funds);
  the delivery hint ("next block, ~1–3 min") is pinned at the bottom.
- **Thread** — bubbles kept: day-grouped text/image/payment bubbles; subject as the title; per-message
  status line under sent bubbles (ring *Posting* → chain *On-chain · blk N* → check *Confirmed*).
- **Contacts / Your key / Settings** — contacts as before; identity QR + backup/restore; Settings holds the
  theme toggle (paper/dark), display name, and "How delivery works".

All glyphs are a custom VectorDrawable set (24-grid, 1.7px rounded strokes) — no emoji or stock icons.

## Identity & crypto (the core)

- Derived once from `vault action:seed` → **HKDF-SHA256** (salt = 32×0, infos `minima-comms-box-v1` /
  `minima-comms-sign-v1`) → **X25519** box keypair + **Ed25519** sign keypair.
- `publicId = 0x` + hex(boxPk 32B) + hex(signPk 32B). Cached locally; invalidated + store wiped on a seed restore.
- Lives in the reusable `com/eurobuddha/comms/` module (`CommsIdentity`, seal/open, sign/verify) — **byte-identical**
  to the sibling native apps and the desktop minimaMail module (interop by construction).

## Wire types (all sealed into coin state port 99 at the CHAINMAIL sentinel `0x434841494E4D41494C`)

`text` · `image` · `payment` · `payaddr-req` · `payaddr-reply`. Each = a `0.000000001` MINIMA coin to the sentinel;
the sealed blob is `crypto_box_seal(recipientBoxPk, signed(payload))`. Inbox = scan the sentinel address +
trial-decrypt with your box key. A peer's pay-address is learned **only** from that peer's own signed messages
(keyed by `publicId`) so it can't be spoofed; a send is gated by `checkaddress` + a mandatory irreversible-confirm.

## Backup format

`{v, salt(16), iv(12), ct}` where `ct = ciphertext‖tag`; key = PBKDF2-HMAC-SHA256 (120k, 256-bit); cipher =
AES-256-GCM. **Byte-compatible with the desktop `mailbackup` module** so backups cross-restore. Public-data-only —
no seed/private key in the file. Passphrases must be ASCII (Android PBEKeySpec is low-byte-per-char).

## Transport safety

Sends are pinned to a signable wallet coin (`fromaddress:`) so anyone-can-spend beacon/sentinel dust can't be
selected and NPE the signer; the payaddr auto-responder is rate-limited (per-peer cooldown + global cap) so forged
requests can't drain coins or WOTS key-uses.

## Design system

`Design` token engine, two palettes behind one token set (`Design.load(theme)`, persisted in DB meta,
`recreate()` on toggle): **light paper default** (warm paper ground `#FAF9F6`, white hairline-bordered
cards, ink text) and the **family dark** (near-black `#0A0A0F`); Minima orange `#F7931A` accent in both;
monospace ids/addresses; QR keeps a white quiet-zone. Status colours: green = on-chain/confirmed,
accent = in-flight, red = failed.
