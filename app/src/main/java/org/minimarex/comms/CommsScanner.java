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
    }

    private static final int MAX_DEPTH = 64;   // never scan more than this many blocks in one pass

    private final NodeApi node;
    private final CryptoProvider crypto;
    private final CommsDb db;
    private final String myId;
    private final Listener listener;

    private boolean running = false;
    private int depth;

    public CommsScanner(NodeApi node, CryptoProvider crypto, CommsDb db, String myId, Listener listener) {
        this.node = node; this.crypto = crypto; this.db = db; this.myId = myId; this.listener = listener;
    }

    /** Scan recent blocks for new messages. Pass the current chain tip (0 if unknown → full MAX_DEPTH). */
    public void scan(final int chainBlock) {
        if (running) return;
        running = true;
        int scannedTip = parseInt(db.getMeta("scanned_tip_block", "0"));
        depth = (chainBlock > 0)
                ? Math.min(MAX_DEPTH, Math.max(1, chainBlock - scannedTip + 1))
                : MAX_DEPTH;
        fetch(chainBlock);
    }

    private void fetch(final int chainBlock) {
        String cmd = "coins relevant:false address:" + CommsTransport.CHAINMAIL_ADDRESS + " depth:" + depth + " order:desc";
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray coins = j.optJSONArray("response");
                if (coins == null || !j.optBoolean("status", true)) { shrinkOrFail(chainBlock); return; }
                int got = process(coins);
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
        if (depth > 1) { depth = Math.max(1, depth / 2); fetch(chainBlock); }
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
            m.incoming = true;
            m.read = false;
            m.hashref = MailText.threadKey(m.frompublickey, m.topublickey, m.subject);
            if (db.insert(m)) newCount++;
        }
        return newCount;
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
