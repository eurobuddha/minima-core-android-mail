# Graph Report - mail  (2026-08-10)

## Corpus Check
- 27 files · ~20,662 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 378 nodes · 1001 edges · 25 communities (15 shown, 10 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 58 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `a2dabb4c`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- NodeApi
- MainActivity
- .dp
- CommsIdentity
- CommsScanner
- CommsDb
- .roundBg
- MailMessage
- Util
- .compressToFit
- QrUtil.java
- Sodium
- gradlew
- Folder
- Minima Mail (native Android)
- User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly.
- Minima Mail — design & architecture map
- JSONObject
- Bitmap
- FrameLayout
- Handler
- LazySodium
- Uri

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 134 edges
2. `CommsDb` - 35 edges
3. `CommsScanner` - 19 edges
4. `NodeApi` - 13 edges
5. `Folder` - 11 edges
6. `CommsIdentity` - 10 edges
7. `ChatListAdapter` - 9 edges
8. `MessagesAdapter` - 9 edges
9. `LocalEcCryptoProvider` - 9 edges
10. `Util` - 9 edges

## Surprising Connections (you probably didn't know these)
- `CommsScanner` --references--> `CommsDb`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/comms/CommsScanner.java → app/src/main/java/com/eurobuddha/comms/CommsDb.java
- `MainActivity` --references--> `CommsDb`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/mail/MainActivity.java → app/src/main/java/com/eurobuddha/comms/CommsDb.java
- `MainActivity` --references--> `CommsScanner`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/mail/MainActivity.java → app/src/main/java/com/eurobuddha/comms/CommsScanner.java
- `LocalEcCryptoProvider` --implements--> `CryptoProvider`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/comms/LocalEcCryptoProvider.java → app/src/main/java/com/eurobuddha/comms/CryptoProvider.java
- `LocalEcCryptoProvider` --references--> `CommsIdentity`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/comms/LocalEcCryptoProvider.java → app/src/main/java/com/eurobuddha/comms/CommsIdentity.java

## Import Cycles
- None detected.

## Communities (25 total, 10 thin omitted)

### Community 0 - "NodeApi"
Cohesion: 0.09
Nodes (12): CommsTransport, SendCb, CryptoProvider, MailMessage, Cb, Context, Handler, JSONObject (+4 more)

### Community 1 - "MainActivity"
Cohesion: 0.06
Nodes (20): ActivityResultLauncher, AlertDialog, CryptoProvider, NodeApi, MainActivity, AppCompatActivity, Bitmap, BroadcastReceiver (+12 more)

### Community 2 - ".dp"
Cohesion: 0.13
Nodes (6): TextView, LinearLayout, OnClickListener, RecyclerView, SwipeRefreshLayout, View

### Community 3 - "CommsIdentity"
Cohesion: 0.08
Nodes (12): BackupCrypto, SecureRandom, CommsIdentity, LazySodium, Hex, Hkdf, LazySodium, Override (+4 more)

### Community 4 - "CommsScanner"
Cohesion: 0.13
Nodes (4): CommsScanner, CryptoProvider, NodeApi, Listener

### Community 5 - "CommsDb"
Cohesion: 0.08
Nodes (9): CommsDb, Context, MailMessage, Override, JSONArray, JSONObject, SQLiteDatabase, SQLiteOpenHelper (+1 more)

### Community 6 - ".roundBg"
Cohesion: 0.21
Nodes (7): Avatars, Context, FrameLayout, Design, Context, TextView, GradientDrawable

### Community 7 - "MailMessage"
Cohesion: 0.17
Nodes (9): Adapter, ChatListAdapter, MailMessage, Override, MessagesAdapter, Row, NonNull, ViewGroup (+1 more)

### Community 9 - ".compressToFit"
Cohesion: 0.42
Nodes (4): Images, Bitmap, Context, Uri

### Community 12 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 13 - "Folder"
Cohesion: 0.25
Nodes (8): Folder, ARCHIVE, CONTACTS, IDENTITY, INBOX, OUTBOX, SENT, SETTINGS

### Community 16 - "Minima Mail (native Android)"
Cohesion: 0.33
Nodes (5): Build, Features, How it works, Minima Mail (native Android), Releases

### Community 18 - "Minima Mail — design & architecture map"
Cohesion: 0.25
Nodes (7): Backup format, Design system, Identity & crypto (the core), Minima Mail — design & architecture map, Screens (0.5.0 — email shell, chat heart), Transport safety, Wire types (all sealed into coin state port 99 at the CHAINMAIL sentinel `0x434841494E4D41494C`)

## Knowledge Gaps
- **18 isolated node(s):** `Screens (0.5.0 — email shell, chat heart)`, `Identity & crypto (the core)`, `Wire types (all sealed into coin state port 99 at the CHAINMAIL sentinel `0x434841494E4D41494C`)`, `Backup format`, `Transport safety` (+13 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **10 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `.dp`, `CommsScanner`, `CommsDb`, `.roundBg`, `MailMessage`, `Folder`?**
  _High betweenness centrality (0.356) - this node is a cross-community bridge._
- **Why does `CommsDb` connect `CommsDb` to `MainActivity`, `.dp`, `CommsScanner`?**
  _High betweenness centrality (0.120) - this node is a cross-community bridge._
- **Why does `LocalEcCryptoProvider` connect `CommsIdentity` to `NodeApi`?**
  _High betweenness centrality (0.110) - this node is a cross-community bridge._
- **What connects `Screens (0.5.0 — email shell, chat heart)`, `Identity & crypto (the core)`, `Wire types (all sealed into coin state port 99 at the CHAINMAIL sentinel `0x434841494E4D41494C`)` to the rest of the system?**
  _18 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `NodeApi` be split into smaller, more focused modules?**
  _Cohesion score 0.08888888888888889 - nodes in this community are weakly interconnected._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.06400409626216078 - nodes in this community are weakly interconnected._
- **Should `.dp` be split into smaller, more focused modules?**
  _Cohesion score 0.13376623376623376 - nodes in this community are weakly interconnected._