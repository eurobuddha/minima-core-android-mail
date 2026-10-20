# Graph Report - apks/mail  (2026-09-04)

## Corpus Check
- 30 files · ~22,059 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 377 nodes · 1099 edges · 19 communities (16 shown, 3 thin omitted)
- Extraction: 91% EXTRACTED · 9% INFERRED · 0% AMBIGUOUS · INFERRED: 94 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `eb85264b`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- BackupCrypto
- MainActivity
- .dp
- MainActivity.java
- NodeApi
- CommsDb
- android.content.Context
- .onBindViewHolder
- .imageBubble
- gradlew
- Minima Mail (native Android)
- User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly.
- Minima Mail — design & architecture map
- .sendImage
- Folder
- install.sh
- pre-commit

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 135 edges
2. `CommsDb` - 35 edges
3. `MailMessage` - 32 edges
4. `NodeApi` - 20 edges
5. `CommsScanner` - 19 edges
6. `Cb` - 15 edges
7. `CommsIdentity` - 13 edges
8. `Folder` - 11 edges
9. `CryptoProvider` - 10 edges
10. `LocalEcCryptoProvider` - 10 edges

## Surprising Connections (you probably didn't know these)
- `CommsScanner` --references--> `CommsDb`  [EXTRACTED]
  apks/mail/app/src/main/java/com/eurobuddha/comms/CommsScanner.java → apks/mail/app/src/main/java/com/eurobuddha/comms/CommsDb.java
- `MainActivity` --references--> `CommsDb`  [EXTRACTED]
  apks/mail/app/src/main/java/com/eurobuddha/mail/MainActivity.java → apks/mail/app/src/main/java/com/eurobuddha/comms/CommsDb.java
- `MainActivity` --references--> `CommsIdentity`  [EXTRACTED]
  apks/mail/app/src/main/java/com/eurobuddha/mail/MainActivity.java → apks/mail/app/src/main/java/com/eurobuddha/comms/CommsIdentity.java
- `CommsScanner` --references--> `CryptoProvider`  [EXTRACTED]
  apks/mail/app/src/main/java/com/eurobuddha/comms/CommsScanner.java → apks/mail/app/src/main/java/com/eurobuddha/comms/CryptoProvider.java
- `MainActivity` --references--> `CommsScanner`  [EXTRACTED]
  apks/mail/app/src/main/java/com/eurobuddha/mail/MainActivity.java → apks/mail/app/src/main/java/com/eurobuddha/comms/CommsScanner.java

## Import Cycles
- None detected.

## Communities (19 total, 3 thin omitted)

### Community 0 - "BackupCrypto"
Cohesion: 0.27
Nodes (4): BackupCrypto, MailText, java.security.SecureRandom, javax.crypto.SecretKey

### Community 1 - "MainActivity"
Cohesion: 0.10
Nodes (5): android.widget.EditText, android.widget.TextView, MainActivity, LruCache, ScanOptions

### Community 2 - ".dp"
Cohesion: 0.17
Nodes (10): android.view.View, android.widget.ImageView, android.widget.LinearLayout, FrameLayout, TextView, EditText, LinearLayout, OnClickListener (+2 more)

### Community 3 - "MainActivity.java"
Cohesion: 0.06
Nodes (18): android.content.BroadcastReceiver, android.graphics.Canvas, android.os.Bundle, androidx.activity.result.ActivityResultLauncher, androidx.appcompat.app.AppCompatActivity, androidx.drawerlayout.widget.DrawerLayout, androidx.swiperefreshlayout.widget.SwipeRefreshLayout, CommsIdentity (+10 more)

### Community 4 - "NodeApi"
Cohesion: 0.06
Nodes (14): android.os.Handler, CommsScanner, Listener, Cb, NodeApi, PairingListener, Pinned, SendPin (+6 more)

### Community 5 - "CommsDb"
Cohesion: 0.08
Nodes (5): android.database.sqlite.SQLiteDatabase, android.database.sqlite.SQLiteOpenHelper, CommsDb, Override, MailMessage

### Community 6 - "android.content.Context"
Cohesion: 0.18
Nodes (8): android.content.Context, android.graphics.drawable.GradientDrawable, android.widget.FrameLayout, Avatars, FrameLayout, Design, TextView, GradientDrawable

### Community 7 - ".onBindViewHolder"
Cohesion: 0.14
Nodes (9): Adapter, android.view.ViewGroup, androidx.annotation.NonNull, androidx.recyclerview.widget.RecyclerView, ChatListAdapter, Override, MessagesAdapter, Row (+1 more)

### Community 12 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 16 - "Minima Mail (native Android)"
Cohesion: 0.33
Nodes (5): Build, Features, How it works, Minima Mail (native Android), Releases

### Community 17 - "User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly."
Cohesion: 0.50
Nodes (3): RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions., User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly., Versioning guardrail — every code change ships with a version bump

### Community 18 - "Minima Mail — design & architecture map"
Cohesion: 0.25
Nodes (7): Backup format, Design system, Identity & crypto (the core), Minima Mail — design & architecture map, Screens (0.5.0 — email shell, chat heart), Transport safety, Wire types (all sealed into coin state port 99 at the CHAINMAIL sentinel `0x434841494E4D41494C`)

### Community 26 - ".sendImage"
Cohesion: 0.10
Nodes (8): android.graphics.Bitmap, android.net.Uri, androidx.appcompat.app.AlertDialog, CommsTransport, SendCb, CryptoProvider, Images, QrUtil

### Community 28 - "Folder"
Cohesion: 0.25
Nodes (8): Folder, ARCHIVE, CONTACTS, IDENTITY, INBOX, OUTBOX, SENT, SETTINGS

## Knowledge Gaps
- **20 isolated node(s):** `install.sh script`, `INBOX`, `OUTBOX`, `SENT`, `ARCHIVE` (+15 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **3 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `.dp`, `MainActivity.java`, `NodeApi`, `CommsDb`, `android.content.Context`, `.onBindViewHolder`, `.imageBubble`, `.sendImage`, `Folder`?**
  _High betweenness centrality (0.447) - this node is a cross-community bridge._
- **Why does `CommsDb` connect `CommsDb` to `MainActivity`, `MainActivity.java`, `NodeApi`, `android.content.Context`, `.sendImage`?**
  _High betweenness centrality (0.097) - this node is a cross-community bridge._
- **Why does `NodeApi` connect `NodeApi` to `MainActivity`, `MainActivity.java`, `android.content.Context`, `.onBindViewHolder`, `.sendImage`?**
  _High betweenness centrality (0.086) - this node is a cross-community bridge._
- **What connects `install.sh script`, `INBOX`, `OUTBOX` to the rest of the system?**
  _20 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.10460992907801418 - nodes in this community are weakly interconnected._
- **Should `MainActivity.java` be split into smaller, more focused modules?**
  _Cohesion score 0.05878084179970972 - nodes in this community are weakly interconnected._
- **Should `NodeApi` be split into smaller, more focused modules?**
  _Cohesion score 0.06464646464646465 - nodes in this community are weakly interconnected._