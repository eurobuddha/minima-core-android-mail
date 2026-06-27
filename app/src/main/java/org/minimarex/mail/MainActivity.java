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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
    private String myId, myName = "";
    private boolean paired = false;
    private int chainBlock = 0;

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
                pendingScanTarget.setText(result.getContents().trim());
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
            if (crypto == null) setupIdentity(); else requestScan();
        }
    }

    private void fetchBlock() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) try { chainBlock = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
                if (chainBlock > 0) io.execute(() -> { db.markConfirmed(chainBlock); ui.post(() -> { if (stack.size() == 1) refreshTop(buildInbox()); }); });
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
        scanner = new CommsScanner(node, crypto, db, myId, MainActivity.this::onScanDone);
    }

    private void askForSeed() {
        final EditText in = input("Your 24-word Minima seed phrase");
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
        rv.setAdapter(new ChatListAdapter(db.threads()));
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
        ChatListAdapter(List<MailMessage> d) { data = d; }

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
        }

        @Override public int getItemCount() { return data.size(); }
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
            TextView bubble = new TextView(MainActivity.this);
            bubble.setText(r.m.message);
            bubble.setTextSize(15f);
            bubble.setPadding(dp(14), dp(9), dp(14), dp(9));
            bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.75));
            if (r.m.incoming) {
                bubble.setTextColor(Design.TEXT);
                bubble.setBackground(Design.roundBg(MainActivity.this, Design.SURFACE2, 18));
                rowWrap.setGravity(Gravity.START);
                rowWrap.addView(bubble);
            } else {
                bubble.setTextColor(Design.ON_ACCENT);
                bubble.setBackground(Design.roundBg(MainActivity.this, Design.ACCENT, 18));
                rowWrap.setGravity(Gravity.END);
                rowWrap.addView(bubble);
            }
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
            String k = to.getText().toString().trim();
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
            Bitmap qr = QrUtil.qr(myId, dp(200));
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
        copy.setOnClickListener(v -> { if (myId != null) { copy(myId, "Mail key"); toast("Copied."); } });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        form.addView(copy, clp);

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
        m.getMenu().add(0, 2, 1, "Your Mail key");
        m.getMenu().add(0, 3, 2, "Help");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: push(buildContacts()); return true;
                case 2: push(buildIdentity()); return true;
                case 3: push(buildHelp()); return true;
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
                    String n = name.getText().toString().trim(), k = key.getText().toString().trim();
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
