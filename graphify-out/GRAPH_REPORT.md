# Graph Report - mail  (2026-08-10)

## Corpus Check
- 28 files · ~21,688 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 395 nodes · 962 edges · 26 communities (15 shown, 11 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 19 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `fd2817a0`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- NodeApi
- MainActivity
- .dp
- CommsIdentity
- CommsScanner
- CommsDb
- Design
- MailMessage
- Util
- .compressToFit
- MainActivity.java
- MailMessage
- gradlew
- Override
- Minima Mail (native Android)
- User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly.
- Minima Mail — design & architecture map
- JSONObject
- Bitmap
- FrameLayout
- Handler
- LazySodium
- Uri
- TextView

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 135 edges
2. `CommsDb` - 30 edges
3. `CommsScanner` - 19 edges
4. `Folder` - 11 edges
5. `NodeApi` - 11 edges
6. `CommsIdentity` - 10 edges
7. `SendCb` - 9 edges
8. `ChatListAdapter` - 9 edges
9. `MessagesAdapter` - 9 edges
10. `LocalEcCryptoProvider` - 9 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `CommsScanner`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/mail/MainActivity.java → app/src/main/java/com/eurobuddha/comms/CommsScanner.java
- `LocalEcCryptoProvider` --references--> `CommsIdentity`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/comms/LocalEcCryptoProvider.java → app/src/main/java/com/eurobuddha/comms/CommsIdentity.java
- `LocalEcCryptoProvider` --implements--> `CryptoProvider`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/comms/LocalEcCryptoProvider.java → app/src/main/java/com/eurobuddha/comms/CryptoProvider.java

## Import Cycles
- None detected.

## Communities (26 total, 11 thin omitted)

### Community 0 - "NodeApi"
Cohesion: 0.22
Nodes (7): Cb, Context, Handler, JSONObject, NodeApi, PairingListener, MinimaAPI

### Community 1 - "MainActivity"
Cohesion: 0.09
Nodes (7): AlertDialog, MainActivity, CommsIdentity, EditText, LruCache, ScanOptions, Uri

### Community 2 - ".dp"
Cohesion: 0.15
Nodes (7): FrameLayout, ImageView, LinearLayout, OnClickListener, RecyclerView, TextView, View

### Community 3 - "CommsIdentity"
Cohesion: 0.06
Nodes (14): BackupCrypto, SecureRandom, CommsIdentity, LazySodium, CryptoProvider, Hex, Hkdf, LazySodium (+6 more)

### Community 4 - "CommsScanner"
Cohesion: 0.09
Nodes (16): CommsScanner, CommsDb, CryptoProvider, NodeApi, Listener, CommsTransport, CryptoProvider, MailMessage (+8 more)

### Community 5 - "CommsDb"
Cohesion: 0.09
Nodes (6): CommsDb, Context, MailMessage, Override, SQLiteDatabase, SQLiteOpenHelper

### Community 6 - "Design"
Cohesion: 0.20
Nodes (7): Avatars, Context, FrameLayout, Design, Context, TextView, GradientDrawable

### Community 7 - "MailMessage"
Cohesion: 0.07
Nodes (18): Adapter, ChatListAdapter, Folder, ARCHIVE, CONTACTS, IDENTITY, INBOX, OUTBOX (+10 more)

### Community 9 - ".compressToFit"
Cohesion: 0.42
Nodes (4): Images, Bitmap, Context, Uri

### Community 10 - "MainActivity.java"
Cohesion: 0.08
Nodes (17): ActivityResultLauncher, Bitmap, QrUtil, LazySodium, Sodium, CommsDb, CryptoProvider, NodeApi (+9 more)

### Community 12 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 16 - "Minima Mail (native Android)"
Cohesion: 0.33
Nodes (5): Build, Features, How it works, Minima Mail (native Android), Releases

### Community 18 - "Minima Mail — design & architecture map"
Cohesion: 0.25
Nodes (7): Backup format, Design system, Identity & crypto (the core), Minima Mail — design & architecture map, Screens (0.5.0 — email shell, chat heart), Transport safety, Wire types (all sealed into coin state port 99 at the CHAINMAIL sentinel `0x434841494E4D41494C`)

## Knowledge Gaps
- **18 isolated node(s):** `How it works`, `Features`, `Build`, `Releases`, `INBOX` (+13 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **11 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `MainActivity.java`, `.dp`, `CommsScanner`, `MailMessage`?**
  _High betweenness centrality (0.290) - this node is a cross-community bridge._
- **Why does `CommsScanner` connect `CommsScanner` to `MainActivity`, `MainActivity.java`?**
  _High betweenness centrality (0.058) - this node is a cross-community bridge._
- **What connects `How it works`, `Features`, `Build` to the rest of the system?**
  _18 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.08636363636363636 - nodes in this community are weakly interconnected._
- **Should `.dp` be split into smaller, more focused modules?**
  _Cohesion score 0.14901960784313725 - nodes in this community are weakly interconnected._
- **Should `CommsIdentity` be split into smaller, more focused modules?**
  _Cohesion score 0.0636734693877551 - nodes in this community are weakly interconnected._
- **Should `CommsScanner` be split into smaller, more focused modules?**
  _Cohesion score 0.08658536585365853 - nodes in this community are weakly interconnected._