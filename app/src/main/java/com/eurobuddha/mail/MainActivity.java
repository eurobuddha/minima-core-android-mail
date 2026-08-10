package com.eurobuddha.mail;

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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.goterl.lazysodium.LazySodium;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.comms.Avatars;
import com.eurobuddha.comms.BackupCrypto;
import com.eurobuddha.comms.CommsDb;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsScanner;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.Images;
import com.eurobuddha.comms.LocalEcCryptoProvider;
import com.eurobuddha.comms.MailMessage;
import com.eurobuddha.comms.MailText;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.QrUtil;
import com.eurobuddha.comms.Sodium;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Minima Mail — on-chain email. Drawer + folders + subjects; bubbles inside a conversation. */
public class MainActivity extends AppCompatActivity {

    private static final String CH = "mail";

    private enum Folder { INBOX, OUTBOX, SENT, ARCHIVE, CONTACTS, IDENTITY, SETTINGS }

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
    private java.util.Map<String, String> contactNames;   // cached publickey→name (avoids a DB query per list row)
    private final android.util.LruCache<String, Bitmap> imgCache = new android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private EditText openPayAddrField;   // the address field of an open Send-funds sheet (for live fill)
    private String openPayContact;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private BroadcastReceiver notifyReceiver;

    private DrawerLayout drawer;
    private LinearLayout navPanel, contentCol, pairingBanner;
    private FrameLayout container;
    private Folder folder = Folder.INBOX;
    private final ArrayDeque<View> stack = new ArrayDeque<>();
    private int insetTop = 0, insetBottom = 0;
    private RecyclerView currentMessages;   // for scroll-to-bottom on keyboard
    private MessagesAdapter convAdapter;    // the open conversation's adapter, for in-place message updates
    private String convHashref;             // the open conversation's thread key
    private TextView freshText;             // the inbox freshness line ("Checked block N · Xm ago")
    private SwipeRefreshLayout inboxSwipe;

    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<ScanOptions> scanLauncher;
    private ActivityResultLauncher<String> imagePicker;
    private String imagePickContact, imagePickSubject = "";
    private EditText pendingScanTarget;
    private boolean pendingScanIsAddress;   // true → scan to fill a Minima address field (not a Mail key)

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ls = Sodium.get();
        db = new CommsDb(this);
        Design.load(db.getMeta("theme", "light"));
        myName = db.getMeta("myname", "");
        myPayaddr = db.getMeta("mypayaddr", "");

        getWindow().setBackgroundDrawable(new ColorDrawable(Design.BG));

