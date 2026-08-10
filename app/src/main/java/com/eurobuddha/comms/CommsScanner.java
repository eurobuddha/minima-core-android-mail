package com.eurobuddha.comms;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Scans the shared ChainMail address for messages addressed to me. NEWBLOCK-driven (no MDS callback).
 *
 * SAFETY: a large `coins` reply can take the node down (the IPC Binder/256 KB limits), and the ChainMail
 * address is shared + busy. So this NEVER issues an unbounded query: it always bounds with `depth:`, starts
 * TINY (depth 4) and only grows while replies come back safely; on an over-limit/dropped reply it probes
 * smaller. It is also throttled so NEWBLOCK/NEWBALANCE bursts can't hammer the node. The DB de-dupes
 * messages by (hashref, randomid), so overlapping windows never double-insert.
 */
public final class CommsScanner {

    public interface Listener {
        void onDone(boolean ok, int newCount);
        void onContactPayaddrUpdated(String publicId);
    }

    private static final int START_DEPTH = 4;          // every scan's FIRST query is this small + safe
    private static final int MAX_DEPTH = 64;           // grow no deeper than this in one pass
    private static final long MIN_INTERVAL_MS = 4000;  // throttle: don't hammer the node on bursts

    private final NodeApi node;
    private final CryptoProvider crypto;
    private final CommsDb db;
    private final String myId;
    private final Listener listener;

    private boolean running = false;
    private boolean tracked = false;
    private boolean backfillRun = false;
    private boolean grew = false;        // got at least one safe (non-over-limit) page this pass
    private int depth, targetDepth, newThisRun;
    private long lastScanEnd = 0;
    private final Set<String> repliedReqs = new HashSet<>();   // dedup payaddr-reply by request id
    private final Set<String> seenCoins = new HashSet<>();      // skip re-decrypting overlap while growing

    public CommsScanner(NodeApi node, CryptoProvider crypto, CommsDb db, String myId, Listener listener) {
        this.node = node; this.crypto = crypto; this.db = db; this.myId = myId; this.listener = listener;
    }

    /** When the last scan finished (ms epoch; 0 = never) — for the "checked … ago" freshness line. */
    public long lastScanEnd() { return lastScanEnd; }

    public void scan(final int chainBlock) {
        if (running) return;
        if (System.currentTimeMillis() - lastScanEnd < MIN_INTERVAL_MS) return;   // throttle
        running = true; grew = false; newThisRun = 0; seenCoins.clear();
        backfillRun = db.getMeta("backfilled", "").isEmpty();
        int scannedTip = parseInt(db.getMeta("scanned_tip_block", "0"));
        int gap = (chainBlock > 0) ? Math.max(1, chainBlock - scannedTip + 1) : MAX_DEPTH;
        targetDepth = Math.min(MAX_DEPTH, backfillRun ? MAX_DEPTH : gap);
        depth = Math.min(START_DEPTH, targetDepth);    // ALWAYS start small — never an unbounded query
        if (tracked) { fetch(chainBlock); return; }
        node.cmd("coinnotify action:add address:" + CommsTransport.CHAINMAIL_ADDRESS, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { tracked = true; fetch(chainBlock); }
            @Override public void onError(String m) { fetch(chainBlock); }
        });
    }

    private void fetch(final int chainBlock) {
        // ALWAYS bounded by depth — never an unbounded `coins` (a huge reply can crash the node).
        String cmd = "coins address:" + CommsTransport.CHAINMAIL_ADDRESS + " order:desc depth:" + depth;
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray coins = j.optJSONArray("response");
                if (coins == null || !j.optBoolean("status", true)) { overLimit(chainBlock); return; }
                grew = true;
                newThisRun += process(coins);
                if (depth < targetDepth) { depth = Math.min(depth * 2, targetDepth); fetch(chainBlock); }  // grow while safe
                else finish(chainBlock, true);
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) { finishFail(); return; }
                overLimit(chainBlock);
            }
        });
    }

    /** A page came back dropped/refused. Probe smaller if we've not safely read anything yet; else stop. */
    private void overLimit(int chainBlock) {
        if (!grew && depth > 1) { depth = Math.max(1, depth / 2); fetch(chainBlock); }
        else finish(chainBlock, grew);
    }

    private void finish(int chainBlock, boolean ok) {
        if (ok) db.setMeta("backfilled", "true");
        if (chainBlock > 0) db.setMeta("scanned_tip_block", String.valueOf(chainBlock));
        running = false; lastScanEnd = System.currentTimeMillis();
        listener.onDone(ok, newThisRun);
    }

    private void finishFail() { running = false; lastScanEnd = System.currentTimeMillis(); listener.onDone(false, 0); }

    private int process(JSONArray coins) {
        int newCount = 0;
        for (int i = 0; i < coins.length(); i++) {
            JSONObject coin = coins.optJSONObject(i);
            if (coin == null) continue;
            String cid = coin.optString("coinid", "");
            if (!cid.isEmpty() && !seenCoins.add(cid)) continue;   // already handled this run (grow overlap)
            String blob = statePort99(coin);
            if (blob == null) continue;
            Opened o = crypto.open(blob);
            if (o == null || !o.valid) continue;                   // not for me / bad signature
            MailMessage m = MailMessage.fromWire(o.plaintext);
            if (m == null) continue;
            if (!o.fromPublicId.equals(m.frompublickey)) continue; // signature must match claimed sender
            if (!myId.equals(m.topublickey)) continue;             // addressed to me

            if (m.payaddr != null && !m.payaddr.isEmpty()) {
                db.setContactPayaddr(m.frompublickey, m.payaddr);
                if (listener != null) listener.onContactPayaddrUpdated(m.frompublickey);
            }

            String type = m.type == null ? "text" : m.type;
            if ("payaddr-req".equals(type)) {
                // Auto-reply only on live scans, once per request — never re-reply to old requests during a
                // backfill (that would flood the node with coins). Bound the dedup set so it can't grow forever.
                if (!backfillRun) {
                    if (repliedReqs.size() >= 1000) repliedReqs.clear();
                    if (repliedReqs.add(m.randomid)) sendPayaddrReply(m.frompublickey);
                }
                continue;
            }
            if ("payaddr-reply".equals(type)) continue;            // address stored above; not a visible message

            m.incoming = true; m.read = false;
            m.hashref = MailText.threadKey(m.frompublickey, m.topublickey, m.subject);
            if (db.insert(m) != -1) newCount++;
        }
        return newCount;
    }

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
