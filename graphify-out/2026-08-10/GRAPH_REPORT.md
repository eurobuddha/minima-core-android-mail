# Graph Report - mail  (2026-08-09)

## Corpus Check
- 28 files · ~18,209 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 322 nodes · 873 edges · 18 communities (13 shown, 5 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 72 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `43aaef8b`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- CommsScanner
- MainActivity
- .buildConversation
- .doImport
- MailMessage
- CommsDb
- .roundBg
- .onBindViewHolder
- Util
- .compressToFit
- QrUtil.java
- Sodium
- gradlew
- Emojis
- Minima Mail (native Android)
- User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly.

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 111 edges
2. `CommsDb` - 30 edges
3. `MailMessage` - 26 edges
4. `CommsScanner` - 18 edges
5. `NodeApi` - 18 edges
6. `CommsIdentity` - 13 edges
7. `Cb` - 12 edges
8. `CryptoProvider` - 10 edges
9. `SendCb` - 9 edges
10. `LocalEcCryptoProvider` - 9 edges

## Surprising Connections (you probably didn't know these)
- `CommsScanner` --references--> `CommsDb`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/comms/CommsScanner.java → app/src/main/java/com/eurobuddha/comms/CommsDb.java
- `MainActivity` --references--> `CommsDb`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/mail/MainActivity.java → app/src/main/java/com/eurobuddha/comms/CommsDb.java
- `MainActivity` --references--> `CommsIdentity`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/mail/MainActivity.java → app/src/main/java/com/eurobuddha/comms/CommsIdentity.java
- `MainActivity` --references--> `CommsScanner`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/mail/MainActivity.java → app/src/main/java/com/eurobuddha/comms/CommsScanner.java
- `LocalEcCryptoProvider` --implements--> `CryptoProvider`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/comms/LocalEcCryptoProvider.java → app/src/main/java/com/eurobuddha/comms/CryptoProvider.java

## Import Cycles
- None detected.

## Communities (18 total, 5 thin omitted)

### Community 0 - "CommsScanner"
Cohesion: 0.09
Nodes (11): CommsScanner, Listener, CryptoProvider, Cb, Context, Handler, JSONObject, NodeApi (+3 more)

### Community 1 - "MainActivity"
Cohesion: 0.08
Nodes (15): ActivityResultLauncher, AlertDialog, Bitmap, FrameLayout, Handler, LazySodium, MainActivity, AppCompatActivity (+7 more)

### Community 2 - ".buildConversation"
Cohesion: 0.17
Nodes (5): TextView, LinearLayout, OnClickListener, RecyclerView, View

### Community 3 - ".doImport"
Cohesion: 0.08
Nodes (11): BackupCrypto, SecureRandom, CommsIdentity, LazySodium, Hex, Hkdf, LazySodium, Override (+3 more)

### Community 4 - "MailMessage"
Cohesion: 0.13
Nodes (7): JSONObject, CommsTransport, SendCb, MailMessage, SecureRandom, MailText, JSONArray

### Community 5 - "CommsDb"
Cohesion: 0.11
Nodes (5): CommsDb, Context, Override, SQLiteDatabase, SQLiteOpenHelper

### Community 6 - ".roundBg"
Cohesion: 0.21
Nodes (7): Avatars, Context, FrameLayout, Design, Context, TextView, GradientDrawable

### Community 7 - ".onBindViewHolder"
Cohesion: 0.14
Nodes (8): Adapter, ChatListAdapter, Override, MessagesAdapter, Row, NonNull, ViewGroup, ViewHolder

### Community 9 - ".compressToFit"
Cohesion: 0.42
Nodes (4): Images, Bitmap, Context, Uri

### Community 12 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 16 - "Minima Mail (native Android)"
Cohesion: 0.33
Nodes (5): Build, Features, How it works, Minima Mail (native Android), Releases

## Knowledge Gaps
- **5 isolated node(s):** `How it works`, `Features`, `Build`, `Releases`, `RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions.`
  These have ≤1 connection - possible missing edges or undocumented components.
- **5 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `CommsScanner`, `.buildConversation`, `.doImport`, `MailMessage`, `CommsDb`, `.onBindViewHolder`?**
  _High betweenness centrality (0.399) - this node is a cross-community bridge._
- **Why does `CommsDb` connect `CommsDb` to `CommsScanner`, `MainActivity`, `.buildConversation`, `.doImport`, `MailMessage`, `.onBindViewHolder`?**
  _High betweenness centrality (0.107) - this node is a cross-community bridge._
- **Why does `NodeApi` connect `CommsScanner` to `MainActivity`, `MailMessage`, `.onBindViewHolder`?**
  _High betweenness centrality (0.072) - this node is a cross-community bridge._
- **What connects `How it works`, `Features`, `Build` to the rest of the system?**
  _5 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `CommsScanner` be split into smaller, more focused modules?**
  _Cohesion score 0.09176788124156546 - nodes in this community are weakly interconnected._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.07510204081632653 - nodes in this community are weakly interconnected._
- **Should `.doImport` be split into smaller, more focused modules?**
  _Cohesion score 0.08478513356562137 - nodes in this community are weakly interconnected._