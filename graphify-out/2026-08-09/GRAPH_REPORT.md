# Graph Report - mail  (2026-08-09)

## Corpus Check
- 28 files · ~18,209 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 316 nodes · 868 edges · 18 communities (13 shown, 5 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 72 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `9ba20dae`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- CommsScanner
- MainActivity
- .buildConversation
- CommsIdentity
- MailMessage
- CommsDb
- .roundBg
- LinearLayout
- Util
- .compressToFit
- QrUtil.java
- Sodium
- gradlew
- Emojis
- BackupCrypto
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
- `CommsScanner` --references--> `CryptoProvider`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/comms/CommsScanner.java → app/src/main/java/com/eurobuddha/comms/CryptoProvider.java
- `MainActivity` --references--> `CommsScanner`  [EXTRACTED]
  app/src/main/java/com/eurobuddha/mail/MainActivity.java → app/src/main/java/com/eurobuddha/comms/CommsScanner.java

## Import Cycles
- None detected.

## Communities (18 total, 5 thin omitted)

### Community 0 - "CommsScanner"
Cohesion: 0.10
Nodes (11): CommsScanner, JSONObject, Listener, Cb, Context, Handler, JSONObject, NodeApi (+3 more)

### Community 1 - "MainActivity"
Cohesion: 0.08
Nodes (14): ActivityResultLauncher, Bitmap, FrameLayout, Handler, LazySodium, Uri, MainActivity, AppCompatActivity (+6 more)

### Community 2 - ".buildConversation"
Cohesion: 0.21
Nodes (4): TextView, OnClickListener, RecyclerView, View

### Community 3 - "CommsIdentity"
Cohesion: 0.09
Nodes (9): CommsIdentity, LazySodium, CryptoProvider, Hex, Hkdf, LazySodium, Override, LocalEcCryptoProvider (+1 more)

### Community 4 - "MailMessage"
Cohesion: 0.12
Nodes (7): AlertDialog, CommsTransport, SendCb, MailMessage, SecureRandom, MailText, Row

### Community 5 - "CommsDb"
Cohesion: 0.10
Nodes (5): CommsDb, Context, Override, SQLiteDatabase, SQLiteOpenHelper

### Community 6 - ".roundBg"
Cohesion: 0.15
Nodes (8): Avatars, Context, FrameLayout, Design, Context, TextView, Drawable, GradientDrawable

### Community 7 - "LinearLayout"
Cohesion: 0.19
Nodes (8): Adapter, ChatListAdapter, Override, MessagesAdapter, LinearLayout, NonNull, ViewGroup, ViewHolder

### Community 9 - ".compressToFit"
Cohesion: 0.42
Nodes (4): Images, Bitmap, Context, Uri

### Community 12 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 16 - "BackupCrypto"
Cohesion: 0.39
Nodes (3): BackupCrypto, SecureRandom, SecretKey

## Knowledge Gaps
- **1 isolated node(s):** `RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions.`
  These have ≤1 connection - possible missing edges or undocumented components.
- **5 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `CommsScanner`, `.buildConversation`, `CommsIdentity`, `MailMessage`, `CommsDb`, `.roundBg`, `LinearLayout`?**
  _High betweenness centrality (0.415) - this node is a cross-community bridge._
- **Why does `CommsDb` connect `CommsDb` to `CommsScanner`, `MainActivity`, `.buildConversation`?**
  _High betweenness centrality (0.111) - this node is a cross-community bridge._
- **Why does `NodeApi` connect `CommsScanner` to `MainActivity`, `MailMessage`, `LinearLayout`?**
  _High betweenness centrality (0.075) - this node is a cross-community bridge._
- **What connects `RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions.` to the rest of the system?**
  _1 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `CommsScanner` be split into smaller, more focused modules?**
  _Cohesion score 0.09581646423751687 - nodes in this community are weakly interconnected._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.08200290275761973 - nodes in this community are weakly interconnected._
- **Should `CommsIdentity` be split into smaller, more focused modules?**
  _Cohesion score 0.09411764705882353 - nodes in this community are weakly interconnected._