        drawer = new DrawerLayout(this);
        contentCol = new LinearLayout(this);
        contentCol.setOrientation(LinearLayout.VERTICAL);
        contentCol.setBackgroundColor(Design.BG);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        container = new FrameLayout(this);
        contentCol.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        contentCol.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        drawer.addView(contentCol, new DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT));

        navPanel = new LinearLayout(this);
        navPanel.setOrientation(LinearLayout.VERTICAL);
        navPanel.setBackgroundColor(Design.SURFACE);
        DrawerLayout.LayoutParams nlp = new DrawerLayout.LayoutParams(dp(288), DrawerLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        drawer.addView(navPanel, nlp);
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerStateChanged(int newState) {
                if (newState == DrawerLayout.STATE_DRAGGING || newState == DrawerLayout.STATE_SETTLING) rebuildNav();
            }
        });
        rebuildNav();

        setContentView(drawer);
        applyInsets();
        ensureChannel();
        requestNotifPermission();

        exportLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                uri -> { if (uri != null) promptBackupPass(uri, true); });
        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) promptBackupPass(uri, false); });
        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null && pendingScanTarget != null) {
                String s = result.getContents().trim();
                if (pendingScanIsAddress) {
                    int bar = s.indexOf('|');                       // a "key|addr" QR → take the address half
                    pendingScanTarget.setText(bar >= 0 ? s.substring(bar + 1).trim() : s);
                } else {
                    pendingScanTarget.setText(acceptKeyShare(s));   // a Mail-key QR → take the key (store the addr)
                }
            }
        });
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && imagePickContact != null) sendImage(imagePickContact, imagePickSubject, uri);
        });

        showFolder(Folder.INBOX);

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

        ui.postDelayed(freshTick, 30000);
    }

    private final Runnable freshTick = new Runnable() {
        @Override public void run() {
            updateFreshness();
            ui.postDelayed(this, 30000);
        }
    };

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(freshTick);
        if (notifyReceiver != null) try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }

    @Override public void onBackPressed() {
        if (drawer.isDrawerOpen(Gravity.START)) { drawer.closeDrawer(Gravity.START); return; }
        if (stack.size() > 1) pop();
        else if (folder != Folder.INBOX) showFolder(Folder.INBOX);
        else super.onBackPressed();
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(drawer, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            insetTop = bars.top;
            insetBottom = Math.max(bars.bottom, ime.bottom);
            contentCol.setPadding(0, insetTop, 0, insetBottom);
            navPanel.setPadding(0, insetTop, 0, bars.bottom);
            if (ime.bottom > 0 && currentMessages != null && currentMessages.getAdapter() != null) {
                currentMessages.scrollToPosition(Math.max(0, currentMessages.getAdapter().getItemCount() - 1));
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(drawer);
        new WindowInsetsControllerCompat(getWindow(), drawer).setAppearanceLightStatusBars(Design.LIGHT);
    }

    // ---- navigation ----

    private void push(View v) { stack.push(v); showTop(); }

    private void pop() {
        if (stack.size() > 1) {
            stack.pop();
            if (stack.size() == 1) refreshTop(buildFolder(folder));   // root re-reads the DB (unread dots etc.)
            else showTop();
        }
    }

    private void showFolder(Folder f) {
        folder = f;
        stack.clear();
        stack.push(buildFolder(f));
        showTop();
    }

    private View buildFolder(Folder f) {
        switch (f) {
            case OUTBOX:   return buildStatusList("Outbox", true);
            case SENT:     return buildStatusList("Sent", false);
            case ARCHIVE:  return buildThreadList(true);
            case CONTACTS: return buildContacts();
            case IDENTITY: return buildIdentity();
            case SETTINGS: return buildSettings();
            default:       return buildThreadList(false);
        }
    }

    private void showTop() {
        currentMessages = null;
        container.removeAllViews();
        container.addView(stack.peek());
    }
    private void refreshTop(View rebuilt) { stack.pop(); stack.push(rebuilt); showTop(); }

    /** Rebuild the visible root folder (called after data changes, never over a pushed screen/dialog). */
    private void refreshFolderIfVisible() {
        if (!modalOpen && stack.size() == 1) refreshTop(buildFolder(folder));
    }

    // ---- drawer ----

    private void rebuildNav() {
        navPanel.removeAllViews();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(18), dp(20), dp(18), dp(14));
        TextView brand = new TextView(this);
        android.text.SpannableString bs = new android.text.SpannableString("MINIMA MAIL");
        bs.setSpan(new android.text.style.ForegroundColorSpan(Design.ACCENT), 7, 11, 0);
        brand.setText(bs);
        brand.setTextColor(Design.TEXT); brand.setTextSize(16f); brand.setTypeface(Typeface.DEFAULT_BOLD);
        TextView me = new TextView(this);
        me.setText(myId == null ? "connecting to your node…" : "you · " + shortKey(myId));
        me.setTextColor(Design.DIM); me.setTextSize(11f); me.setTypeface(Typeface.MONOSPACE);
        me.setPadding(0, dp(3), 0, 0);
        // NB: no click handler on the header — it sits under the hamburger's screen position, so a
        // double-tap on the hamburger would silently navigate. "Your key" below covers the shortcut.
        head.addView(brand); head.addView(me);
        navPanel.addView(head);
        navPanel.addView(hairline());

        int unread = db.unreadCount();
        int pending = db.outboxCount();
        navPanel.addView(navItem(R.drawable.ic_inbox, "Inbox", unread, Folder.INBOX));
        navPanel.addView(navItem(R.drawable.ic_outbox, "Outbox", pending, Folder.OUTBOX));
        navPanel.addView(navItem(R.drawable.ic_send, "Sent", 0, Folder.SENT));
        navPanel.addView(navItem(R.drawable.ic_archive, "Archive", 0, Folder.ARCHIVE));
        View sep = hairline();
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        slp.setMargins(dp(18), dp(8), dp(18), dp(8));
        navPanel.addView(sep, slp);
        navPanel.addView(navItem(R.drawable.ic_contacts, "Contacts", 0, Folder.CONTACTS));
        navPanel.addView(navItem(R.drawable.ic_key, "Your key", 0, Folder.IDENTITY));
        navPanel.addView(navItem(R.drawable.ic_settings, "Settings", 0, Folder.SETTINGS));

        View spacer = new View(this);
        navPanel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView ver = new TextView(this);
        ver.setText("Minima Mail v" + BuildConfig.VERSION_NAME);
        ver.setTextColor(Design.DIM2); ver.setTextSize(10.5f);
        ver.setPadding(dp(18), dp(6), dp(18), dp(10));
        navPanel.addView(ver);
    }

    private View navItem(int iconRes, String label, int badgeCount, final Folder target) {
        boolean sel = folder == target && stack.size() == 1;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(11), dp(14), dp(11));
        int tint = sel ? Design.ACCENT : Design.DIM;
        row.addView(icon(iconRes, tint, 20));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(sel ? Design.ACCENT : Design.TEXT);
        t.setTextSize(14.5f);
        if (sel) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(14), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);
        if (badgeCount > 0) {
            TextView b = new TextView(this);
            b.setText(String.valueOf(badgeCount));
            b.setTextColor(Design.ON_ACCENT); b.setTextSize(10.5f); b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setBackground(Design.roundBg(this, Design.ACCENT, 10));
            b.setPadding(dp(7), dp(1), dp(7), dp(1));
            row.addView(b);
        }
        if (sel) {
            GradientDrawable d = new GradientDrawable();
            d.setColor((Design.ACCENT & 0x00FFFFFF) | 0x22000000);
            float r = dp(22);
            d.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
            row.setBackground(d);
            ViewGroup.MarginLayoutParams mlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ((ViewGroup.MarginLayoutParams) mlp).rightMargin = dp(12);
            row.setLayoutParams(mlp);
        }
        row.setOnClickListener(v -> { drawer.closeDrawer(Gravity.START); showFolder(target); });
        return row;
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(Design.HAIRLINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

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
                updateFreshness();
                if (chainBlock > 0) io.execute(() -> { db.markConfirmed(chainBlock); ui.post(MainActivity.this::refreshFolderIfVisible); });
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
                ui.post(() -> { adoptIdentity(id); refreshFolderIfVisible(); requestScan(); });
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

    /** Reload the open conversation's messages WITHOUT rebuilding the screen (so an open dialog is untouched). */
    private void reloadConversation(final String hashref) {
        if (convAdapter == null || convHashref == null || !convHashref.equals(hashref)) return;
        final MessagesAdapter a = convAdapter;
        final RecyclerView rv = currentMessages;
        io.execute(() -> {
            final List<MailMessage> msgs = db.thread(hashref);
            ui.post(() -> {
                if (convAdapter != a) return;   // user navigated away in the meantime
                a.setData(msgs);
                if (rv != null && !msgs.isEmpty()) rv.scrollToPosition(msgs.size() - 1);
            });
        });
    }

    private void onScanDone(boolean ok, int newCount) {
        ui.post(() -> {
            if (inboxSwipe != null) inboxSwipe.setRefreshing(false);
            updateFreshness();
            if (newCount > 0) {
                notifyNew(newCount);
                if (convHashref != null && stack.size() > 1) reloadConversation(convHashref);   // in-place, dialog-safe
                else refreshFolderIfVisible();
            }
        });
    }

    // ---- freshness ("checked block N · Xm ago") ----

    private View buildFreshness() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Design.SURFACE);
        bar.setPadding(dp(14), dp(3), dp(14), dp(7));
        View dot = new View(this);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL);
        g.setColor(paired ? Design.GREEN : Design.DIM2);
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dlp.rightMargin = dp(6);
        bar.addView(dot, dlp);
        freshText = new TextView(this);
        freshText.setTextColor(Design.DIM); freshText.setTextSize(10.5f);
        freshText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(freshText);
        LinearLayout check = new LinearLayout(this);
        check.setOrientation(LinearLayout.HORIZONTAL);
        check.setGravity(Gravity.CENTER_VERTICAL);
        check.addView(icon(R.drawable.ic_refresh, Design.ACCENT, 11));
        TextView ct = new TextView(this);
        ct.setText("Check now"); ct.setTextColor(Design.ACCENT); ct.setTextSize(10.5f); ct.setTypeface(Typeface.DEFAULT_BOLD);
        ct.setPadding(dp(4), 0, 0, 0);
        check.addView(ct);
        check.setPadding(dp(8), dp(2), 0, dp(2));
        check.setOnClickListener(v -> { fetchBlock(); requestScan(); toast("Checking the chain…"); });
        bar.addView(check);
        updateFreshness();
        return bar;
    }

    private void updateFreshness() {
        if (freshText == null) return;
        if (crypto == null) { freshText.setText(paired ? "Connecting to your node…" : "Waiting for Minima Core…"); return; }
        long last = scanner == null ? 0 : scanner.lastScanEnd();
        String blk = chainBlock > 0 ? "block " + String.format(java.util.Locale.ENGLISH, "%,d", chainBlock) : "the chain";
        if (last <= 0) { freshText.setText("Checking " + blk + "…"); return; }
        long m = (System.currentTimeMillis() - last) / 60000;
        String ago = m < 1 ? "just now" : m + " min ago";
        freshText.setText("Checked " + blk + " · " + ago);
    }

    // ---- INBOX / ARCHIVE (thread lists, email rows) ----

    private View buildThreadList(final boolean archivedView) {
        FrameLayout screen = new FrameLayout(this);
        LinearLayout col = column();

        LinearLayout head = header(archivedView ? "Archive" : "Inbox", false);
        col.addView(head);
        if (!archivedView) col.addView(buildFreshness());

        if (crypto == null && !archivedView) {
            TextView s = new TextView(this);
            s.setText(paired ? "Connecting to your node…" : "Enable Minima Mail in Minima Core → Apps.");
            s.setTextColor(Design.DIM);
            s.setTextSize(13f);
            s.setPadding(dp(16), dp(8), dp(16), dp(8));
            col.addView(s);
        }

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        final ChatListAdapter adapter = new ChatListAdapter(new ArrayList<>(), archivedView);
        rv.setAdapter(adapter);
        attachSwipe(rv, adapter);
        rv.setClipToPadding(false);
        rv.setPadding(0, 0, 0, dp(88));

        final TextView empty = new TextView(this);
        empty.setText(archivedView ? "No archived threads." : "No mail yet.\nCompose a message to get started.");
        empty.setTextColor(Design.DIM2); empty.setGravity(Gravity.CENTER); empty.setTextSize(13f);
        empty.setPadding(dp(24), dp(64), dp(24), 0); empty.setVisibility(View.GONE);

        io.execute(() -> {                                  // load off the UI thread
            java.util.Set<String> archived = db.archivedSet();
            List<MailMessage> threads = new ArrayList<>();
            for (MailMessage t : db.threads()) if (archived.contains(t.hashref) == archivedView) threads.add(t);
            ui.post(() -> {
                adapter.data.clear(); adapter.data.addAll(threads); adapter.notifyDataSetChanged();
                empty.setVisibility(threads.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });

        if (archivedView) {
            col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            col.addView(empty);
            screen.addView(col);
            return screen;
        }

        inboxSwipe = new SwipeRefreshLayout(this);
        inboxSwipe.setColorSchemeColors(Design.ACCENT);
        inboxSwipe.setProgressBackgroundColorSchemeColor(Design.SURFACE);
        LinearLayout inner = column();
        inner.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        inboxSwipe.addView(inner);
        inboxSwipe.setOnRefreshListener(() -> {
            fetchBlock(); requestScan();
            ui.postDelayed(() -> { if (inboxSwipe != null) inboxSwipe.setRefreshing(false); }, 4000);
        });
        FrameLayout listWrap = new FrameLayout(this);
        listWrap.addView(inboxSwipe);
        listWrap.addView(empty);
        col.addView(listWrap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        screen.addView(col);

        // FAB → compose
        FrameLayout fab = new FrameLayout(this);
        GradientDrawable fb = Design.roundBg(this, Design.ACCENT, 16);
        fab.setBackground(fb);
        fab.setElevation(dp(6));
        ImageView pen = icon(R.drawable.ic_pen, Design.ON_ACCENT, 24);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        fab.addView(pen, plp);
        fab.setOnClickListener(v -> push(buildCompose(null, null)));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(56), dp(56));
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
            LinearLayout wrap = new LinearLayout(MainActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(wrap) {};
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            MailMessage t = data.get(pos);
            String other = myId != null && myId.equals(t.frompublickey) ? t.topublickey : t.frompublickey;
            String name = nameFor(other);
            boolean unread = t.incoming && !t.read;

            LinearLayout wrap = (LinearLayout) h.itemView;
            wrap.removeAllViews();
            wrap.setBackgroundColor(Design.BG);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.addView(avatar(other, name, 44));

            LinearLayout mid = new LinearLayout(MainActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(12), 0, dp(8), 0);
            mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout l1 = new LinearLayout(MainActivity.this);
            l1.setOrientation(LinearLayout.HORIZONTAL);
            TextView nm = new TextView(MainActivity.this);
            nm.setText(name);
            nm.setTextColor(unread ? Design.TEXT : Design.DIM);
            nm.setTextSize(14.5f);
            nm.setTypeface(unread ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            nm.setMaxLines(1);
            nm.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView time = new TextView(MainActivity.this);
            time.setText(rel(t.date)); time.setTextColor(Design.DIM2); time.setTextSize(11f);
            l1.addView(nm); l1.addView(time);

            TextView subj = new TextView(MainActivity.this);
            boolean noSubject = t.subject == null || t.subject.isEmpty();
            subj.setText(noSubject ? "(no subject)" : t.subject);
            subj.setTextColor(unread ? Design.TEXT : Design.DIM);
            if (noSubject) { subj.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC)); subj.setTextColor(Design.DIM2); }
            else subj.setTypeface(unread ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            subj.setTextSize(12.5f); subj.setMaxLines(1);

            TextView snip = new TextView(MainActivity.this);
            snip.setText((t.incoming ? "" : "You: ") + previewText(t));
            snip.setTextColor(Design.DIM);
            snip.setTextSize(11.5f); snip.setMaxLines(1);

            mid.addView(l1); mid.addView(subj); mid.addView(snip);
            row.addView(mid);

            if (unread) {
                View udot = new View(MainActivity.this);
                GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(Design.ACCENT);
                udot.setBackground(g);
                row.addView(udot, new LinearLayout.LayoutParams(dp(8), dp(8)));
            }
            wrap.addView(row);
            wrap.addView(hairline());

            wrap.setOnClickListener(v -> openConversation(t));
            wrap.setOnLongClickListener(v -> { showThreadActions(t, archivedView); return true; });
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
                    new AlertDialog.Builder(MainActivity.this).setMessage("Delete this thread?")
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
                refreshFolderIfVisible();
            } else {
                new AlertDialog.Builder(this).setMessage("Delete this thread?")
                        .setPositiveButton("Delete", (dd, ww) -> { io.execute(() -> db.deleteThread(hashref)); refreshFolderIfVisible(); })
                        .setNegativeButton("Cancel", null).show();
            }
        }).show();
    }

    private void openConversation(MailMessage thread) {
        String other = myId != null && myId.equals(thread.frompublickey) ? thread.topublickey : thread.frompublickey;
        openConversation(other, thread.subject == null ? "" : thread.subject);
    }

    private void openConversation(final String otherKey, final String subject) {
        io.execute(() -> {
            db.markThreadRead(MailText.threadKey(myId, otherKey, subject == null ? "" : subject));
            ui.post(() -> push(buildConversation(otherKey, subject == null ? "" : subject)));
        });
    }

    // ---- OUTBOX / SENT (per-message status lists) ----

    private View buildStatusList(String title, final boolean outboxView) {
        LinearLayout col = column();
        col.addView(header(title, false));

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));

        final TextView empty = new TextView(this);
        empty.setText(outboxView ? "Outbox is empty — everything you sent has reached the chain."
                : "Nothing sent yet.");
        empty.setTextColor(Design.DIM2); empty.setGravity(Gravity.CENTER); empty.setTextSize(13f);
        empty.setPadding(dp(24), dp(64), dp(24), 0); empty.setVisibility(View.GONE);

        final List<MailMessage> data = new ArrayList<>();
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                LinearLayout wrap = new LinearLayout(MainActivity.this);
                wrap.setOrientation(LinearLayout.VERTICAL);
                wrap.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(wrap) {};
            }

            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                final MailMessage m = data.get(pos);
                LinearLayout wrap = (LinearLayout) h.itemView;
                wrap.removeAllViews();
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(dp(14), dp(10), dp(14), dp(10));

                LinearLayout l1 = new LinearLayout(MainActivity.this);
                TextView to = new TextView(MainActivity.this);
                String subj = (m.subject == null || m.subject.isEmpty()) ? "(no subject)" : m.subject;
                to.setText("To " + nameFor(m.topublickey) + " — " + subj);
                to.setTextColor(Design.TEXT); to.setTextSize(13f); to.setTypeface(Typeface.DEFAULT_BOLD);
                to.setMaxLines(1);
                to.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView time = new TextView(MainActivity.this);
                time.setText(rel(m.date)); time.setTextColor(Design.DIM2); time.setTextSize(10.5f);
                l1.addView(to); l1.addView(time);
                cell.addView(l1);

                TextView snip = new TextView(MainActivity.this);
                snip.setText(previewText(m));
                snip.setTextColor(Design.DIM); snip.setTextSize(11.5f); snip.setMaxLines(1);
                cell.addView(snip);

                cell.addView(statusLine(m, outboxView));
                wrap.addView(cell);
                wrap.addView(hairline());
                wrap.setOnClickListener(v -> openConversation(m.topublickey, m.subject == null ? "" : m.subject));
            }

            @Override public int getItemCount() { return data.size(); }
        };
        rv.setAdapter(adapter);

        io.execute(() -> {
            List<MailMessage> l = outboxView ? db.outbox() : db.sent();
            ui.post(() -> {
                data.clear(); data.addAll(l); adapter.notifyDataSetChanged();
                empty.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });

        FrameLayout listWrap = new FrameLayout(this);
        listWrap.addView(rv);
        listWrap.addView(empty);
        col.addView(listWrap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    /** The honest lifecycle line: ring "Posting…" → chain "On-chain · blk N" → check "Confirmed" / failed+Retry. */
    private View statusLine(final MailMessage m, boolean withRetry) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(4), 0, 0);
        int ic; int tint; String label; int labelColor;
        if ("posting".equals(m.status)) {
            ic = R.drawable.ic_ring; tint = Design.ACCENT; label = "Posting to chain…"; labelColor = Design.ACCENT;
        } else if ("failed".equals(m.status)) {
            ic = R.drawable.ic_close; tint = Design.RED; label = "Failed — didn't reach the chain"; labelColor = Design.RED;
        } else if ("confirmed".equals(m.status)) {
            ic = R.drawable.ic_check; tint = Design.GREEN;
            label = "Confirmed" + (m.sentblock > 0 ? " · block " + String.format(java.util.Locale.ENGLISH, "%,d", m.sentblock) : "");
            labelColor = Design.DIM;
        } else {
            ic = R.drawable.ic_chain; tint = Design.GREEN;
            label = "On-chain" + (m.sentblock > 0 ? " · block " + String.format(java.util.Locale.ENGLISH, "%,d", m.sentblock) : "") + " — confirming";
            labelColor = Design.DIM;
        }
        line.addView(icon(ic, tint, 12));
        TextView t = new TextView(this);
        t.setText(label); t.setTextColor(labelColor); t.setTextSize(11f);
        t.setPadding(dp(6), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(t);
        if (withRetry && "failed".equals(m.status)) {
            LinearLayout retry = new LinearLayout(this);
            retry.setOrientation(LinearLayout.HORIZONTAL);
            retry.setGravity(Gravity.CENTER_VERTICAL);
            retry.setBackground(Design.outlineBg(this, Design.ACCENT, 11));
            retry.setPadding(dp(9), dp(2), dp(9), dp(2));
            retry.addView(icon(R.drawable.ic_refresh, Design.ACCENT, 11));
            TextView rt = new TextView(this);
            rt.setText("Retry"); rt.setTextColor(Design.ACCENT); rt.setTextSize(10.5f); rt.setTypeface(Typeface.DEFAULT_BOLD);
            rt.setPadding(dp(4), 0, 0, 0);
            retry.addView(rt);
            retry.setOnClickListener(v -> retrySend(m.id));
            line.addView(retry);
        }
        return line;
    }

    // ---- CONVERSATION (bubbles kept) ----

    private View buildConversation(final String otherKey, final String subject) {
        final String hashref = MailText.threadKey(myId == null ? "" : myId, otherKey, subject);
        LinearLayout col = column();
        col.setTag(hashref);
        proactivePayaddr(otherKey);   // request their receiving address now, so it's ready when you pay

        // top bar: back + avatar + subject/name
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Design.SURFACE);
        head.setPadding(dp(6), dp(8), dp(12), dp(8));
        head.addView(iconBtn(R.drawable.ic_back, v -> pop()));
        head.addView(avatar(otherKey, nameFor(otherKey), 34));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(dp(10), 0, 0, 0);
        tcol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView nm = new TextView(this);
        nm.setText(subject.isEmpty() ? nameFor(otherKey) : subject);
        nm.setTextColor(Design.TEXT); nm.setTextSize(15f); nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setMaxLines(1);
        TextView sub = new TextView(this);
        sub.setText(subject.isEmpty() ? shortKey(otherKey) : nameFor(otherKey) + " · " + shortKey(otherKey));
        sub.setTextColor(Design.DIM); sub.setTextSize(10.5f); sub.setTypeface(Typeface.MONOSPACE);
        sub.setMaxLines(1);
        tcol.addView(nm); tcol.addView(sub);
        head.addView(tcol);
        head.addView(iconBtn(R.drawable.ic_dots, v -> conversationMenu(otherKey, hashref)));
        col.addView(head);

        // messages
        final RecyclerView rv = new RecyclerView(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        final MessagesAdapter adapter = new MessagesAdapter(new ArrayList<>());
        rv.setAdapter(adapter);
        rv.setPadding(0, dp(8), 0, dp(8));
        rv.setClipToPadding(false);
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        currentMessages = rv;
        convAdapter = adapter; convHashref = hashref;   // enable in-place updates (no screen rebuild)
        io.execute(() -> {                              // load messages off the UI thread
            final List<MailMessage> msgs = db.thread(hashref);
            ui.post(() -> { adapter.setData(msgs); if (!msgs.isEmpty()) rv.scrollToPosition(msgs.size() - 1); });
        });

        // composer
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Design.SURFACE);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        ImageView attach = iconBtn(R.drawable.ic_plus, null);
        attach.setColorFilter(Design.ACCENT);
        attach.setOnClickListener(v -> {
            androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(this, attach);
            pm.getMenu().add(0, 1, 0, "Photo or GIF");
            pm.getMenu().add(0, 2, 1, "Send funds");
            pm.setOnMenuItemClickListener(it -> {
                if (it.getItemId() == 1) { imagePickContact = otherKey; imagePickSubject = subject; imagePicker.launch("image/*"); }
                else showSendFundsSheet(otherKey, subject);
                return true;
            });
            pm.show();
        });
        bar.addView(attach);
        final EditText box = new EditText(this);
        box.setHint("Reply");
        box.setHintTextColor(Design.DIM2);
        box.setTextColor(Design.TEXT);
        box.setTextSize(15f);
        box.setMaxLines(4);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.setBackground(Design.roundBg(this, Design.SURFACE2, 20));
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(box);

        final FrameLayout send = new FrameLayout(this);
        GradientDrawable sd = new GradientDrawable(); sd.setShape(GradientDrawable.OVAL); sd.setColor(Design.ACCENT);
        send.setBackground(sd);
        send.addView(icon(R.drawable.ic_up, Design.ON_ACCENT, 16), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.leftMargin = dp(8);
        send.setLayoutParams(slp);
        send.setOnClickListener(v -> {
            String text = box.getText().toString();
            doSend(send, otherKey, subject, text, () -> box.setText(""));
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
                        || !sameDay(m.date, msgs.get(i + 1).date)
                        || (!m.incoming && ("posting".equals(m.status) || "failed".equals(m.status)));
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
            rowWrap.addView("image".equals(r.m.type) ? imageBubble(r.m)
                    : "payment".equals(r.m.type) ? paymentBubble(r.m) : textBubble(r.m));
            v.addView(rowWrap);

            if (r.footer) {
                LinearLayout f = new LinearLayout(MainActivity.this);
                f.setOrientation(LinearLayout.HORIZONTAL);
                f.setGravity(Gravity.CENTER_VERTICAL);
                f.setPadding(dp(18), dp(2), dp(18), dp(8));
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                flp.gravity = r.m.incoming ? Gravity.START : Gravity.END;
                f.setLayoutParams(flp);

                TextView time = new TextView(MainActivity.this);
                time.setText(clock(r.m.date));
                time.setTextColor(Design.DIM2);
                time.setTextSize(10f);
                f.addView(time);

                if (!r.m.incoming) {
                    int ic = 0; int tint = 0; String label = null; int labelColor = Design.DIM2;
                    if ("posting".equals(r.m.status)) { ic = R.drawable.ic_ring; tint = Design.ACCENT; label = "Posting to chain…"; labelColor = Design.ACCENT; }
                    else if ("failed".equals(r.m.status)) { ic = R.drawable.ic_close; tint = Design.RED; label = "Failed — retry from Outbox"; labelColor = Design.RED; }
                    else if ("confirmed".equals(r.m.status)) { ic = R.drawable.ic_check; tint = Design.GREEN; label = "Confirmed"; }
                    else if ("sent".equals(r.m.status)) {
                        ic = R.drawable.ic_chain; tint = Design.DIM2;
                        label = "On-chain" + (r.m.sentblock > 0 ? " · blk " + String.format(java.util.Locale.ENGLISH, "%,d", r.m.sentblock) : "");
                    }
                    if (label != null) {
                        TextView dotSep = new TextView(MainActivity.this);
                        dotSep.setText(" · "); dotSep.setTextColor(Design.DIM2); dotSep.setTextSize(10f);
                        f.addView(dotSep);
                        f.addView(icon(ic, tint, 11));
                        TextView st = new TextView(MainActivity.this);
                        st.setText(label); st.setTextColor(labelColor); st.setTextSize(10f);
                        st.setPadding(dp(3), 0, 0, 0);
                        f.addView(st);
                    }
                }
                v.addView(f);
            }
        }

        @Override public int getItemCount() { return rows.size(); }
    }

    private void conversationMenu(String otherKey, String hashref) {
        androidx.appcompat.widget.PopupMenu m = new androidx.appcompat.widget.PopupMenu(this, container);
        m.getMenu().add(0, 1, 0, db.contactName(otherKey) == null ? "Add to contacts" : "View contact key");
        m.getMenu().add(0, 2, 1, "Delete thread");
        m.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) {
                if (db.contactName(otherKey) == null) addContactDialog(otherKey);
                else { copy(otherKey, "Mail key"); toast("Key copied."); }
            } else {
                new AlertDialog.Builder(this).setMessage("Delete this thread from this device?")
                        .setPositiveButton("Delete", (d, w) -> io.execute(() -> { db.deleteThread(hashref); ui.post(this::pop); }))
                        .setNegativeButton("Cancel", null).show();
            }
            return true;
        });
        m.show();
    }

    // ---- COMPOSE ----

    private View buildCompose(String prefillKey, String prefillSubject) {
        LinearLayout col = column();

        // top bar: close + title + Send
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Design.SURFACE);
        head.setPadding(dp(6), dp(10), dp(12), dp(10));
        head.addView(iconBtn(R.drawable.ic_close, v -> pop()));
        TextView t = new TextView(this);
        t.setText("New message");
        t.setTextColor(Design.TEXT); t.setTextSize(17f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(6), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(t);

        final EditText to = new EditText(this);
        final EditText subject = new EditText(this);
        final EditText body = new EditText(this);

        LinearLayout sendBtn = new LinearLayout(this);
        sendBtn.setOrientation(LinearLayout.HORIZONTAL);
        sendBtn.setGravity(Gravity.CENTER_VERTICAL);
        sendBtn.setBackground(Design.roundBg(this, Design.ACCENT, 15));
        sendBtn.setPadding(dp(13), dp(6), dp(13), dp(6));
        sendBtn.addView(icon(R.drawable.ic_send, Design.ON_ACCENT, 13));
        TextView st = new TextView(this);
        st.setText("Send"); st.setTextColor(Design.ON_ACCENT); st.setTextSize(13f); st.setTypeface(Typeface.DEFAULT_BOLD);
        st.setPadding(dp(5), 0, 0, 0);
        sendBtn.addView(st);
        head.addView(sendBtn);
        col.addView(head);

        // To
        LinearLayout toRow = fieldRow("To");
        to.setHint("0x… Mail key");
        styleFieldInput(to, true);
        toRow.addView(to, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        toRow.addView(fieldChip(R.drawable.ic_scan, "Scan", v -> startScan(to)));
        toRow.addView(fieldChip(R.drawable.ic_contacts, null, v -> pickContact(to)));
        if (prefillKey != null) to.setText(prefillKey);
        col.addView(toRow);
        col.addView(hairline());

        // Subject
        LinearLayout subjRow = fieldRow("Subject");
        subject.setHint("(optional — subjects make their own thread)");
        styleFieldInput(subject, false);
        subject.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (prefillSubject != null) subject.setText(prefillSubject);
        subjRow.addView(subject, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(subjRow);
        col.addView(hairline());

        // Body
        body.setHint("Write your message…");
        body.setHintTextColor(Design.DIM2);
        body.setTextColor(Design.TEXT);
        body.setTextSize(14.5f);
        body.setGravity(Gravity.TOP);
        body.setBackground(null);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        body.setPadding(dp(16), dp(12), dp(16), dp(12));
        col.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // attach row
        col.addView(hairline());
        LinearLayout attachRow = new LinearLayout(this);
        attachRow.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout photo = attachChip(R.drawable.ic_photo, "Photo");
        photo.setOnClickListener(v -> {
            String k = acceptKeyShare(to.getText().toString());
            if (!CommsIdentity.isValidPublicId(k)) { toast("Enter a valid Mail key first."); return; }
            imagePickContact = k; imagePickSubject = subject.getText().toString().trim();
            imagePicker.launch("image/*");
        });
        LinearLayout funds = attachChip(R.drawable.ic_coin, "Send funds");
        funds.setOnClickListener(v -> {
            String k = acceptKeyShare(to.getText().toString());
            if (!CommsIdentity.isValidPublicId(k)) { toast("Enter a valid Mail key first."); return; }
            showSendFundsSheet(k, subject.getText().toString().trim());
        });
        attachRow.addView(photo);
        View gap = new View(this);
        attachRow.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        attachRow.addView(funds);
        col.addView(attachRow);

        // delivery hint
        col.addView(hairline());
        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setBackgroundColor(Design.SURFACE);
        hint.setPadding(dp(14), dp(9), dp(14), dp(11));
        hint.addView(icon(R.drawable.ic_chain, Design.ACCENT, 13));
        TextView ht = new TextView(this);
        ht.setText("Delivered with the next block — usually 1–3 minutes. Your message waits in the Outbox until it's on-chain.");
        ht.setTextColor(Design.DIM); ht.setTextSize(10.5f);
        ht.setPadding(dp(7), 0, 0, 0);
        hint.addView(ht, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(hint);

        sendBtn.setOnClickListener(v -> {
            String k = acceptKeyShare(to.getText().toString());
            String subj = subject.getText().toString().trim();
            String text = body.getText().toString();
            if (!CommsIdentity.isValidPublicId(k)) { toast("That doesn't look like a valid Mail key."); return; }
            if (text.trim().isEmpty()) { toast("Write a message first."); return; }
            doSend(sendBtn, k, subj, text, () -> { pop(); openConversation(k, subj); });
        });

        return col;
    }

    private LinearLayout fieldRow(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(4), dp(12), dp(4));
        TextView k = new TextView(this);
        k.setText(label); k.setTextColor(Design.DIM); k.setTextSize(12f);
        k.setWidth(dp(58));
        row.addView(k);
        return row;
    }

    private void styleFieldInput(EditText e, boolean mono) {
        e.setHintTextColor(Design.DIM2);
        e.setTextColor(Design.TEXT);
        e.setTextSize(mono ? 12.5f : 14f);
        if (mono) e.setTypeface(Typeface.MONOSPACE);
        e.setBackground(null);
        e.setSingleLine(true);
        e.setPadding(0, dp(10), dp(8), dp(10));
    }

    private LinearLayout fieldChip(int iconRes, String label, View.OnClickListener click) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Design.outlineBg(this, Design.ACCENT, 11));
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        chip.addView(icon(iconRes, Design.ACCENT, 12));
        if (label != null) {
            TextView t = new TextView(this);
            t.setText(label); t.setTextColor(Design.ACCENT); t.setTextSize(10.5f); t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(4), 0, 0, 0);
            chip.addView(t);
        }
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private LinearLayout attachChip(int iconRes, String label) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Design.roundBg(this, Design.SURFACE2, 14));
        chip.setPadding(dp(11), dp(6), dp(11), dp(6));
        chip.addView(icon(iconRes, Design.ACCENT, 13));
        TextView t = new TextView(this);
        t.setText(label); t.setTextColor(Design.ACCENT); t.setTextSize(11.5f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(5), 0, 0, 0);
        chip.addView(t);
        return chip;
    }

    // ---- CONTACTS ----

    private View buildContacts() {
        LinearLayout col = column();
        LinearLayout head = header("Contacts", false);
        head.addView(iconBtn(R.drawable.ic_plus, v -> addContactDialog(null)));
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
                row.setOnClickListener(v -> push(buildCompose(key, null)));
                row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(MainActivity.this).setMessage("Delete contact " + name + "?")
                            .setPositiveButton("Delete", (d, w) -> io.execute(() -> { db.deleteContact(id); ui.post(() -> { contactNames = null; refreshFolderIfVisible(); }); }))
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
        col.addView(header("Your key", false));
        LinearLayout form = pad(column());
        form.setGravity(Gravity.CENTER_HORIZONTAL);

        form.addView(avatar(myId, myName, 72));

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

    // ---- SETTINGS ----

    private View buildSettings() {
        LinearLayout col = column();
        col.addView(header("Settings", false));
        LinearLayout list = column();

        list.addView(settingsGroup("Appearance"));
        LinearLayout themeRow = settingsRow("Theme", "Paper by day, dark by night — your call");
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(Design.roundBg(this, Design.SURFACE2, 15));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.addView(segOption("Paper", Design.LIGHT, () -> setTheme2("light")));
        seg.addView(segOption("Dark", !Design.LIGHT, () -> setTheme2("dark")));
        themeRow.addView(seg);
        list.addView(themeRow);
        list.addView(hairline());

        list.addView(settingsGroup("Identity"));
        LinearLayout nameRow = settingsRow("Display name", "Sent with every message");
        TextView nv = new TextView(this);
        nv.setText(myName.isEmpty() ? "(not set)" : myName);
        nv.setTextColor(Design.DIM); nv.setTextSize(12.5f);
        nameRow.addView(nv);
        nameRow.addView(chev());
        nameRow.setOnClickListener(v -> {
            final EditText in = input("Your display name");
            in.setText(myName);
            new AlertDialog.Builder(this).setTitle("Display name").setView(in)
                    .setPositiveButton("Save", (d, w) -> {
                        myName = in.getText().toString().trim();
                        io.execute(() -> db.setMeta("myname", myName));
                        refreshFolderIfVisible();
                    })
                    .setNegativeButton("Cancel", null).show();
        });
        list.addView(nameRow);
        list.addView(hairline());
        LinearLayout keyRow = settingsRow("Your key & backup", "QR, export, restore");
        keyRow.addView(chev());
        keyRow.setOnClickListener(v -> showFolder(Folder.IDENTITY));
        list.addView(keyRow);
        list.addView(hairline());

        list.addView(settingsGroup("About"));
        LinearLayout helpRow = settingsRow("How delivery works", "Messages travel as coins — next block, ~1–3 min");
        helpRow.addView(chev());
        helpRow.setOnClickListener(v -> push(buildHelp()));
        list.addView(helpRow);
        list.addView(hairline());
        LinearLayout verRow = settingsRow("Version", "Minima Mail v" + BuildConfig.VERSION_NAME);
        list.addView(verRow);

        ScrollView sv = new ScrollView(this); sv.addView(list);
        col.addView(sv);
        return col;
    }

    private void setTheme2(String theme) {
        if (theme.equals(db.getMeta("theme", "light"))) return;
        db.setMeta("theme", theme);
        Design.load(theme);
        recreate();
    }

    private TextView settingsGroup(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(java.util.Locale.ENGLISH));
        t.setTextColor(Design.DIM2); t.setTextSize(10.5f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(16), dp(18), dp(16), dp(6));
        return t;
    }

    private LinearLayout settingsRow(String title, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        LinearLayout grow = new LinearLayout(this);
        grow.setOrientation(LinearLayout.VERTICAL);
        grow.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title); t.setTextColor(Design.TEXT); t.setTextSize(14f);
        grow.addView(t);
        if (detail != null) {
            TextView d = new TextView(this);
            d.setText(detail); d.setTextColor(Design.DIM); d.setTextSize(11f);
            d.setPadding(0, dp(1), 0, 0);
            grow.addView(d);
        }
        row.addView(grow);
        return row;
    }

    private ImageView chev() {
        ImageView iv = icon(R.drawable.ic_chev, Design.DIM2, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(14));
        lp.leftMargin = dp(8);
        iv.setLayoutParams(lp);
        return iv;
    }

    private TextView segOption(String label, boolean on, final Runnable click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(11.5f);
        t.setPadding(dp(11), dp(4), dp(11), dp(4));
        if (on) {
            t.setTextColor(Design.ON_ACCENT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setBackground(Design.roundBg(this, Design.ACCENT, 12));
        } else {
            t.setTextColor(Design.DIM);
        }
        t.setOnClickListener(v -> click.run());
        return t;
    }

    private View buildHelp() {
        LinearLayout col = column();
        col.addView(header("How delivery works", true));
        ScrollView sv = new ScrollView(this);
        TextView t = new TextView(this);
        t.setPadding(dp(16), dp(8), dp(16), dp(24));
        t.setTextColor(Design.DIM); t.setTextSize(13f); t.setLineSpacing(dp(4), 1f);
        t.setText("Minima Mail is on-chain email: end-to-end encrypted messages carried by the Minima blockchain itself.\n\n" +
                "• Your identity is a key derived from your Minima seed — recoverable on any device that restores the same seed.\n\n" +
                "• Each message is encrypted on your device and posted as a tiny coin to a shared address; only the recipient can decrypt it. Sender, recipient and content are hidden on-chain.\n\n" +
                "• Delivery rides the chain: a message arrives with the next block — usually 1–3 minutes, sometimes longer. That's why Mail behaves like email, not instant chat. A message you send waits in the Outbox until it's on-chain, then moves to Sent; \"Confirmed\" means the chain has moved past its block.\n\n" +
                "• The inbox line \"Checked block N\" tells you how fresh your view is — if the block is recent and there's no new mail, there is no new mail.\n\n" +
                "• Minima is feeless — a message costs a negligible 0.000000001 MINIMA, locked at the shared address.\n\n" +
                "• Subjects are optional. A blank subject keeps one running thread per contact; a subject starts its own thread, and replies stay in it — like email.\n\n" +
                "• This is a native, in-app-encrypted network. It does NOT interoperate with the web ChainMail MiniDapp (which uses Maxima). Native Mail users can message each other, and the desktop minimaMail module speaks the same format.");
        sv.addView(t);
        col.addView(sv);
        return col;
    }

    // ---- send (honest lifecycle: posting → sent → confirmed / failed) ----

    private void doSend(final View btn, String toKey, String subject, String message, Runnable onOk) {
        if (crypto == null) { toast("Still connecting to your node…"); return; }
        if (!CommsIdentity.isValidPublicId(toKey)) { toast("That doesn't look like a valid Mail key."); return; }
        if (message == null || message.trim().isEmpty()) return;

        btn.setEnabled(false);
        final MailMessage m = new MailMessage();
        m.frompublickey = myId; m.fromname = myName; m.topublickey = toKey;
        m.subject = subject == null ? "" : subject; m.message = message;
        m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
        m.incoming = false; m.read = true;
        m.payaddr = myPayaddr;
        m.status = "posting"; m.sentblock = chainBlock;
        m.hashref = MailText.threadKey(myId, toKey, m.subject);

        io.execute(() -> {                                   // appears in the thread + Outbox immediately
            m.id = db.insert(m);
            ui.post(() -> {
                btn.setEnabled(true);
                if (onOk != null) onOk.run();
                reloadConversation(m.hashref);
                postMessage(m);
            });
        });
    }

    /** Post an already-inserted message to the chain and keep its status honest. */
    private void postMessage(final MailMessage m) {
        CommsTransport.send(node, crypto, m, new CommsTransport.SendCb() {
            @Override public void onSent() {
                io.execute(() -> {
                    db.setStatus(m.id, "sent");
                    db.setSentBlock(m.id, chainBlock);
                    ui.post(() -> { reloadConversation(m.hashref); refreshFolderIfVisible(); });
                });
            }
            @Override public void onFailed(String err) {
                io.execute(() -> {
                    db.setStatus(m.id, "failed");
                    ui.post(() -> {
                        toast("Send failed: " + err + " — kept in Outbox.");
                        reloadConversation(m.hashref); refreshFolderIfVisible();
                    });
                });
            }
        });
    }

    private void retrySend(final long id) {
        if (crypto == null) { toast("Still connecting to your node…"); return; }
        io.execute(() -> {
            final MailMessage m = db.message(id);
            if (m == null) return;
            db.setStatus(id, "posting");
            ui.post(() -> { refreshFolderIfVisible(); postMessage(m); });
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
        b.setBackground(m.incoming && Design.LIGHT
                ? Design.cardBg(this, Design.SURFACE2, 18)
                : Design.roundBg(this, m.incoming ? Design.SURFACE2 : Design.ACCENT, 18));
        return b;
    }

    private View paymentBubble(MailMessage m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(Design.roundBg(this, m.incoming ? Design.SURFACE2 : Design.ACCENT, 18));
        int fg = m.incoming ? Design.TEXT : Design.ON_ACCENT;
        int sub = m.incoming ? Design.DIM : (Design.ON_ACCENT & 0x00FFFFFF) | 0xCC000000;
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.addView(icon(R.drawable.ic_coin, sub, 12));
        TextView head = new TextView(this);
        head.setText(m.incoming ? (nameFor(m.frompublickey) + " sent you") : "You sent");
        head.setTextColor(sub); head.setTextSize(11f);
        head.setPadding(dp(5), 0, 0, 0);
        headRow.addView(head);
        TextView amt = new TextView(this);
        amt.setText(m.amount + "  " + (m.tokenname == null || m.tokenname.isEmpty() ? "MINIMA" : m.tokenname));
        amt.setTextColor(fg); amt.setTextSize(20f); amt.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(headRow); card.addView(amt);
        if (m.message != null && !m.message.isEmpty()) {
            TextView memo = new TextView(this);
            memo.setText(m.message); memo.setTextColor(fg); memo.setTextSize(13f); memo.setPadding(0, dp(4), 0, 0);
            card.addView(memo);
        }
        return card;
    }

    // ---- send funds ----

    private void showSendFundsSheet(final String otherKey, final String subject) {
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
                    : "Auto-fills when they message you / are online. Or scan their QR, or paste their address.");
            hint.setTextColor(Design.DIM2); hint.setTextSize(11f); hint.setPadding(0, dp(4), 0, 0);
            box.addView(hint);
            TextView scanAddr = textButton("Scan their QR for their address");
            scanAddr.setOnClickListener(v -> startAddrScan(addr));
            box.addView(scanAddr);
            if (myPayaddr.isEmpty()) fetchMyPayaddr();
            TextView mine = new TextView(this);
            mine.setText("You'll receive at: " + (myPayaddr.isEmpty() ? "(getting your address…)" : shortKey(myPayaddr)));
            mine.setTextColor(Design.DIM2); mine.setTextSize(10f); mine.setPadding(0, dp(8), 0, 0);
            box.addView(mine);
            box.addView(label("Note (optional)"));
            final EditText memo = input("What's it for?");
            box.addView(memo);
            ScrollView sv = new ScrollView(this); sv.addView(box);
            AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Send funds to " + nameFor(otherKey)).setView(sv)
                    .setPositiveButton("Review", (d, w) ->
                            trySendFunds(otherKey, subject, sel[0], sel[1], sel[2], amt.getText().toString().trim(), addr.getText().toString().trim(), memo.getText().toString()))
                    .setNegativeButton("Cancel", null).create();
            dlg.setOnDismissListener(d -> { modalOpen = false; openPayAddrField = null; openPayContact = null; });
            modalOpen = true;
            dlg.show();
        });
    }

    private void trySendFunds(String otherKey, String subject, String tokenid, String tokenname, String balanceStr, String amountStr, String addr, String memo) {
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
                .setMessage("To " + nameFor(otherKey) + "\n" + payaddr + "\n\nThis sends real funds and cannot be undone.")
                .setPositiveButton("Send", (d, w) -> doPay(otherKey, subject, payaddr, tokenid, tokenname, amountStr, memo))
                .setNegativeButton("Cancel", null).show();
    }

    /** A real Minima receiving address: 0x + exactly 64 hex (32-byte hash), or an Mx… address.
     *  Crucially this REJECTS a Mail key (0x + 130 hex), which must never be used as a pay address. */
    private static boolean looksLikeMinimaAddress(String a) {
        if (a == null) return false;
        a = a.trim();
        if (a.startsWith("Mx")) return a.length() >= 40 && a.length() <= 80;
        if (a.startsWith("0x")) {
            String h = a.substring(2);
            return h.length() == 64 && h.matches("[0-9A-Fa-f]+");
        }
        return false;
    }

    private void doPay(final String otherKey, final String subject, String payaddr, final String tokenid, final String tokenname, final String amountStr, final String memo) {
        final AlertDialog progress = progressDialog("Sending " + amountStr + " " + tokenname,
                "Posting to the chain — this can take a few seconds…");
        progress.show();
        node.cmd("send amount:" + amountStr + " address:" + payaddr + " tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                boolean ok = j.optBoolean("status", false) || j.optBoolean("pending", false);
                if (!ok) { safeDismiss(progress); payResult(false, "The node rejected the payment.\n" + j.optString("error", ""), null); return; }
                JSONObject r = j.optJSONObject("response");
                final String txid = r != null ? r.optString("txpowid", "") : "";
                // The funds have left — record it locally (shows the bubble) and confirm, then notify the recipient.
                final MailMessage m = paymentMsg(otherKey, subject, tokenid, tokenname, amountStr, memo, txid);
                io.execute(() -> {
                    m.id = db.insert(m);
                    ui.post(() -> {
                        safeDismiss(progress);
                        reloadConversation(m.hashref);
                        payResult(true, "Sent " + amountStr + " " + tokenname + " to " + nameFor(otherKey) + ".", txid);
                    });
                });
                CommsTransport.send(node, crypto, m, new CommsTransport.SendCb() {   // best-effort chat receipt
                    @Override public void onSent() {}
                    @Override public void onFailed(String e) {}
                });
            }
            @Override public void onError(String m) { safeDismiss(progress); payResult(false, "Payment failed: " + m, null); }
        });
    }

    private MailMessage paymentMsg(String otherKey, String subject, String tokenid, String tokenname, String amountStr, String memo, String txid) {
        MailMessage m = new MailMessage();
        m.type = "payment";
        m.frompublickey = myId; m.fromname = myName; m.topublickey = otherKey;
        m.subject = subject == null ? "" : subject;
        m.message = memo == null ? "" : memo;
        m.amount = amountStr; m.tokenid = tokenid; m.tokenname = tokenname; m.txpowid = txid;
        m.payaddr = myPayaddr;
        m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
        m.incoming = false; m.read = true; m.status = "sent"; m.sentblock = chainBlock;
        m.hashref = MailText.threadKey(myId, otherKey, m.subject);
        return m;
    }

    private void payResult(boolean ok, String message, String txid) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(ok ? "Payment sent" : "Payment failed")
                .setMessage(message + (ok && txid != null && !txid.isEmpty() ? "\n\nTx: " + shortKey(txid) : ""))
                .setPositiveButton("OK", null);
        if (ok && txid != null && !txid.isEmpty()) b.setNeutralButton("Copy tx id", (d, w) -> { copy(txid, "Tx id"); toast("Copied."); });
        b.show();
    }

    private void safeDismiss(AlertDialog d) { try { if (d != null && d.isShowing()) d.dismiss(); } catch (Exception ignored) {} }

    private AlertDialog progressDialog(String title, String msg) {
        return new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setCancelable(false).create();
    }

    private byte[] readBytes(Uri uri) {
        try (java.io.InputStream is = getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }

    // ---- images ----

    private void sendImage(final String otherKey, final String subject, final Uri uri) {
        if (crypto == null) { toast("Connect to your node first."); return; }
        final AlertDialog progress = progressDialog("Sending image", "Compressing + posting to the chain…");
        progress.show();
        io.execute(() -> {
            try {
                byte[] raw = readBytes(uri);
                MailMessage m = (raw != null && raw.length <= 16000)
                        ? imageMsg(otherKey, subject, raw)                                              // keep original (GIF animated, PNG alpha)
                        : imageMsg(otherKey, subject, Images.compressToFit(MainActivity.this, uri, 15000)); // else shrink to JPEG
                if (m == null) { ui.post(() -> { safeDismiss(progress); toast("Couldn't read that image."); }); return; }
                String blob = crypto.seal(otherKey, m.toWire());            // seal ONCE, then size-check + send
                if (blob.length() / 2 > 47000) {                            // too big once sealed → compress harder
                    MailMessage m2 = imageMsg(otherKey, subject, Images.compressToFit(MainActivity.this, uri, 9000));
                    if (m2 != null) { m = m2; blob = crypto.seal(otherKey, m.toWire()); }
                }
                if (blob.length() / 2 > 49000) { ui.post(() -> { safeDismiss(progress); toast("Image too large to send on-chain."); }); return; }
                final MailMessage msg = m;
                msg.id = db.insert(msg);                                     // in the thread + Outbox as "posting"
                final String sealed = blob;
                ui.post(() -> { safeDismiss(progress); reloadConversation(msg.hashref); refreshFolderIfVisible(); });
                CommsTransport.sendBlob(node, sealed, new CommsTransport.SendCb() {
                    @Override public void onSent() { io.execute(() -> {
                        db.setStatus(msg.id, "sent"); db.setSentBlock(msg.id, chainBlock);
                        ui.post(() -> { reloadConversation(msg.hashref); refreshFolderIfVisible(); });
                    }); }
                    @Override public void onFailed(String e) { io.execute(() -> {
                        db.setStatus(msg.id, "failed");
                        ui.post(() -> { toast("Image send failed: " + e + " — kept in Outbox."); reloadConversation(msg.hashref); refreshFolderIfVisible(); });
                    }); }
                });
            } catch (Throwable t) { ui.post(() -> { safeDismiss(progress); toast("Image send failed."); }); }
        });
    }

    private MailMessage imageMsg(String otherKey, String subject, byte[] jpeg) {
        if (jpeg == null) return null;
        MailMessage m = new MailMessage();
        m.type = "image";
        m.image = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
        m.frompublickey = myId; m.fromname = myName; m.topublickey = otherKey;
        m.subject = subject == null ? "" : subject;
        m.message = ""; m.payaddr = myPayaddr;
        m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
        m.incoming = false; m.read = true; m.status = "posting"; m.sentblock = chainBlock;
        m.hashref = MailText.threadKey(myId, otherKey, m.subject);
        return m;
    }

    private View imageBubble(final MailMessage m) {
        final ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.62));
        iv.setMaxHeight(dp(300));
        iv.setMinimumWidth(dp(120)); iv.setMinimumHeight(dp(120));
        iv.setBackground(Design.roundBg(this, m.incoming ? Design.SURFACE2 : Design.ACCENT, 14));
        int p = dp(3); iv.setPadding(p, p, p, p);
        iv.setOnClickListener(v -> showFullImage(m.image));

        Bitmap cached = imgCache.get(m.randomid);
        if (cached != null) { iv.setImageBitmap(cached); return iv; }   // hit → no decode

        io.execute(() -> {                                              // decode off the UI thread
            try {
                byte[] bytes = android.util.Base64.decode(m.image, android.util.Base64.NO_WRAP);
                if (isGif(bytes)) {                                     // animated → decode fresh, don't cache
                    Drawable d = decodeImage(m.image);
                    if (d != null) ui.post(() -> {
                        iv.setImageDrawable(d);
                        if (d instanceof android.graphics.drawable.AnimatedImageDrawable) ((android.graphics.drawable.AnimatedImageDrawable) d).start();
                    });
                } else {
                    Bitmap b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (b != null) { imgCache.put(m.randomid, b); ui.post(() -> iv.setImageBitmap(b)); }
                }
            } catch (Exception ignored) {}
        });
        return iv;
    }

    private static boolean isGif(byte[] b) { return b != null && b.length > 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F'; }

    private void showFullImage(String b64) {
        Drawable d = decodeImage(b64);
        if (d == null) return;
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(d);
        if (d instanceof android.graphics.drawable.AnimatedImageDrawable) ((android.graphics.drawable.AnimatedImageDrawable) d).start();
        iv.setAdjustViewBounds(true);
        ScrollView sv = new ScrollView(this); sv.addView(iv);
        new AlertDialog.Builder(this).setView(sv).setPositiveButton("Close", null).show();
    }

    /** Decode a base64 image to a Drawable — animates GIFs, keeps PNG alpha, falls back to a bitmap. */
    private Drawable decodeImage(String b64) {
        try {
            byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
            try {
                android.graphics.ImageDecoder.Source src = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes));
                return android.graphics.ImageDecoder.decodeDrawable(src);
            } catch (Throwable t) {
                Bitmap b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                return b != null ? new android.graphics.drawable.BitmapDrawable(getResources(), b) : null;
            }
        } catch (Exception e) { return null; }
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

    private void startScan(EditText target) { launchScan(target, false); }
    private void startAddrScan(EditText target) { launchScan(target, true); }

    private void launchScan(EditText target, boolean isAddress) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA}, 2);
        }
        pendingScanTarget = target;
        pendingScanIsAddress = isAddress;
        ScanOptions o = new ScanOptions();
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        o.setPrompt(isAddress ? "Scan their Mail-key QR for their address" : "Scan a Mail key");
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
                    if (pass.length() < 8) { toast("Use a passphrase of at least 8 characters."); return; }
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
                        o.put("to", m.topublickey); o.put("subject", m.subject == null ? "" : m.subject);
                        o.put("message", m.message); o.put("randomid", m.randomid);
                        o.put("incoming", m.incoming); o.put("read", m.read); o.put("date", m.date); o.put("status", m.status);
                        o.put("type", m.type == null ? "text" : m.type);
                        if ("payment".equals(m.type)) {
                            o.put("amount", m.amount); o.put("tokenid", m.tokenid);
                            o.put("tokenname", m.tokenname); o.put("txpowid", m.txpowid);
                        }
                        if ("image".equals(m.type)) o.put("image", m.image == null ? "" : m.image);
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
                    m.subject = o.optString("subject", "");
                    m.message = o.optString("message"); m.randomid = o.optString("randomid");
                    m.incoming = o.optBoolean("incoming"); m.read = o.optBoolean("read"); m.date = o.optLong("date"); m.status = o.optString("status", "");
                    m.type = o.optString("type", "text");
                    m.amount = o.optString("amount", ""); m.tokenid = o.optString("tokenid", "");
                    m.tokenname = o.optString("tokenname", ""); m.txpowid = o.optString("txpowid", "");
                    m.image = o.optString("image", "");
                    db.insert(m);
                }
                ui.post(() -> { contactNames = null; adoptIdentity(restored); toast("Restored."); showFolder(Folder.INBOX); requestScan(); });
            } catch (Exception e) { ui.post(() -> toast("Restore failed — wrong passphrase or bad file.")); }
        });
    }

    // ---- dialogs ----

    private void addContactDialog(String prefillKey) {
        LinearLayout box = pad(column());
        final EditText name = input("Name");
        final EditText key = input("0x… their Mail key");
        if (prefillKey != null) key.setText(prefillKey);
        LinearLayout scan = new LinearLayout(this);
        scan.setOrientation(LinearLayout.HORIZONTAL);
        scan.setGravity(Gravity.CENTER_VERTICAL);
        scan.setPadding(dp(4), dp(10), dp(8), dp(6));
        scan.addView(icon(R.drawable.ic_scan, Design.ACCENT, 14));
        TextView stext = new TextView(this);
        stext.setText("Scan QR"); stext.setTextColor(Design.ACCENT); stext.setTextSize(13f);
        stext.setPadding(dp(6), 0, 0, 0);
        scan.addView(stext);
        scan.setOnClickListener(v -> startScan(key));
        box.addView(label("Name")); box.addView(name);
        box.addView(label("Mail key")); box.addView(key); box.addView(scan);
        new AlertDialog.Builder(this).setTitle("Add contact").setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String n = name.getText().toString().trim(), k = acceptKeyShare(key.getText().toString());
                    if (n.isEmpty() || !CommsIdentity.isValidPublicId(k)) { toast("Enter a name and a valid Mail key."); return; }
                    io.execute(() -> { db.addContact(n, k); ui.post(() -> { contactNames = null; refreshFolderIfVisible(); }); });
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
                    .setContentText(count == 1 ? "New mail" : count + " new messages")
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

    /** Folder header: hamburger (root folders) or back (pushed screens) + title. */
    private LinearLayout header(String title, boolean back) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE);
        h.setPadding(dp(8), dp(12), dp(8), dp(12));
        if (back) {
            h.addView(iconBtn(R.drawable.ic_back, v -> pop()));
        } else {
            ImageView burger = iconBtn(R.drawable.ic_menu, v -> drawer.openDrawer(Gravity.START));
            h.addView(burger);
        }
        TextView t = new TextView(this);
        t.setText(title); t.setTextColor(Design.TEXT); t.setTextSize(19f); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(8), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        h.addView(t);
        return h;
    }

    /** A tinted vector icon. */
    private ImageView icon(int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    /** A padded, tappable icon button for headers/bars. */
    private ImageView iconBtn(int res, View.OnClickListener click) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(Design.DIM);
        int p = dp(10);
        iv.setPadding(p, p, p, p);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        if (click != null) iv.setOnClickListener(click);
        return iv;
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
        e.setBackground(Design.cardBg(this, Design.SURFACE, 10));
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
        b.setBackground(Design.outlineBg(this, Design.ACCENT, 10));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    // ---- formatting ----

    private String nameFor(String key) {
        if (key == null) return "(unknown)";
        if (contactNames == null) {
            java.util.HashMap<String, String> m = new java.util.HashMap<>();
            for (String[] c : db.contacts()) m.put(c[2], c[1]);
            contactNames = m;
        }
        String n = contactNames.get(key);
        return n != null ? n : shortKey(key);
    }

    private static String shortKey(String key) {
        if (key == null) return "—";
        String h = key.startsWith("0x") ? key.substring(2) : key;
        if (h.length() <= 14) return key;
        return "0x" + h.substring(0, 8) + "…" + h.substring(h.length() - 6);
    }

    private String previewText(MailMessage t) {
        if ("image".equals(t.type)) return "Photo";
        if ("payment".equals(t.type)) return t.amount + " " + (t.tokenname == null || t.tokenname.isEmpty() ? "MINIMA" : t.tokenname)
                + (t.message == null || t.message.isEmpty() ? "" : " — " + oneLine(t.message));
        return oneLine(t.message);
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

    private int dp(int v) { return Design.dp(this, v); }

    private void copy(String text, String label) {
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
