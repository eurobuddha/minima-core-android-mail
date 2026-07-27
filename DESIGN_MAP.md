# Minima Mail — design & architecture map

A build map for the native **Minima Mail** app (see `README.md` for the user-facing overview). On-chain encrypted
messenger; the node is a dumb transport (no Maxima/MDS/server).

## Screens

- **Inbox** — list of conversations (latest message + unread badge), sorted by recency; excludes archived. Tap → Thread.
- **Thread** — a conversation: day-grouped message bubbles (text / image / payment), a composer (`＋` → text, image,
  send-funds), and a thread `⋮` menu (rename · archive · delete).
- **Contacts** — known `publicId`s with names; right-click/long-press to rename or delete; **＋ add** via QR scan or
  pasted id.
- **Archived** — archived conversations (kept off the inbox).
- **Settings / Help** — identity (your `publicId` + QR), passphrase **backup / restore**, "how it works".

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

`MailDesign` token engine (dark default + light toggle): near-black ground, hairline-bordered cards, accent for CTAs;
bundled sans + mono; monospace ids/addresses; QR keeps a white quiet-zone.
