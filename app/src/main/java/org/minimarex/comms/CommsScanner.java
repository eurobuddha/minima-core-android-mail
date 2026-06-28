package org.minimarex.comms;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Scans the shared ChainMail address for messages addressed to me. There is no MDS callback over the
 * IPC, so this is driven by NEWBLOCK (debounced) instead of `coinnotify`.
 *
 * The address is shared + busy, and `coins` can't be offset-paged — so we bound the scan with `depth:`
 * (how many blocks back from the tip). On an over-limit/dropped reply (the node's 256 KB IPC cap) we
 * halve the depth and retry, keeping every request safely small. The local DB de-dupes by
 * (hashref, randomid), so re-scanning overlapping windows never double-inserts.
 */
public final class CommsScanner {

    public interface Listener {
        void onDone(boolean ok, int newCount);
        void onContactPayaddrUpdated(String publicId);
    }

    private static final int MAX_DEPTH = 64;   // never scan more than this many blocks in one pass

    private final NodeApi node;
    private final CryptoProvider crypto;
    private final CommsDb db;
    private final String myId;
    private final Listener listener;

    private boolean running = false;
    private boolean tracked = false;
    private int depth;

    public CommsScanner(NodeApi node, CryptoProvider crypto, CommsDb db, String myId, Listener listener) {
        this.node = node; this.crypto = crypto; this.db = db; this.myId = myId; this.listener = listener;
    }

    /** Scan recent blocks for new messages. Pass the current chain tip (0 if unknown → full MAX_DEPTH). */
    public void scan(final int chainBlock) {
        if (running) return;
        running = true;
        boolean backfill = db.getMeta("backfilled", "").isEmpty();
        int scannedTip = parseInt(db.getMeta("scanned_tip_block", "0"));
        // Fresh install (never backfilled) → scan ALL coins at the shared address to recover OLD mail (the
        // identity is seed-derived, so a reinstall decrypts the same history). Then incremental depth-windows.
        depth = backfill ? 0
                : (chainBlock > 0 ? Math.min(MAX_DEPTH, Math.max(1, chainBlock - scannedTip + 1)) : MAX_DEPTH);
        if (tracked) { fetch(chainBlock); return; }
        // The node only indexes/retains coins at a shared address you don't own once it's TRACKED
        // (this is what ChainMail's `coinnotify action:add` does). Track once per run, then scan.
        node.cmd("coinnotify action:add address:" + CommsTransport.CHAINMAIL_ADDRESS, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { tracked = true; fetch(chainBlock); }
            @Override public void onError(String m) { fetch(chainBlock); }
        });
    }

    private void fetch(final int chainBlock) {
        // NB: NO `relevant:` flag — `coins relevant:false address:X` returns nothing at a shared address;
        // a bare `coins address:X` (once tracked) returns them.
        String cmd = "coins address:" + CommsTransport.CHAINMAIL_ADDRESS + " order:desc" + (depth > 0 ? " depth:" + depth : "");
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray coins = j.optJSONArray("response");
                if (coins == null || !j.optBoolean("status", true)) { shrinkOrFail(chainBlock); return; }
                int got = process(coins);
                db.setMeta("backfilled", "true");   // the one-time full backfill has run
                if (chainBlock > 0) db.setMeta("scanned_tip_block", String.valueOf(chainBlock));
                running = false;
                listener.onDone(true, got);
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) { running = false; listener.onDone(false, 0); return; }
                shrinkOrFail(chainBlock);   // an oversized window comes back dropped — shrink + retry
            }
        });
    }

    private void shrinkOrFail(int chainBlock) {
        if (depth == 0) { depth = MAX_DEPTH; fetch(chainBlock); }        // full backfill too big → recent window
        else if (depth > 1) { depth = Math.max(1, depth / 2); fetch(chainBlock); }
        else { running = false; listener.onDone(false, 0); }
    }

    /** Try to open every coin's state[99]; keep the ones for me with a valid signature. Returns new count. */
    private int process(JSONArray coins) {
        int newCount = 0;
        for (int i = 0; i < coins.length(); i++) {
            JSONObject coin = coins.optJSONObject(i);
            if (coin == null) continue;
            String blob = statePort99(coin);
            if (blob == null) continue;
            Opened o = crypto.open(blob);
            if (o == null || !o.valid) continue;                 // not for me / bad signature
            MailMessage m = MailMessage.fromWire(o.plaintext);
            if (m == null) continue;
            if (!o.fromPublicId.equals(m.frompublickey)) continue; // signature must match claimed sender
            if (!myId.equals(m.topublickey)) continue;             // addressed to me

            // Every message piggybacks the sender's receiving address — remember it.
            if (m.payaddr != null && !m.payaddr.isEmpty()) {
                db.setContactPayaddr(m.frompublickey, m.payaddr);
                if (listener != null) listener.onContactPayaddrUpdated(m.frompublickey);
            }

            String type = m.type == null ? "text" : m.type;
            if ("payaddr-req".equals(type)) { sendPayaddrReply(m.frompublickey); continue; }   // auto-answer
            if ("payaddr-reply".equals(type)) continue;            // address stored above; not a visible message

            // text or payment → store as a visible message
            m.incoming = true;
            m.read = false;
            m.hashref = MailText.threadKey(m.frompublickey, m.topublickey, m.subject);
            if (db.insert(m)) newCount++;
        }
        return newCount;
    }

    /** Auto-answer an address request with my receiving address (no UI; not stored as a message). */
    private void sendPayaddrReply(String toPublicId) {
        String myPayaddr = db.getMeta("mypayaddr", "");
        if (myPayaddr.isEmpty()) return;
        MailMessage r = new MailMessage();
        r.type = "payaddr-reply";
        r.frompublickey = myId; r.topublickey = toPublicId; r.payaddr = myPayaddr;
        r.message = ""; r.randomid = MailText.randomId(); r.date = System.currentTimeMillis();
        CommsTransport.send(node, crypto, r, new CommsTransport.SendCb() {
            @Override public void onSent() {}
            @Override public void onFailed(String m) {}
        });
    }

    /** Coin state may be an array [{port,data}] or an object {"99":...} depending on the node build. */
    private static String statePort99(JSONObject coin) {
        Object st = coin.opt("state");
        if (st instanceof JSONObject) {
            String v = ((JSONObject) st).optString("99", "");
            return v.isEmpty() ? null : v;
        }
        if (st instanceof JSONArray) {
            JSONArray a = (JSONArray) st;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && p.optInt("port", -1) == 99) {
                    String v = p.optString("data", "");
                    return v.isEmpty() ? null : v;
                }
            }
        }
        return null;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
