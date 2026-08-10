package com.eurobuddha.comms;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * sendpin — dodge the shared-node beacon-dust signing NPE on a MINIMA {@code send}, and stop it
 * burning WOTS key-uses on doomed transactions. Port of the desktop module's {@code sendpin.js}
 * (same algorithm, same fallbacks) so both mail clients behave identically.
 *
 * On a node shared with other apps the wallet can hold ANYONE-CAN-SPEND sentinel/beacon dust —
 * e.g. PandaPools 1-nano coins — that has NO private key. The node's {@code send} auto-selects the
 * SMALLEST coin first, grabs that dust, then tries to sign it → {@code KeyRow.getPrivateKey()} null
 * NPE and the send fails. {@code send} has no coin-exclude flag, but it DOES take a
 * {@code fromaddress:} that restricts funding to one address. So we pin the send to the SMALLEST
 * wallet-SIGNABLE coin that still covers the amount — beacon dust can never be selected, and we
 * disturb the smallest coin possible (never the reserve/main coin). {@code checkaddress} →
 * {@code {simple:true}} is the reliable signable test (beacon addrs → {}).
 * Best-effort: if we can't find a covering signable coin the command goes out unchanged.
 */
public final class SendPin {

    public interface Pinned { void cmd(String command); }

    private static final Pattern TOKENID = Pattern.compile("\\btokenid:(0x[0-9A-Fa-f]+)");
    private static final Pattern AMOUNT = Pattern.compile("\\bamount:([0-9.]+)");

    /** Resolve {@code command} to itself, possibly with {@code fromaddress:<addr>} appended — only
     *  for a MINIMA (0x00 or absent tokenid) {@code send} that doesn't already pin one. */
    public static void pin(NodeApi node, String command, Pinned done) {
        final String c = command == null ? "" : command;
        if (!c.startsWith("send ") || c.contains("fromaddress:")) { done.cmd(c); return; }
        Matcher tok = TOKENID.matcher(c);
        if (tok.find() && !"0x00".equalsIgnoreCase(tok.group(1))) { done.cmd(c); return; }   // non-MINIMA → untouched
        Matcher am = AMOUNT.matcher(c);
        BigDecimal needTmp = BigDecimal.ZERO;
        if (am.find()) { try { needTmp = new BigDecimal(am.group(1)); } catch (Exception ignored) {} }
        final BigDecimal need = needTmp;

        // SENDABLE only — pending/locked/covenant coins can't fund a send.
        node.cmd("coins relevant:true sendable:true tokenid:0x00", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                List<String> candidates = new ArrayList<>();
                try {
                    JSONArray arr = j.optJSONArray("response");
                    List<String[]> all = new ArrayList<>();          // [address, amount]
                    if (arr != null) for (int i = 0; i < arr.length(); i++) {
                        JSONObject x = arr.optJSONObject(i);
                        if (x == null) continue;
                        String addr = x.optString("address", "");
                        String amt = x.optString("amount", "0");
                        try {
                            if (new BigDecimal(amt).signum() > 0 && addr.length() >= 42) all.add(new String[]{addr, amt});
                        } catch (Exception ignored) {}
                    }
                    // (1) A SINGLE coin that covers the amount on its own — minimal disturbance, smallest first.
                    List<String[]> single = new ArrayList<>();
                    for (String[] x : all) if (new BigDecimal(x[1]).compareTo(need) >= 0) single.add(x);
                    single.sort((a, b) -> new BigDecimal(a[1]).compareTo(new BigDecimal(b[1])));
                    LinkedHashSet<String> ordered = new LinkedHashSet<>();
                    for (String[] x : single) ordered.add(x[0]);
                    // (2) No single coin covers → the SIGNABLE address whose coins TOTAL covers (the node
                    //     combines the coins AT that address) — fragmented own-funds instead of the raw send.
                    Map<String, BigDecimal> byAddr = new HashMap<>();
                    for (String[] x : all) byAddr.merge(x[0], new BigDecimal(x[1]), BigDecimal::add);
                    List<Map.Entry<String, BigDecimal>> covering = new ArrayList<>();
                    for (Map.Entry<String, BigDecimal> e : byAddr.entrySet()) if (e.getValue().compareTo(need) >= 0) covering.add(e);
                    covering.sort(Map.Entry.comparingByValue());
                    for (Map.Entry<String, BigDecimal> e : covering) ordered.add(e.getKey());
                    candidates.addAll(ordered);
                } catch (Exception ignored) {}
                tryNext(node, c, candidates, 0, done);
            }
            @Override public void onError(String m) { done.cmd(c); }   // best-effort: unmodified send
        });
    }

    /** Sequentially checkaddress the candidates; the first with {response:{simple:true}} wins. */
    private static void tryNext(NodeApi node, String c, List<String> cands, int i, Pinned done) {
        if (i >= cands.size()) { done.cmd(c); return; }
        final String addr = cands.get(i);
        node.cmd("checkaddress address:" + addr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null && r.optBoolean("simple", false)) done.cmd(c + " fromaddress:" + addr);
                else tryNext(node, c, cands, i + 1, done);
            }
            @Override public void onError(String m) { tryNext(node, c, cands, i + 1, done); }
        });
    }

    private SendPin() {}
}
