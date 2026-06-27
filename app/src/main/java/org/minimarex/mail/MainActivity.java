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
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.goterl.lazysodium.LazySodium;

import org.json.JSONObject;
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
import org.minimarex.comms.Sodium;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minima Mail — a native, client-side-encrypted on-chain messenger (a ChainMail clone that needs no
 * Maxima). One Activity hosts a small screen stack: Inbox → Thread / Compose / Contacts / Your key / Help.
 */
public class MainActivity extends AppCompatActivity {

    private static final String CH = "mail";

    private LazySodium ls;
    private NodeApi node;
    private CommsDb db;
    private CryptoProvider crypto;     // null until the identity is derived from the node seed
    private CommsScanner scanner;
    private String myId, myName = "";
    private boolean paired = false;
    private int chainBlock = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private BroadcastReceiver notifyReceiver;

    private LinearLayout root, pairingBanner;
    private FrameLayout container;
    private final ArrayDeque<View> stack = new ArrayDeque<>();

    // ---- lifecycle ----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        showInbox();   // shows whatever is already stored, instantly + offline

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

    @Override public void onBackPressed() {
        if (stack.size() > 1) pop(); else super.onBackPressed();
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    // ---- navigation ----

    private void push(View v) { stack.push(v); showTop(); }
    private void pop() { if (stack.size() > 1) { stack.pop(); showTop(); } }
    private void showInbox() { stack.clear(); stack.push(buildInbox()); showTop(); }
    private void showTop() { container.removeAllViews(); container.addView(stack.peek()); }
    /** Rebuild whatever screen is on top (after data changes). */
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
            }
            @Override public void onError(String m) {}
        });
    }

    /** Derive the identity from the node seed (recoverable). Falls back to manual seed entry if the node
     *  won't hand over the seed over the IPC. */
    private void setupIdentity() {
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (ikm.isEmpty()) { askForSeed(); return; }
                deriveIdentity(ikm);
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
                byte[] seedBytes = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                CommsIdentity id = CommsIdentity.fromSeed(ls, seedBytes);
                CryptoProvider c = new LocalEcCryptoProvider(ls, id);
                ui.post(() -> {
                    crypto = c; myId = id.publicId();
                    scanner = new CommsScanner(node, crypto, db, myId, MainActivity.this::onScanDone);
                    refreshTop(buildInbox());
                    requestScan();
                });
            } catch (Exception e) {
                ui.post(() -> toast("Identity error: " + e.getMessage()));
            }
        });
    }

    private void askForSeed() {
        final EditText in = input("Your 24-word Minima seed phrase");
        in.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("Create your Mail identity")
                .setMessage("Your Mail key is derived from your Minima seed (so it's recoverable). The node didn't share it automatically — paste your seed phrase once. It is used only to derive your key and is never stored.")
                .setView(in)
                .setPositiveButton("Create", (d, w) -> {
                    String s = in.getText().toString().trim();
                    if (!s.isEmpty()) deriveIdentity(s);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- scanning ----

    private void requestScan() {
        if (crypto == null || scanner == null) return;
        scanner.scan(chainBlock);
    }

    private void onScanDone(boolean ok, int newCount) {
        ui.post(() -> {
            if (newCount > 0) {
                notifyNew(newCount);
                if (stack.size() == 1) refreshTop(buildInbox());   // only refresh when the inbox is on top
            }
        });
    }

    // ---- screens ----

    private LinearLayout buildInbox() {
        LinearLayout col = column();
        LinearLayout head = header("Minima Mail", false);
        head.addView(iconBtn("✏", v -> push(buildCompose(null, null))));
        head.addView(iconBtn("⋮", this::showMenu));
        col.addView(head);

        TextView status = new TextView(this);
        status.setTextColor(Design.DIM);
        status.setTextSize(12f);
        status.setPadding(Design.dp(this, 16), 0, Design.dp(this, 16), Design.dp(this, 8));
        if (crypto == null) status.setText(paired ? "Connecting to your node…" : "Enable Minima Mail in Minima Core → Apps.");
        else {
            int unread = db.unreadCount();
            status.setText("You: " + shortKey(myId) + (unread > 0 ? "   ·   " + unread + " unread" : ""));
        }
        col.addView(status);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = column();
        List<MailMessage> threads = db.threads();
        if (threads.isEmpty()) {
            list.addView(empty(crypto == null ? "" : "No messages yet.\nTap ✏ to write one."));
        } else {
            for (MailMessage t : threads) list.addView(threadRow(t));
        }
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private View threadRow(final MailMessage t) {
        String other = myId != null && myId.equals(t.frompublickey) ? t.topublickey : t.frompublickey;
        String name = nameFor(other);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Design.dp(this, 16), Design.dp(this, 12), Design.dp(this, 16), Design.dp(this, 12));
        row.setOnClickListener(v -> openThread(t.hashref));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView nm = new TextView(this);
        nm.setText(name);
        nm.setTextColor(Design.TEXT);
        nm.setTextSize(15f);
        nm.setTypeface(Typeface.DEFAULT_BOLD);
        nm.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(nm);
        TextView time = new TextView(this);
        time.setText(rel(t.date));
        time.setTextColor(Design.DIM2);
        time.setTextSize(11f);
        top.addView(time);
        row.addView(top);

        TextView subj = new TextView(this);
        subj.setText((t.subject == null || t.subject.isEmpty() ? "(no subject)" : t.subject));
        subj.setTextColor(Design.DIM);
        subj.setTextSize(12f);
        subj.setPadding(0, Design.dp(this, 2), 0, 0);
        row.addView(subj);

        TextView snip = new TextView(this);
        String pre = t.incoming ? "" : "You: ";
        snip.setText(pre + oneLine(t.message));
        snip.setTextColor(t.incoming && !t.read ? Design.TEXT : Design.DIM2);
        snip.setTextSize(13f);
        snip.setMaxLines(1);
        row.addView(snip);
        return row;
    }

    private void openThread(String hashref) {
        io.execute(() -> { db.markThreadRead(hashref); ui.post(() -> push(buildThread(hashref))); });
    }

    private LinearLayout buildThread(final String hashref) {
        List<MailMessage> msgs = db.thread(hashref);
        String other = "", subject = "";
        if (!msgs.isEmpty()) {
            MailMessage first = msgs.get(0);
            other = myId != null && myId.equals(first.frompublickey) ? first.topublickey : first.frompublickey;
            subject = first.subject;
        }
        final String otherKey = other, subj = subject;

        LinearLayout col = column();
        LinearLayout head = header(nameFor(other), true);
        head.addView(iconBtn("🗑", v -> confirmDelete(hashref)));
        col.addView(head);
        if (subject != null && !subject.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subject);
            s.setTextColor(Design.ACCENT);
            s.setTextSize(12f);
            s.setPadding(Design.dp(this, 16), 0, Design.dp(this, 16), Design.dp(this, 8));
            col.addView(s);
        }

        ScrollView sv = new ScrollView(this);
        LinearLayout list = column();
        for (MailMessage m : msgs) list.addView(bubble(m));
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // reply bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(Design.dp(this, 12), Design.dp(this, 8), Design.dp(this, 12), Design.dp(this, 8));
        bar.setBackgroundColor(Design.SURFACE);
        final EditText reply = input("Reply…");
        reply.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(reply);
        final Button send = accentButton("Send");
        send.setOnClickListener(v -> {
            String text = reply.getText().toString();
            doSend(send, otherKey, null, subj, text, () -> { reply.setText(""); refreshTop(buildThread(hashref)); });
        });
        bar.addView(send);
        col.addView(bar);
        return col;
    }

    private View bubble(MailMessage m) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setPadding(Design.dp(this, 12), Design.dp(this, 4), Design.dp(this, 12), Design.dp(this, 4));
        TextView b = new TextView(this);
        b.setText(m.message);
        b.setTextColor(Design.TEXT);
        b.setTextSize(14f);
        b.setPadding(Design.dp(this, 12), Design.dp(this, 8), Design.dp(this, 12), Design.dp(this, 8));
        b.setBackground(Design.roundBg(this, m.incoming ? Design.SURFACE2 : 0xFF3A2A12, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.weight = 1f;
        b.setLayoutParams(lp);
        if (m.incoming) { wrap.setGravity(Gravity.START); wrap.addView(b); wrap.addView(spacer()); }
        else { wrap.setGravity(Gravity.END); wrap.addView(spacer()); wrap.addView(b); }
        return wrap;
    }

    private LinearLayout buildCompose(final String prefillKey, final String prefillName) {
        LinearLayout col = column();
        col.addView(header("New message", true));
        LinearLayout form = pad(column());

        form.addView(label("To (Mail key)"));
        final EditText to = input("0x… recipient key");
        if (prefillKey != null) to.setText(prefillKey);
        form.addView(to);
        Button pick = textButton("Pick from contacts");
        pick.setOnClickListener(v -> pickContact(to));
        form.addView(pick);

        form.addView(label("Subject"));
        final EditText subject = input("Subject");
        form.addView(subject);

        form.addView(label("Message"));
        final EditText message = input("Write your message…");
        message.setMinLines(4);
        message.setGravity(Gravity.TOP);
        form.addView(message);

        final Button send = accentButton("Send");
        send.setOnClickListener(v -> doSend(send, to.getText().toString().trim(), prefillName,
                subject.getText().toString(), message.getText().toString(),
                () -> { toast("Sent."); pop(); refreshTop(buildInbox()); }));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Design.dp(this, 16);
        form.addView(send, slp);

        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        col.addView(sv);
        return col;
    }

    private LinearLayout buildContacts() {
        LinearLayout col = column();
        LinearLayout head = header("Contacts", true);
        head.addView(iconBtn("＋", v -> addContactDialog()));
        col.addView(head);
        ScrollView sv = new ScrollView(this);
        LinearLayout list = column();
        List<String[]> cs = db.contacts();
        if (cs.isEmpty()) list.addView(empty("No contacts.\nTap ＋ to add one."));
        for (String[] c : cs) {
            final long id = Long.parseLong(c[0]);
            final String name = c[1], key = c[2];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Design.dp(this, 16), Design.dp(this, 12), Design.dp(this, 16), Design.dp(this, 12));
            row.setOnClickListener(v -> push(buildCompose(key, name)));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setMessage("Delete contact " + name + "?")
                        .setPositiveButton("Delete", (d, w) -> io.execute(() -> { db.deleteContact(id); ui.post(() -> refreshTop(buildContacts())); }))
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
            TextView nm = new TextView(this); nm.setText(name); nm.setTextColor(Design.TEXT); nm.setTextSize(15f); nm.setTypeface(Typeface.DEFAULT_BOLD);
            TextView kv = new TextView(this); kv.setText(shortKey(key)); kv.setTextColor(Design.DIM); kv.setTextSize(12f); kv.setTypeface(Typeface.MONOSPACE);
            row.addView(nm); row.addView(kv);
            list.addView(row);
        }
        sv.addView(list);
        col.addView(sv);
        return col;
    }

    private LinearLayout buildIdentity() {
        LinearLayout col = column();
        col.addView(header("Your Mail key", true));
        LinearLayout form = pad(column());

        form.addView(label("Display name (shown to people you message)"));
        final EditText nm = input("Your name");
        nm.setText(myName);
        form.addView(nm);
        Button saveName = textButton("Save name");
        saveName.setOnClickListener(v -> { myName = nm.getText().toString().trim(); io.execute(() -> db.setMeta("myname", myName)); toast("Saved."); });
        form.addView(saveName);

        form.addView(label("Your Mail key — share this so people can message you"));
        TextView key = new TextView(this);
        key.setText(myId == null ? "(connecting to your node…)" : myId);
        key.setTextColor(Design.TEXT);
        key.setTextSize(12f);
        key.setTypeface(Typeface.MONOSPACE);
        key.setTextIsSelectable(true);
        key.setPadding(Design.dp(this, 12), Design.dp(this, 12), Design.dp(this, 12), Design.dp(this, 12));
        key.setBackground(Design.roundBg(this, Design.SURFACE, 10));
        form.addView(key);
        Button copy = accentButton("Copy my key");
        copy.setEnabled(myId != null);
        copy.setOnClickListener(v -> { copy(myId, "Mail key"); toast("Copied."); });
        form.addView(copy);

        TextView note = new TextView(this);
        note.setText("Your key is derived from your Minima seed, so the same identity comes back on any device with the same seed.");
        note.setTextColor(Design.DIM2);
        note.setTextSize(11f);
        note.setPadding(0, Design.dp(this, 12), 0, 0);
        form.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        col.addView(sv);
        return col;
    }

    private LinearLayout buildHelp() {
        LinearLayout col = column();
        col.addView(header("Help", true));
        ScrollView sv = new ScrollView(this);
        TextView t = new TextView(this);
        t.setPadding(Design.dp(this, 16), Design.dp(this, 8), Design.dp(this, 16), Design.dp(this, 24));
        t.setTextColor(Design.DIM);
        t.setTextSize(13f);
        t.setLineSpacing(Design.dp(this, 4), 1f);
        t.setText(
                "Minima Mail is end-to-end encrypted on-chain messaging.\n\n" +
                "• Your identity is a key derived from your Minima seed — recoverable on any device.\n\n" +
                "• Messages are encrypted on YOUR device and carried inside a tiny on-chain coin to a shared " +
                "address everyone watches. Only the intended recipient can decrypt — the sender, recipient and " +
                "content are all hidden on-chain.\n\n" +
                "• Each message you send is a real 0.001 Minima coin (plus a small fee), so you need a little " +
                "Minima to send.\n\n" +
                "• This is a native, in-app-encrypted network. It does NOT yet interoperate with the web " +
                "ChainMail MiniDapp (that uses Maxima, which this node build doesn't have). Native Mail users " +
                "can message each other today.");
        sv.addView(t);
        col.addView(sv);
        return col;
    }

    // ---- actions ----

    private void doSend(final Button btn, String toKey, String toName, String subject, String message, Runnable onOk) {
        if (crypto == null) { toast("Still connecting to your node…"); return; }
        if (!CommsIdentity.isValidPublicId(toKey)) { toast("That doesn't look like a valid Mail key."); return; }
        if (message == null || message.trim().isEmpty()) { toast("Message is empty."); return; }

        // Posting a coin includes proof-of-work and can take many seconds on a phone. Disable the button
        // so repeated taps don't fire the same message several times.
        final CharSequence orig = btn.getText();
        btn.setEnabled(false);
        btn.setText("Sending…");

        final MailMessage m = new MailMessage();
        m.frompublickey = myId; m.fromname = myName; m.topublickey = toKey;
        m.subject = subject == null ? "" : subject; m.message = message;
        m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
        m.incoming = false; m.read = true;
        m.hashref = MailText.threadKey(myId, toKey, m.subject);

        final String contactName = toName;
        CommsTransport.send(node, crypto, m, new CommsTransport.SendCb() {
            @Override public void onSent() {
                io.execute(() -> {
                    db.insert(m);
                    if (contactName != null && !contactName.isEmpty()) db.addContact(contactName, toKey);
                    ui.post(() -> { btn.setEnabled(true); btn.setText(orig); onOk.run(); });
                });
            }
            @Override public void onFailed(String err) {
                ui.post(() -> { btn.setEnabled(true); btn.setText(orig); toast("Send failed: " + err); });
            }
        });
    }

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

    private void addContactDialog() {
        LinearLayout box = pad(column());
        final EditText name = input("Name");
        final EditText key = input("0x… their Mail key");
        box.addView(label("Name")); box.addView(name);
        box.addView(label("Mail key")); box.addView(key);
        new AlertDialog.Builder(this).setTitle("Add contact").setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String n = name.getText().toString().trim(), k = key.getText().toString().trim();
                    if (n.isEmpty() || !CommsIdentity.isValidPublicId(k)) { toast("Enter a name and a valid Mail key."); return; }
                    io.execute(() -> { db.addContact(n, k); ui.post(() -> refreshTop(buildContacts())); });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void pickContact(EditText target) {
        List<String[]> cs = db.contacts();
        if (cs.isEmpty()) { toast("No contacts yet."); return; }
        String[] names = new String[cs.size()];
        for (int i = 0; i < cs.size(); i++) names[i] = cs.get(i)[1];
        new AlertDialog.Builder(this).setTitle("Pick a contact")
                .setItems(names, (d, which) -> target.setText(cs.get(which)[2])).show();
    }

    private void confirmDelete(String hashref) {
        new AlertDialog.Builder(this).setMessage("Delete this conversation from this device?")
                .setPositiveButton("Delete", (d, w) -> io.execute(() -> { db.deleteThread(hashref); ui.post(() -> { pop(); refreshTop(buildInbox()); }); }))
                .setNegativeButton("Cancel", null).show();
    }

    // ---- notifications ----

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CH, "New mail", NotificationManager.IMPORTANCE_DEFAULT));
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
                    .setAutoCancel(true)
                    .build();
            NotificationManagerCompat.from(this).notify(42, n);
        } catch (Exception ignored) {}
    }

    // ---- small view helpers ----

    private LinearLayout buildPairingBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(Design.SURFACE);
        b.setPadding(Design.dp(this, 16), Design.dp(this, 14), Design.dp(this, 16), Design.dp(this, 14));
        TextView t1 = new TextView(this); t1.setText("Minima Mail is not enabled yet"); t1.setTextColor(Design.ACCENT); t1.setTextSize(15f); t1.setTypeface(Typeface.DEFAULT_BOLD);
        TextView t2 = new TextView(this); t2.setText("Open Minima Core → Apps and enable \"Minima Mail\", then come back."); t2.setTextColor(Design.DIM); t2.setTextSize(13f); t2.setPadding(0, Design.dp(this, 4), 0, Design.dp(this, 8));
        Button open = accentButton("Open Minima Core");
        open.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("org.minimarex.minimacore");
            if (i != null) startActivity(i); else toast("Minima Core isn't installed.");
        });
        b.addView(t1); b.addView(t2); b.addView(open);
        return b;
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout pad(LinearLayout l) { int p = Design.dp(this, 16); l.setPadding(p, p, p, p); return l; }

    private LinearLayout header(String title, boolean back) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE);
        h.setPadding(Design.dp(this, 8), Design.dp(this, 12), Design.dp(this, 8), Design.dp(this, 12));
        if (back) h.addView(iconBtn("‹", v -> pop()));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(back ? Design.TEXT : Design.ACCENT);
        t.setTextSize(18f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(Design.dp(this, 8), 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        h.addView(t);
        return h;
    }

    private TextView iconBtn(String glyph, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(Design.ACCENT);
        t.setTextSize(20f);
        t.setPadding(Design.dp(this, 12), Design.dp(this, 4), Design.dp(this, 12), Design.dp(this, 4));
        t.setOnClickListener(click);
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Design.DIM); t.setTextSize(12f);
        t.setPadding(0, Design.dp(this, 12), 0, Design.dp(this, 4));
        return t;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Design.DIM2);
        e.setTextColor(Design.TEXT);
        e.setTextSize(14f);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setBackground(Design.roundBg(this, Design.SURFACE, 10));
        int p = Design.dp(this, 12);
        e.setPadding(p, p, p, p);
        return e;
    }

    private Button accentButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Design.ON_ACCENT);
        b.setBackground(Design.roundBg(this, Design.ACCENT, 10));
        return b;
    }

    private Button textButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Design.ACCENT);
        b.setBackgroundColor(0x00000000);
        return b;
    }

    private TextView empty(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Design.DIM2);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14f);
        t.setPadding(Design.dp(this, 24), Design.dp(this, 64), Design.dp(this, 24), 0);
        return t;
    }

    private View spacer() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f)); return v; }

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
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static String rel(long ms) {
        if (ms <= 0) return "";
        long d = System.currentTimeMillis() - ms;
        if (d < 60000) return "now";
        if (d < 3600000) return (d / 60000) + "m";
        if (d < 86400000) return (d / 3600000) + "h";
        if (d < 7 * 86400000L) return (d / 86400000) + "d";
        return new java.text.SimpleDateFormat("dd MMM", java.util.Locale.ENGLISH).format(new java.util.Date(ms));
    }

    private void copy(String text, String label) {
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
