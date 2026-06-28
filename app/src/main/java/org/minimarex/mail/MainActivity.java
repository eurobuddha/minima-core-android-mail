package org.minimarex.mail;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.goterl.lazysodium.LazySodium;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.comms.Avatars;
import org.minimarex.comms.BackupCrypto;
import org.minimarex.comms.CommsDb;
import org.minimarex.comms.CommsIdentity;
import org.minimarex.comms.CommsScanner;
import org.minimarex.comms.CommsTransport;
import org.minimarex.comms.CryptoProvider;
import org.minimarex.comms.Hex;
import org.minimarex.comms.LocalEcCryptoProvider;
import org.minimarex.comms.MailMessage;
import org.minimarex.comms.MailText;
import org.minimarex.comms.NodeApi;
import org.minimarex.comms.QrUtil;
import org.minimarex.comms.Sodium;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Minima Mail — iMessage-style native encrypted on-chain messenger. */
public class MainActivity extends AppCompatActivity {

    private static final String CH = "mail";

    private LazySodium ls;
    private NodeApi node;
    private CommsDb db;
    private CryptoProvider crypto;
    private CommsIdentity identity;
    private CommsScanner scanner;
    private String myId, myName = "", myPayaddr = "";
    private boolean paired = false;
    private boolean modalOpen = false;   // suppress background screen rebuilds while a dialog is open
    private int chainBlock = 0;
    private final java.util.HashMap<String, Runnable> pendingPay = new java.util.HashMap<>();
    private final java.util.HashSet<String> payaddrAsked = new java.util.HashSet<>();
    private EditText openPayAddrField;   // the address field of an open Send-funds sheet (for live fill)
    private String openPayContact;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private BroadcastReceiver notifyReceiver;

    private LinearLayout root, pairingBanner;
    private FrameLayout container;
    private final ArrayDeque<View> stack = new ArrayDeque<>();
    private int insetTop = 0, insetBottom = 0;
    private RecyclerView currentMessages;   // for scroll-to-bottom on keyboard

    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<ScanOptions> scanLauncher;
    private EditText pendingScanTarget;

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ls = Sodium.get();
        db = new CommsDb(this);
        myName = db.getMeta("myname", "");
        myPayaddr = db.getMeta("mypayaddr", "");

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        container = new FrameLayout(this);
        root.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyInsets();
        ensureChannel();
        requestNotifPermission();

        exportLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                uri -> { if (uri != null) promptBackupPass(uri, true); });
        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) promptBackupPass(uri, false); });
        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null && pendingScanTarget != null) {
                pendingScanTarget.setText(acceptKeyShare(result.getContents()));
            }
        });

        showInbox();

        node = new NodeApi(this, this::onPaired);
        notifyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    String event = new JSONObject(i.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA)).optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) { fetchBlock(); requestScan(); }
                } catch (Exception ignored) {}
            }
        };
        ContextCompat.registerReceiver(this, notifyReceiver,
                new IntentFilter(MinimaAPIMessages.MINIMA_API_NOTIFY), ContextCompat.RECEIVER_EXPORTED);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (notifyReceiver != null) try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }

    @Override public void onBackPressed() { if (stack.size() > 1) pop(); else super.onBackPressed(); }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            insetTop = bars.top;
            insetBottom = Math.max(bars.bottom, ime.bottom);
            root.setPadding(0, insetTop, 0, insetBottom);
            if (ime.bottom > 0 && currentMessages != null && currentMessages.getAdapter() != null) {
                currentMessages.scrollToPosition(Math.max(0, currentMessages.getAdapter().getItemCount() - 1));
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    // ---- navigation ----

    private void push(View v) { stack.push(v); showTop(); }
    private void pop() { if (stack.size() > 1) { stack.pop(); showTop(); } }
    private void showInbox() { stack.clear(); stack.push(buildInbox()); showTop(); }
    private void showTop() {
        currentMessages = null;
        container.removeAllViews();
        container.addView(stack.peek());
    }
    private void refreshTop(View rebuilt) { stack.pop(); stack.push(rebuilt); showTop(); }

    // ---- pairing + identity ----

    private void onPaired(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) {
            if (chainBlock == 0) fetchBlock();
            fetchMyPayaddr();
            if (crypto == null) setupIdentity(); else requestScan();
        }
    }

    /** Cache my Minima receiving address (piggybacked on outgoing messages so contacts can pay me). */
    private void fetchMyPayaddr() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) {
                    String a = r.optString("miniaddress", r.optString("address", ""));   // prefer Mx
                    if (!a.isEmpty()) { myPayaddr = a; db.setMeta("mypayaddr", a); }
                }
            }
            @Override public void onError(String m) {}
        });
    }

    private void fetchBlock() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) try { chainBlock = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
                if (chainBlock > 0) io.execute(() -> { db.markConfirmed(chainBlock); ui.post(() -> { if (!modalOpen && stack.size() == 1) refreshTop(buildInbox()); }); });
            }
            @Override public void onError(String m) {}
        });
    }

    private void setupIdentity() {
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (ikm.isEmpty()) askForSeed(); else deriveIdentity(ikm);
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) { pairingBanner.setVisibility(View.VISIBLE); return; }
                askForSeed();
            }
        });
    }

    private void deriveIdentity(final String ikm) {
        io.execute(() -> {
            try {
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                CommsIdentity id = CommsIdentity.fromSeed(ls, seed);
                ui.post(() -> { adoptIdentity(id); refreshTop(buildInbox()); requestScan(); });
            } catch (Exception e) { ui.post(() -> toast("Identity error: " + e.getMessage())); }
        });
    }

    private void adoptIdentity(CommsIdentity id) {
        identity = id;
        crypto = new LocalEcCryptoProvider(ls, id);
        myId = id.publicId();
        scanner = new CommsScanner(node, crypto, db, myId, new CommsScanner.Listener() {
            @Override public void onDone(boolean ok, int newCount) { onScanDone(ok, newCount); }
            @Override public void onContactPayaddrUpdated(String publicId) {
                ui.post(() -> {
                    if (openPayAddrField != null && publicId.equals(openPayContact)) {
                        String a = db.contactPayaddr(publicId);
                        if (a != null && openPayAddrField.getText().toString().trim().isEmpty()) openPayAddrField.setText(a);
                    }
                    Runnable r = pendingPay.remove(publicId); if (r != null) r.run();
                });
            }
        });
        fetchMyPayaddr();   // cache my receiving address NOW, so every message I send carries it
    }

    /** Fire a one-time address request to a contact we don't have an address for yet (so it's ready by pay time). */
    private void proactivePayaddr(String otherKey) {
        if (crypto == null || otherKey == null) return;
        if (db.contactPayaddr(otherKey) != null) return;
        if (!payaddrAsked.add(otherKey)) return;   // at most once per contact per session (avoid coin spam)
        sendPayaddrReq(otherKey);
    }

    /** What I share as my "Mail key": encryption key + my Mx receiving address, so contacts can pay me. */
    private String keyShare() {
        return (myPayaddr == null || myPayaddr.isEmpty()) ? myId : myId + "|" + myPayaddr;
    }

    /** Accept a shared key that may be "mailkey|payaddr": store the address if present, return the mail key. */
    private String acceptKeyShare(String shared) {
        if (shared == null) return "";
        shared = shared.trim();
        int bar = shared.indexOf('|');
        if (bar > 0) {
            String key = shared.substring(0, bar).trim();
            String addr = shared.substring(bar + 1).trim();
            if (CommsIdentity.isValidPublicId(key) && looksLikeMinimaAddress(addr)) db.setContactPayaddr(key, addr);
            return key;
        }
        return shared;
    }

    private void askForSeed() {
        final EditText in = input("Your Minima seed phrase (any words / format)");
        in.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("Create your Mail identity")
                .setMessage("Your Mail key is derived from your Minima seed (so it's recoverable). Paste your seed phrase once — it's used only to derive your key and is never stored.")
                .setView(in)
                .setPositiveButton("Create", (d, w) -> { String s = in.getText().toString().trim(); if (!s.isEmpty()) deriveIdentity(s); })
                .setNegativeButton("Restore from backup instead", (d, w) -> importLauncher.launch(new String[]{"application/json", "*/*"}))
                .show();
    }

    // ---- scanning ----

    private void requestScan() { if (crypto != null && scanner != null) scanner.scan(chainBlock); }

    private void onScanDone(boolean ok, int newCount) {
        ui.post(() -> {
            if (newCount > 0) {
                notifyNew(newCount);
                if (modalOpen) return;   // don't tear down an open dialog (the pay sheet)
                View top = stack.peek();
                if (top != null && top.getTag() instanceof String) {
                    // a conversation is open — refresh it
                    refreshTop(buildConversation((String) top.getTag()));
                } else if (stack.size() == 1) {
                    refreshTop(buildInbox());
                }
            }
        });
    }

    // ---- INBOX (chat list) ----

    private View buildInbox() {
        FrameLayout screen = new FrameLayout(this);
        LinearLayout col = column();

        LinearLayout head = header("Messages", false);
        head.addView(iconBtn("⋮", this::showMenu));
        col.addView(head);

        TextView ver = new TextView(this);
        ver.setText("Minima Mail v" + BuildConfig.VERSION_NAME);
        ver.setTextColor(Design.DIM2); ver.setTextSize(10f);
        ver.setPadding(dp(16), dp(2), dp(16), dp(4));
        col.addView(ver);

        if (crypto == null) {
            TextView s = new TextView(this);
            s.setText(paired ? "Connecting to your node…" : "Enable Minima Mail in Minima Core → Apps.");
            s.setTextColor(Design.DIM);
            s.setTextSize(13f);
            s.setPadding(dp(16), dp(8), dp(16), dp(8));
            col.addView(s);
        }

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        java.util.Set<String> archived = db.archivedSet();
        List<MailMessage> threads = new ArrayList<>();
        for (MailMessage t : db.threads()) if (!archived.contains(t.hashref)) threads.add(t);
        ChatListAdapter adapter = new ChatListAdapter(threads, false);
        rv.setAdapter(adapter);
        attachSwipe(rv, adapter);
        rv.setClipToPadding(false);
        rv.setPadding(0, 0, 0, dp(88));
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        screen.addView(col);

        // FAB → new chat
        TextView fab = new TextView(this);
        fab.setText("✏");
        fab.setTextSize(22f);
        fab.setTextColor(Design.ON_ACCENT);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable fb = new GradientDrawable();
        fb.setShape(GradientDrawable.OVAL);
        fb.setColor(Design.ACCENT);
        fab.setBackground(fb);
        fab.setOnClickListener(v -> push(buildNewChat()));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(60), dp(60));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.setMargins(0, 0, dp(20), dp(24));
        screen.addView(fab, flp);
        return screen;
    }

    private class ChatListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<MailMessage> data;
        final boolean archivedView;
        ChatListAdapter(List<MailMessage> d, boolean arch) { data = d; archivedView = arch; }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) {};
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            MailMessage t = data.get(pos);
            String other = myId != null && myId.equals(t.frompublickey) ? t.topublickey : t.frompublickey;
            String name = nameFor(other);
            LinearLayout row = (LinearLayout) h.itemView;
            row.removeAllViews();
            row.addView(avatar(other, name, 48));

            LinearLayout mid = new LinearLayout(MainActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(12), 0, dp(8), 0);
            mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView nm = new TextView(MainActivity.this);
            nm.setText(name); nm.setTextColor(Design.TEXT); nm.setTextSize(16f); nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setMaxLines(1);
            TextView snip = new TextView(MainActivity.this);
            boolean unread = t.incoming && !t.read;
            snip.setText((t.incoming ? "" : "You: ") + oneLine(t.message));
            snip.setTextColor(unread ? Design.TEXT : Design.DIM);
            snip.setTextSize(13f); snip.setMaxLines(1);
            mid.addView(nm); mid.addView(snip);
            row.addView(mid);

            LinearLayout right = new LinearLayout(MainActivity.this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            TextView time = new TextView(MainActivity.this);
            time.setText(rel(t.date)); time.setTextColor(Design.DIM2); time.setTextSize(11f); time.setGravity(Gravity.END);
            right.addView(time);
            if (unread) {
                TextView dot = new TextView(MainActivity.this);
                dot.setText(" "); dot.setWidth(dp(10)); dot.setHeight(dp(10));
                GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(Design.ACCENT);
                dot.setBackground(g);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
                dlp.topMargin = dp(6); dlp.gravity = Gravity.END;
                dot.setLayoutParams(dlp);
                right.addView(dot);
            }
            row.addView(right);
            row.setOnClickListener(v -> openConversation(other));
            row.setOnLongClickListener(v -> { showThreadActions(t, archivedView); return true; });
        }

        @Override public int getItemCount() { return data.size(); }
    }

    // ---- swipe + long-press (archive / delete) ----

    private void attachSwipe(final RecyclerView rv, final ChatListAdapter adapter) {
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(RecyclerView r, RecyclerView.ViewHolder a, RecyclerView.ViewHolder b) { return false; }

            @Override public void onSwiped(RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getBindingAdapterPosition();
                if (pos < 0 || pos >= adapter.data.size()) return;
                final String hashref = adapter.data.get(pos).hashref;
                if (dir == ItemTouchHelper.LEFT) {                 // archive / unarchive
                    io.execute(() -> db.setArchived(hashref, !adapter.archivedView));
                    adapter.data.remove(pos); adapter.notifyItemRemoved(pos);
                    toast(adapter.archivedView ? "Unarchived" : "Archived");
                } else {                                            // delete (confirm; restore row if cancelled)
                    new AlertDialog.Builder(MainActivity.this).setMessage("Delete this conversation?")
                            .setPositiveButton("Delete", (d, w) -> { io.execute(() -> db.deleteThread(hashref)); adapter.data.remove(pos); adapter.notifyItemRemoved(pos); })
                            .setNegativeButton("Cancel", (d, w) -> adapter.notifyItemChanged(pos))
                            .setOnCancelListener(d -> adapter.notifyItemChanged(pos)).show();
                }
            }

            @Override public void onChildDraw(Canvas c, RecyclerView r, RecyclerView.ViewHolder vh, float dX, float dY, int as, boolean active) {
                View item = vh.itemView;
                Paint p = new Paint();
                if (dX > 0) {                                       // swipe right → Delete (red, left)
                    p.setColor(Design.RED);
                    c.drawRect(item.getLeft(), item.getTop(), item.getLeft() + dX, item.getBottom(), p);
                    drawSwipeLabel(c, "Delete", item.getLeft() + dp(24), item.getTop(), item.getBottom(), true);
                } else if (dX < 0) {                                // swipe left → Archive (orange, right)
                    p.setColor(Design.ACCENT);
                    c.drawRect(item.getRight() + dX, item.getTop(), item.getRight(), item.getBottom(), p);
                    drawSwipeLabel(c, adapter.archivedView ? "Unarchive" : "Archive", item.getRight() - dp(24), item.getTop(), item.getBottom(), false);
                }
                super.onChildDraw(c, r, vh, dX, dY, as, active);
            }
        }).attachToRecyclerView(rv);
    }

    private void drawSwipeLabel(Canvas c, String text, float x, int top, int bottom, boolean leftAlign) {
        Paint tp = new Paint();
        tp.setColor(0xFFFFFFFF); tp.setTextSize(dp(14)); tp.setAntiAlias(true);
        tp.setTextAlign(leftAlign ? Paint.Align.LEFT : Paint.Align.RIGHT);
        float y = top + (bottom - top) / 2f - (tp.descent() + tp.ascent()) / 2f;
        c.drawText(text, x, y, tp);
    }

    private void showThreadActions(MailMessage t, final boolean archivedView) {
        final String hashref = t.hashref;
        String[] items = {archivedView ? "Unarchive" : "Archive", "Delete"};
        new AlertDialog.Builder(this).setItems(items, (d, w) -> {
            if (w == 0) {
                io.execute(() -> db.setArchived(hashref, !archivedView));
                toast(archivedView ? "Unarchived" : "Archived");
                refreshTop(archivedView ? buildArchived() : buildInbox());
            } else {
                new AlertDialog.Builder(this).setMessage("Delete this conversation?")
                        .setPositiveButton("Delete", (dd, ww) -> { io.execute(() -> db.deleteThread(hashref)); refreshTop(archivedView ? buildArchived() : buildInbox()); })
                        .setNegativeButton("Cancel", null).show();
            }
        }).show();
    }

    private View buildArchived() {
        LinearLayout col = column();
        col.addView(header("Archived", true));
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        java.util.Set<String> archived = db.archivedSet();
        List<MailMessage> threads = new ArrayList<>();
        for (MailMessage t : db.threads()) if (archived.contains(t.hashref)) threads.add(t);
        ChatListAdapter adapter = new ChatListAdapter(threads, true);
        rv.setAdapter(adapter);
        attachSwipe(rv, adapter);
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        if (threads.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("No archived chats."); e.setTextColor(Design.DIM2); e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(64), 0, 0);
            col.addView(e);
        }
        return col;
    }

    private void openConversation(String otherKey) {
        io.execute(() -> {
            db.markThreadRead(MailText.threadKey(myId, otherKey, ""));
            ui.post(() -> push(buildConversation(otherKey)));
        });
    }

    // ---- CONVERSATION ----

    private View buildConversation(final String otherKey) {
        final String hashref = MailText.threadKey(myId == null ? "" : myId, otherKey, "");
        LinearLayout col = column();
        col.setTag(otherKey);   // marks this as an open conversation; onScanDone rebuilds it by otherKey
        proactivePayaddr(otherKey);   // request their receiving address now, so it's ready when you pay

        // top bar: back + avatar + name
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Design.SURFACE);
        head.setPadding(dp(6), dp(8), dp(12), dp(8));
        head.addView(iconBtn("‹", v -> pop()));
        head.addView(avatar(otherKey, nameFor(otherKey), 34));
        TextView nm = new TextView(this);
        nm.setText(nameFor(otherKey)); nm.setTextColor(Design.TEXT); nm.setTextSize(16f); nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setPadding(dp(10), 0, 0, 0);
        nm.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(nm);
        head.addView(iconBtn("⋮", v -> conversationMenu(otherKey, hashref)));
        col.addView(head);

        // messages
        final RecyclerView rv = new RecyclerView(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        List<MailMessage> msgs = db.thread(hashref);
        MessagesAdapter adapter = new MessagesAdapter(msgs);
        rv.setAdapter(adapter);
        rv.setPadding(0, dp(8), 0, dp(8));
        rv.setClipToPadding(false);
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        currentMessages = rv;
        if (!msgs.isEmpty()) rv.scrollToPosition(msgs.size() - 1);

        // composer
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Design.SURFACE);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView pay = new TextView(this);
        pay.setText("＋"); pay.setTextColor(Design.ACCENT); pay.setTextSize(26f); pay.setGravity(Gravity.CENTER);
        pay.setPadding(dp(2), 0, dp(8), 0);
        pay.setOnClickListener(v -> showSendFundsSheet(otherKey));
        bar.addView(pay);
        final EditText box = new EditText(this);
        box.setHint("Message");
        box.setHintTextColor(Design.DIM2);
        box.setTextColor(Design.TEXT);
        box.setTextSize(15f);
        box.setMaxLines(4);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.setBackground(Design.roundBg(this, Design.SURFACE2, 20));
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(box);

        final TextView send = new TextView(this);
        send.setText("▲");
        send.setTextColor(Design.ON_ACCENT);
        send.setTextSize(16f);
        send.setGravity(Gravity.CENTER);
        GradientDrawable sd = new GradientDrawable(); sd.setShape(GradientDrawable.OVAL); sd.setColor(Design.ACCENT);
        send.setBackground(sd);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.leftMargin = dp(8);
        send.setLayoutParams(slp);
        send.setOnClickListener(v -> {
            String text = box.getText().toString();
            doSend(send, otherKey, text, () -> {
                box.setText("");
                List<MailMessage> updated = db.thread(hashref);
                adapter.setData(updated);
                if (!updated.isEmpty()) rv.scrollToPosition(updated.size() - 1);
            });
        });
        bar.addView(send);
        col.addView(bar);
        return col;
    }

    /** A precomputed render row: a message, an optional date header above it, and whether to show a footer. */
    private static class Row {
        final MailMessage m; final String dateHeader; final boolean footer;
        Row(MailMessage m, String dateHeader, boolean footer) { this.m = m; this.dateHeader = dateHeader; this.footer = footer; }
    }

    private class MessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<Row> rows = new ArrayList<>();
        MessagesAdapter(List<MailMessage> msgs) { setData(msgs); }

        void setData(List<MailMessage> msgs) {
            List<Row> out = new ArrayList<>();
            for (int i = 0; i < msgs.size(); i++) {
                MailMessage m = msgs.get(i);
                String header = null;
                if (i == 0 || !sameDay(msgs.get(i - 1).date, m.date)) header = dateLabel(m.date);
                boolean last = i == msgs.size() - 1;
                boolean footer = last
                        || msgs.get(i + 1).incoming != m.incoming
                        || msgs.get(i + 1).date - m.date > 5 * 60 * 1000L
                        || !sameDay(m.date, msgs.get(i + 1).date);
                out.add(new Row(m, header, footer));
            }
            rows = out;
            notifyDataSetChanged();
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout v = new LinearLayout(MainActivity.this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            Row r = rows.get(pos);
            LinearLayout v = (LinearLayout) h.itemView;
            v.removeAllViews();

            if (r.dateHeader != null) {
                TextView chip = new TextView(MainActivity.this);
                chip.setText(r.dateHeader);
                chip.setTextColor(Design.DIM2);
                chip.setTextSize(11f);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(0, dp(10), 0, dp(8));
                v.addView(chip);
            }

            LinearLayout rowWrap = new LinearLayout(MainActivity.this);
            rowWrap.setOrientation(LinearLayout.HORIZONTAL);
            rowWrap.setPadding(dp(12), dp(1), dp(12), dp(1));
            rowWrap.setGravity(r.m.incoming ? Gravity.START : Gravity.END);
            rowWrap.addView("payment".equals(r.m.type) ? paymentBubble(r.m) : textBubble(r.m));
            v.addView(rowWrap);

            if (r.footer) {
                TextView f = new TextView(MainActivity.this);
                String time = clock(r.m.date);
                String status = r.m.incoming ? "" : ("confirmed".equals(r.m.status) ? " · Delivered" : "sent".equals(r.m.status) ? " · Sent" : "");
                f.setText(time + status);
                f.setTextColor(Design.DIM2);
                f.setTextSize(10f);
                f.setPadding(dp(18), dp(2), dp(18), dp(8));
                f.setGravity(r.m.incoming ? Gravity.START : Gravity.END);
                v.addView(f);
            }
        }

        @Override public int getItemCount() { return rows.size(); }
    }

    private void conversationMenu(String otherKey, String hashref) {
        androidx.appcompat.widget.PopupMenu m = new androidx.appcompat.widget.PopupMenu(this, container);
        m.getMenu().add(0, 1, 0, db.contactName(otherKey) == null ? "Add to contacts" : "View contact key");
        m.getMenu().add(0, 2, 1, "Delete conversation");
        m.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) {
                if (db.contactName(otherKey) == null) addContactDialog(otherKey);
                else { copy(otherKey, "Mail key"); toast("Key copied."); }
            } else {
                new AlertDialog.Builder(this).setMessage("Delete this conversation from this device?")
                        .setPositiveButton("Delete", (d, w) -> io.execute(() -> { db.deleteThread(hashref); ui.post(() -> { pop(); refreshTop(buildInbox()); }); }))
                        .setNegativeButton("Cancel", null).show();
            }
            return true;
        });
        m.show();
    }

    // ---- NEW CHAT ----

    private View buildNewChat() {
        LinearLayout col = column();
        col.addView(header("New message", true));
        LinearLayout form = pad(column());

        form.addView(label("To — paste a Mail key, scan, or pick a contact"));
        final EditText to = input("0x… Mail key");
        form.addView(to);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView scan = textButton("⧉ Scan QR");
        scan.setOnClickListener(v -> startScan(to));
        TextView pick = textButton("Contacts");
        pick.setOnClickListener(v -> pickContact(to));
        actions.addView(scan); actions.addView(pick);
        form.addView(actions);

        TextView start = accentButton("Start chat");
        start.setOnClickListener(v -> {
            String k = acceptKeyShare(to.getText().toString());
            if (!CommsIdentity.isValidPublicId(k)) { toast("That doesn't look like a valid Mail key."); return; }
            pop();
            push(buildConversation(k));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        form.addView(start, lp);

        ScrollView sv = new ScrollView(this); sv.addView(form);
        col.addView(sv);
        return col;
    }

    // ---- CONTACTS ----

    private View buildContacts() {
        LinearLayout col = column();
        LinearLayout head = header("Contacts", true);
        head.addView(iconBtn("＋", v -> addContactDialog(null)));
        col.addView(head);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        final List<String[]> cs = db.contacts();
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(10), dp(14), dp(10));
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(row) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                String[] c = cs.get(pos);
                final long id = Long.parseLong(c[0]); final String name = c[1], key = c[2];
                LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
                row.addView(avatar(key, name, 44));
                LinearLayout mid = new LinearLayout(MainActivity.this); mid.setOrientation(LinearLayout.VERTICAL);
                mid.setPadding(dp(12), 0, 0, 0);
                mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView nm = new TextView(MainActivity.this); nm.setText(name); nm.setTextColor(Design.TEXT); nm.setTextSize(15f); nm.setTypeface(Typeface.DEFAULT_BOLD);
                TextView kv = new TextView(MainActivity.this); kv.setText(shortKey(key)); kv.setTextColor(Design.DIM); kv.setTextSize(12f); kv.setTypeface(Typeface.MONOSPACE);
                mid.addView(nm); mid.addView(kv); row.addView(mid);
                row.setOnClickListener(v -> { pop(); push(buildConversation(key)); });
                row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(MainActivity.this).setMessage("Delete contact " + name + "?")
                            .setPositiveButton("Delete", (d, w) -> io.execute(() -> { db.deleteContact(id); ui.post(() -> refreshTop(buildContacts())); }))
                            .setNegativeButton("Cancel", null).show();
                    return true;
                });
            }
            @Override public int getItemCount() { return cs.size(); }
        });
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    // ---- YOUR KEY / IDENTITY ----

    private View buildIdentity() {
        LinearLayout col = column();
        col.addView(header("Your Mail key", true));
        LinearLayout form = pad(column());
        form.setGravity(Gravity.CENTER_HORIZONTAL);

        form.addView(avatar(myId, myName, 72));

        final EditText nm = input("Your display name");
        nm.setText(myName);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(12);
        form.addView(nm, nlp);
        TextView saveName = textButton("Save name");
        saveName.setOnClickListener(v -> { myName = nm.getText().toString().trim(); io.execute(() -> db.setMeta("myname", myName)); toast("Saved."); });
        form.addView(saveName);

        if (myId != null) {
            Bitmap qr = QrUtil.qr(keyShare(), dp(200));
            if (qr != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(qr);
                int s = dp(200);
                LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(s, s);
                qlp.topMargin = dp(16);
                iv.setLayoutParams(qlp);
                iv.setPadding(dp(8), dp(8), dp(8), dp(8));
                iv.setBackgroundColor(0xFFFFFFFF);
                form.addView(iv);
            }
        }
        TextView keyLabel = new TextView(this);
        keyLabel.setText("Have someone scan this, or share your key:");
        keyLabel.setTextColor(Design.DIM); keyLabel.setTextSize(12f); keyLabel.setPadding(0, dp(14), 0, dp(6));
        form.addView(keyLabel);
        TextView key = new TextView(this);
        key.setText(myId == null ? "(connecting to your node…)" : shortKey(myId));
        key.setTextColor(Design.TEXT); key.setTextSize(13f); key.setTypeface(Typeface.MONOSPACE);
        form.addView(key);
        TextView copy = accentButton("Copy my key");
        copy.setOnClickListener(v -> { if (myId != null) { copy(keyShare(), "Mail key + address"); toast("Copied."); } });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        form.addView(copy, clp);

        if (myPayaddr != null && !myPayaddr.isEmpty()) {
            TextView ral = new TextView(this);
            ral.setText("Your Minima receiving address (so people can pay you):");
            ral.setTextColor(Design.DIM); ral.setTextSize(12f); ral.setPadding(0, dp(16), 0, dp(4));
            form.addView(ral);
            TextView ra = new TextView(this);
            ra.setText(shortKey(myPayaddr));
            ra.setTextColor(Design.TEXT); ra.setTextSize(12f); ra.setTypeface(Typeface.MONOSPACE);
            ra.setOnClickListener(v -> { copy(myPayaddr, "Receiving address"); toast("Address copied."); });
            form.addView(ra);
        }

        TextView backup = textButton("Back up identity + messages");
        backup.setOnClickListener(v -> { if (identity == null) toast("Connect to your node first."); else exportLauncher.launch("minima-mail-backup.json"); });
        form.addView(backup);
        TextView restore = textButton("Restore from a backup");
        restore.setOnClickListener(v -> importLauncher.launch(new String[]{"application/json", "*/*"}));
        form.addView(restore);

        ScrollView sv = new ScrollView(this); sv.addView(form);
        col.addView(sv);
        return col;
    }

    private View buildHelp() {
        LinearLayout col = column();
        col.addView(header("Help", true));
        ScrollView sv = new ScrollView(this);
        TextView t = new TextView(this);
        t.setPadding(dp(16), dp(8), dp(16), dp(24));
        t.setTextColor(Design.DIM); t.setTextSize(13f); t.setLineSpacing(dp(4), 1f);
        t.setText("Minima Mail is end-to-end encrypted on-chain messaging.\n\n" +
                "• Your identity is a key derived from your Minima seed — recoverable on any device.\n\n" +
                "• Messages are encrypted on your device and carried in a tiny on-chain coin to a shared address; " +
                "only the recipient can decrypt. Sender, recipient and content are hidden on-chain.\n\n" +
                "• Minima is feeless — a message costs only a negligible 0.000000001 Minima.\n\n" +
                "• This is a native, in-app-encrypted network. It does NOT yet interoperate with the web ChainMail " +
                "MiniDapp (which uses Maxima, absent from this node build). Native Mail users can message each other.");
        sv.addView(t);
        col.addView(sv);
        return col;
    }

    // ---- send ----

    private void doSend(final View btn, String toKey, String message, Runnable onOk) {
        if (crypto == null) { toast("Still connecting to your node…"); return; }
        if (!CommsIdentity.isValidPublicId(toKey)) { toast("That doesn't look like a valid Mail key."); return; }
        if (message == null || message.trim().isEmpty()) return;

        btn.setEnabled(false);
        final MailMessage m = new MailMessage();
        m.frompublickey = myId; m.fromname = myName; m.topublickey = toKey;
        m.subject = ""; m.message = message;
        m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
        m.incoming = false; m.read = true;
        m.payaddr = myPayaddr;
        m.hashref = MailText.threadKey(myId, toKey, "");

        CommsTransport.send(node, crypto, m, new CommsTransport.SendCb() {
            @Override public void onSent() {
                io.execute(() -> {
                    m.status = "sent"; m.sentblock = chainBlock;
                    db.insert(m);
                    ui.post(() -> { btn.setEnabled(true); onOk.run(); });
                });
            }
            @Override public void onFailed(String err) {
                ui.post(() -> { btn.setEnabled(true); toast("Send failed: " + err); });
            }
        });
    }

    // ---- bubbles ----

    private View textBubble(MailMessage m) {
        TextView b = new TextView(this);
        b.setText(m.message);
        b.setTextSize(15f);
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.75));
        b.setTextColor(m.incoming ? Design.TEXT : Design.ON_ACCENT);
        b.setBackground(Design.roundBg(this, m.incoming ? Design.SURFACE2 : Design.ACCENT, 18));
        return b;
    }

    private View paymentBubble(MailMessage m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(Design.roundBg(this, m.incoming ? Design.SURFACE2 : Design.ACCENT, 18));
        int fg = m.incoming ? Design.TEXT : Design.ON_ACCENT;
        int sub = m.incoming ? Design.DIM : 0xCC000000;
        TextView head = new TextView(this);
        head.setText(m.incoming ? ("💰  " + nameFor(m.frompublickey) + " sent you") : "💰  You sent");
        head.setTextColor(sub); head.setTextSize(11f);
        TextView amt = new TextView(this);
        amt.setText(m.amount + "  " + (m.tokenname == null || m.tokenname.isEmpty() ? "MINIMA" : m.tokenname));
        amt.setTextColor(fg); amt.setTextSize(20f); amt.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(head); card.addView(amt);
        if (m.message != null && !m.message.isEmpty()) {
            TextView memo = new TextView(this);
            memo.setText(m.message); memo.setTextColor(fg); memo.setTextSize(13f); memo.setPadding(0, dp(4), 0, 0);
            card.addView(memo);
        }
        return card;
    }

    // ---- send funds ----

    private void showSendFundsSheet(final String otherKey) {
        if (crypto == null) { toast("Connect to your node first."); return; }
        final String known = db.contactPayaddr(otherKey);
        if (known == null) sendPayaddrReq(otherKey);   // try to auto-fetch their address in the background
        loadTokens(tokens -> {
            if (tokens.isEmpty()) { toast("No funds available to send."); return; }
            LinearLayout box = pad(column());
            final String[] sel = {tokens.get(0)[0], tokens.get(0)[1], tokens.get(0)[2]};   // tokenid, name, balance
            box.addView(label("Token"));
            final TextView tokenBtn = accentOutlineButton(sel[1] + "   ·   balance " + sel[2]);
            tokenBtn.setOnClickListener(v -> {
                String[] labels = new String[tokens.size()];
                for (int i = 0; i < tokens.size(); i++) labels[i] = tokens.get(i)[1] + " — " + tokens.get(i)[2];
                new AlertDialog.Builder(this).setTitle("Choose token").setItems(labels, (d, w) -> {
                    sel[0] = tokens.get(w)[0]; sel[1] = tokens.get(w)[1]; sel[2] = tokens.get(w)[2];
                    tokenBtn.setText(sel[1] + "   ·   balance " + sel[2]);
                }).show();
            });
            box.addView(tokenBtn);
            box.addView(label("Amount"));
            final EditText amt = input("0.0");
            amt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            box.addView(amt);
            box.addView(label("Their Minima receiving address"));
            final EditText addr = input("0x… / Mx… address");
            if (known != null) addr.setText(known);
            box.addView(addr);
            openPayAddrField = addr; openPayContact = otherKey;   // live-fill when the handshake returns
            TextView hint = new TextView(this);
            hint.setText(known != null ? "Auto-filled from their messages."
                    : "Auto-fills once they've messaged you. Otherwise paste their address (their wallet → Receive).");
            hint.setTextColor(Design.DIM2); hint.setTextSize(11f); hint.setPadding(0, dp(4), 0, 0);
            box.addView(hint);
            box.addView(label("Note (optional)"));
            final EditText memo = input("What's it for?");
            box.addView(memo);
            ScrollView sv = new ScrollView(this); sv.addView(box);
            AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Send funds to " + nameFor(otherKey)).setView(sv)
                    .setPositiveButton("Review", (d, w) ->
                            trySendFunds(otherKey, sel[0], sel[1], sel[2], amt.getText().toString().trim(), addr.getText().toString().trim(), memo.getText().toString()))
                    .setNegativeButton("Cancel", null).create();
            dlg.setOnDismissListener(d -> { modalOpen = false; openPayAddrField = null; openPayContact = null; });
            modalOpen = true;
            dlg.show();
        });
    }

    private void trySendFunds(String otherKey, String tokenid, String tokenname, String balanceStr, String amountStr, String addr, String memo) {
        if (amountStr.isEmpty()) { toast("Enter an amount."); return; }
        java.math.BigDecimal amount, balance;
        try { amount = new java.math.BigDecimal(amountStr); balance = new java.math.BigDecimal(balanceStr); }
        catch (Exception e) { toast("Invalid amount."); return; }
        if (amount.signum() <= 0) { toast("Enter an amount."); return; }
        if (amount.compareTo(balance) > 0) { toast("Insufficient balance."); return; }
        if (!looksLikeMinimaAddress(addr)) {
            if (db.contactPayaddr(otherKey) == null) sendPayaddrReq(otherKey);
            toast("Enter " + nameFor(otherKey) + "'s Minima receiving address (0x… or Mx…).");
            return;
        }
        final String payaddr = addr.trim();
        db.setContactPayaddr(otherKey, payaddr);   // remember it for next time
        new AlertDialog.Builder(this)
                .setTitle("Send " + amountStr + " " + tokenname + "?")
                .setMessage("To " + nameFor(otherKey) + "\n" + shortKey(payaddr) + "\n\nThis sends real funds and cannot be undone.")
                .setPositiveButton("Send", (d, w) -> doPay(otherKey, payaddr, tokenid, tokenname, amountStr, memo))
                .setNegativeButton("Cancel", null).show();
    }

    private static boolean looksLikeMinimaAddress(String a) {
        if (a == null) return false;
        a = a.trim();
        return (a.startsWith("0x") && a.length() >= 12) || (a.startsWith("Mx") && a.length() >= 20);
    }

    private void doPay(String otherKey, String payaddr, String tokenid, String tokenname, String amountStr, String memo) {
        toast("Sending " + amountStr + " " + tokenname + "…");
        node.cmd("send amount:" + amountStr + " address:" + payaddr + " tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!(j.optBoolean("status", false) || j.optBoolean("pending", false))) { toast("Payment failed: " + j.optString("error", "")); return; }
                JSONObject r = j.optJSONObject("response");
                String txid = r != null ? r.optString("txpowid", "") : "";
                sendPaymentReceipt(otherKey, tokenid, tokenname, amountStr, memo, txid);
            }
            @Override public void onError(String m) { toast("Payment failed: " + m); }
        });
    }

    private void sendPaymentReceipt(final String otherKey, String tokenid, String tokenname, String amountStr, String memo, String txid) {
        final MailMessage m = new MailMessage();
        m.type = "payment";
        m.frompublickey = myId; m.fromname = myName; m.topublickey = otherKey;
        m.message = memo == null ? "" : memo;
        m.amount = amountStr; m.tokenid = tokenid; m.tokenname = tokenname; m.txpowid = txid;
        m.payaddr = myPayaddr;
        m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
        m.incoming = false; m.read = true; m.status = "sent"; m.sentblock = chainBlock;
        m.hashref = MailText.threadKey(myId, otherKey, "");
        CommsTransport.send(node, crypto, m, new CommsTransport.SendCb() {
            @Override public void onSent() {
                io.execute(() -> { db.insert(m); ui.post(() -> {
                    toast("Sent " + amountStr + " " + tokenname);
                    View top = stack.peek();
                    if (top != null && otherKey.equals(top.getTag())) refreshTop(buildConversation(otherKey));
                }); });
            }
            @Override public void onFailed(String err) { ui.post(() -> toast("Funds sent — but the chat receipt failed.")); }
        });
    }

    private void sendPayaddrReq(String otherKey) {
        if (crypto == null) return;
        MailMessage r = new MailMessage();
        r.type = "payaddr-req";
        r.frompublickey = myId; r.topublickey = otherKey; r.payaddr = myPayaddr;
        r.message = ""; r.randomid = MailText.randomId(); r.date = System.currentTimeMillis();
        CommsTransport.send(node, crypto, r, new CommsTransport.SendCb() {
            @Override public void onSent() {} @Override public void onFailed(String m) {}
        });
    }

    private void loadTokens(final java.util.function.Consumer<List<String[]>> cb) {
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                List<String[]> out = new ArrayList<>();
                org.json.JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    if (t == null) continue;
                    String tid = t.optString("tokenid", "0x00");
                    String conf = t.optString("confirmed", "0");
                    try { if (new java.math.BigDecimal(conf).signum() <= 0) continue; } catch (Exception e) { continue; }
                    String name;
                    Object tok = t.opt("token");
                    if (tok instanceof JSONObject) name = ((JSONObject) tok).optString("name", shortKey(tid));
                    else name = (tok == null) ? shortKey(tid) : tok.toString();
                    if ("0x00".equals(tid)) name = "Minima";
                    out.add(new String[]{tid, name, conf});
                }
                cb.accept(out);
            }
            @Override public void onError(String m) { cb.accept(new ArrayList<>()); }
        });
    }

    // ---- QR scan ----

    private void startScan(EditText target) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA}, 2);
        }
        pendingScanTarget = target;
        ScanOptions o = new ScanOptions();
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        o.setPrompt("Scan a Mail key");
        o.setBeepEnabled(false);
        o.setOrientationLocked(false);
        scanLauncher.launch(o);
    }

    // ---- backup ----

    private void promptBackupPass(Uri uri, boolean export) {
        final EditText p = input("Passphrase");
        p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle(export ? "Encrypt backup" : "Decrypt backup")
                .setMessage(export ? "Choose a passphrase. Your backup contains your private key, so it's encrypted with this — you'll need it to restore."
                        : "Enter the passphrase you used when you made this backup.")
                .setView(p)
                .setPositiveButton(export ? "Back up" : "Restore", (d, w) -> {
                    String pass = p.getText().toString();
                    if (pass.length() < 4) { toast("Use a longer passphrase."); return; }
                    if (export) doExport(uri, pass); else doImport(uri, pass);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void doExport(Uri uri, String pass) {
        io.execute(() -> {
            try {
                JSONObject root = new JSONObject();
                JSONObject id = new JSONObject();
                id.put("boxPk", Hex.to(identity.boxPk)); id.put("boxSk", Hex.to(identity.boxSk));
                id.put("signPk", Hex.to(identity.signPk)); id.put("signSk", Hex.to(identity.signSk));
                root.put("identity", id);
                root.put("name", myName);
                JSONArray cons = new JSONArray();
                for (String[] c : db.contacts()) { JSONObject o = new JSONObject(); o.put("name", c[1]); o.put("key", c[2]); cons.put(o); }
                root.put("contacts", cons);
                JSONArray msgs = new JSONArray();
                for (MailMessage t : db.threads())
                    for (MailMessage m : db.thread(t.hashref)) {
                        JSONObject o = new JSONObject();
                        o.put("hashref", m.hashref); o.put("fromname", m.fromname); o.put("from", m.frompublickey);
                        o.put("to", m.topublickey); o.put("message", m.message); o.put("randomid", m.randomid);
                        o.put("incoming", m.incoming); o.put("read", m.read); o.put("date", m.date); o.put("status", m.status);
                        msgs.put(o);
                    }
                root.put("messages", msgs);
                String enc = BackupCrypto.encrypt(pass, root.toString().getBytes(StandardCharsets.UTF_8));
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(enc.getBytes(StandardCharsets.UTF_8)); }
                ui.post(() -> toast("Backed up."));
            } catch (Exception e) { ui.post(() -> toast("Backup failed: " + e.getMessage())); }
        });
    }

    private void doImport(Uri uri, String pass) {
        io.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                    String line; while ((line = r.readLine()) != null) sb.append(line);
                }
                byte[] plain = BackupCrypto.decrypt(pass, sb.toString());
                JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                JSONObject id = root.getJSONObject("identity");
                CommsIdentity restored = CommsIdentity.fromKeys(Hex.from(id.getString("boxPk")), Hex.from(id.getString("boxSk")),
                        Hex.from(id.getString("signPk")), Hex.from(id.getString("signSk")));
                myName = root.optString("name", "");
                db.setMeta("myname", myName);
                JSONArray cons = root.optJSONArray("contacts");
                if (cons != null) for (int i = 0; i < cons.length(); i++) { JSONObject o = cons.getJSONObject(i); db.addContact(o.optString("name"), o.optString("key")); }
                JSONArray msgs = root.optJSONArray("messages");
                if (msgs != null) for (int i = 0; i < msgs.length(); i++) {
                    JSONObject o = msgs.getJSONObject(i);
                    MailMessage m = new MailMessage();
                    m.hashref = o.optString("hashref"); m.fromname = o.optString("fromname");
                    m.frompublickey = o.optString("from"); m.topublickey = o.optString("to");
                    m.message = o.optString("message"); m.randomid = o.optString("randomid");
                    m.incoming = o.optBoolean("incoming"); m.read = o.optBoolean("read"); m.date = o.optLong("date"); m.status = o.optString("status", "");
                    db.insert(m);
                }
                ui.post(() -> { adoptIdentity(restored); toast("Restored."); showInbox(); requestScan(); });
            } catch (Exception e) { ui.post(() -> toast("Restore failed — wrong passphrase or bad file.")); }
        });
    }

    // ---- menu / dialogs ----

    private void showMenu(View anchor) {
        androidx.appcompat.widget.PopupMenu m = new androidx.appcompat.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Contacts");
        m.getMenu().add(0, 4, 1, "Archived");
        m.getMenu().add(0, 2, 2, "Your Mail key");
        m.getMenu().add(0, 3, 3, "Help");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: push(buildContacts()); return true;
                case 2: push(buildIdentity()); return true;
                case 3: push(buildHelp()); return true;
                case 4: push(buildArchived()); return true;
                default: return false;
            }
        });
        m.show();
    }

    private void addContactDialog(String prefillKey) {
        LinearLayout box = pad(column());
        final EditText name = input("Name");
        final EditText key = input("0x… their Mail key");
        if (prefillKey != null) key.setText(prefillKey);
        TextView scan = textButton("⧉ Scan QR");
        scan.setOnClickListener(v -> startScan(key));
        box.addView(label("Name")); box.addView(name);
        box.addView(label("Mail key")); box.addView(key); box.addView(scan);
        new AlertDialog.Builder(this).setTitle("Add contact").setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String n = name.getText().toString().trim(), k = acceptKeyShare(key.getText().toString());
                    if (n.isEmpty() || !CommsIdentity.isValidPublicId(k)) { toast("Enter a name and a valid Mail key."); return; }
                    io.execute(() -> { db.addContact(n, k); ui.post(() -> { View top = stack.peek(); if (top != null && top.getTag() instanceof String) refreshTop(buildConversation(k)); }); });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void pickContact(EditText target) {
        List<String[]> cs = db.contacts();
        if (cs.isEmpty()) { toast("No contacts yet."); return; }
        String[] names = new String[cs.size()];
        for (int i = 0; i < cs.size(); i++) names[i] = cs.get(i)[1];
        new AlertDialog.Builder(this).setTitle("Pick a contact").setItems(names, (d, which) -> target.setText(cs.get(which)[2])).show();
    }

    // ---- notifications ----

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CH, "New mail", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void notifyNew(int count) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            android.app.Notification n = new androidx.core.app.NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("Minima Mail")
                    .setContentText(count == 1 ? "New message" : count + " new messages")
                    .setAutoCancel(true).build();
            NotificationManagerCompat.from(this).notify(42, n);
        } catch (Exception ignored) {}
    }

    // ---- view helpers ----

    private LinearLayout buildPairingBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(Design.SURFACE);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t1 = new TextView(this); t1.setText("Minima Mail is not enabled yet"); t1.setTextColor(Design.ACCENT); t1.setTextSize(15f); t1.setTypeface(Typeface.DEFAULT_BOLD);
        TextView t2 = new TextView(this); t2.setText("Open Minima Core → Apps and enable \"Minima Mail\", then come back."); t2.setTextColor(Design.DIM); t2.setTextSize(13f); t2.setPadding(0, dp(4), 0, dp(8));
        TextView open = accentButton("Open Minima Core");
        open.setOnClickListener(v -> { Intent i = getPackageManager().getLaunchIntentForPackage("org.minimarex.minimacore"); if (i != null) startActivity(i); else toast("Minima Core isn't installed."); });
        b.addView(t1); b.addView(t2); b.addView(open);
        return b;
    }

    private FrameLayout avatar(String key, String name, int sizeDp) { return Avatars.view(this, key, name, sizeDp); }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout pad(LinearLayout l) { int p = dp(16); l.setPadding(p, p, p, p); return l; }

    private LinearLayout header(String title, boolean back) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE);
        h.setPadding(dp(8), dp(12), dp(8), dp(12));
        if (back) h.addView(iconBtn("‹", v -> pop()));
        TextView t = new TextView(this);
        t.setText(title); t.setTextColor(back ? Design.TEXT : Design.ACCENT); t.setTextSize(20f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(8), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        h.addView(t);
        return h;
    }

    private TextView iconBtn(String glyph, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(glyph); t.setTextColor(Design.ACCENT); t.setTextSize(22f);
        t.setPadding(dp(12), dp(4), dp(12), dp(4));
        t.setOnClickListener(click);
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Design.DIM); t.setTextSize(12f);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(Design.DIM2); e.setTextColor(Design.TEXT); e.setTextSize(14f);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setBackground(Design.roundBg(this, Design.SURFACE, 10));
        int p = dp(12); e.setPadding(p, p, p, p);
        return e;
    }

    private TextView accentButton(String s) {
        TextView b = new TextView(this);
        b.setText(s); b.setGravity(Gravity.CENTER);
        b.setTextColor(Design.ON_ACCENT); b.setTextSize(15f); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(Design.roundBg(this, Design.ACCENT, 12));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    private TextView textButton(String s) {
        TextView b = new TextView(this);
        b.setText(s); b.setTextColor(Design.ACCENT); b.setTextSize(14f);
        b.setPadding(dp(8), dp(12), dp(16), dp(12));
        return b;
    }

    private TextView accentOutlineButton(String s) {
        TextView b = new TextView(this);
        b.setText(s); b.setTextColor(Design.ACCENT); b.setTextSize(14f);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10)); d.setStroke(dp(1), Design.ACCENT); d.setColor(0x00000000);
        b.setBackground(d);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    // ---- formatting ----

    private String nameFor(String key) {
        if (key == null) return "(unknown)";
        String n = db.contactName(key);
        return n != null ? n : shortKey(key);
    }

    private static String shortKey(String key) {
        if (key == null) return "—";
        String h = key.startsWith("0x") ? key.substring(2) : key;
        if (h.length() <= 14) return key;
        return "0x" + h.substring(0, 8) + "…" + h.substring(h.length() - 6);
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 64 ? s.substring(0, 64) + "…" : s;
    }

    private static boolean sameDay(long a, long b) {
        java.util.Calendar ca = java.util.Calendar.getInstance(); ca.setTimeInMillis(a);
        java.util.Calendar cb = java.util.Calendar.getInstance(); cb.setTimeInMillis(b);
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR)
                && ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private static String clock(long ms) { return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ENGLISH).format(new java.util.Date(ms)); }

    private static String dateLabel(long ms) {
        long now = System.currentTimeMillis();
        if (sameDay(ms, now)) return "Today";
        if (sameDay(ms, now - 86400000L)) return "Yesterday";
        return new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).format(new java.util.Date(ms));
    }

    private static String rel(long ms) {
        if (ms <= 0) return "";
        long d = System.currentTimeMillis() - ms;
        if (d < 60000) return "now";
        if (d < 3600000) return (d / 60000) + "m";
        if (d < 86400000) return clock(ms);
        if (d < 7 * 86400000L) return (d / 86400000) + "d";
        return new java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(new java.util.Date(ms));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void copy(String text, String label) {
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
