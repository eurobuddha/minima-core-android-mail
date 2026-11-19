package com.eurobuddha.mail;

import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.SendPin;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Salon discovery windows, with exact card validation and an address-specific state search. */
final class MailBeacon {
    static final String ADDRESS = "0x4D41494C4144445231"; // MAILADDR1
    static final int FRESH = 1000, DISCOVERY = 1500;
    static final String FRESH_STATUS = "Address visible on-chain";
    static final String PENDING_STATUS = "Awaiting Core approval";
    interface Found { void done(MailAddress.Card card, String error); }

    static void find(NodeApi node, String phrase, int depth, Found cb) {
        inspect(node, phrase, depth, (card, block, error) -> cb.done(card, error));
    }

    interface Observation { void done(MailAddress.Card card, long block, String error); }
    static void inspect(NodeApi node, String phrase, int depth, Observation cb) {
        if (!phrase.equals(MailAddress.normalize(phrase))) throw new IllegalArgumentException("Invalid address");
        String tag = MailAddress.tag(phrase);
        node.cmd("coins address:" + ADDRESS + " tokenid:0x00 state:" + tag
                + " simplestate:true order:desc depth:" + depth, new NodeApi.Cb() {
            public void onError(String error) { cb.done(null, 0, error); }
            public void onResult(JSONObject j) {
                JSONArray rows = j.optJSONArray("response");
                if (!j.optBoolean("status") || rows == null) {
                    onError("Could not read address beacons from Core."); return;
                }
                MailAddress.Card found = null;
                long newestBlock = 0;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject coin = rows.optJSONObject(i);
                    if (coin == null || !ADDRESS.equalsIgnoreCase(coin.optString("address"))
                            || !"0x00".equalsIgnoreCase(coin.optString("tokenid"))) continue;
                    JSONObject state = coin.optJSONObject("state");
                    if (state == null || !tag.equalsIgnoreCase(state.optString("0"))) continue;
                    String signature = state.optString("2");
                    if (signature.length() != 130 || !signature.regionMatches(true, 0, "0x", 0, 2)) continue;
                    MailAddress.Card card = MailAddress.verify(state.optString("1"), signature.substring(2), phrase);
                    if (card == null) continue;
                    if (found != null && !found.key.equals(card.key)) {
                        onError("Multiple keys match this address. Ask for a contact link."); return;
                    }
                    found = card;
                    newestBlock = Math.max(newestBlock, coin.optLong("created", 0));
                }
                cb.done(found, newestBlock, null);
            }
        });
    }

    static void renew(NodeApi node, MailAddress.Card card, BooleanSupplier enabled, boolean pending, Consumer<String> done) {
        find(node, card.phrase, FRESH, (fresh, error) -> {
            if (error != null) { done.accept(error); return; }
            if (fresh != null) {
                done.accept(fresh.key.equals(card.key) ? FRESH_STATUS : "Address conflict"); return;
            }
            if (pending) { done.accept(PENDING_STATUS); return; }
            if (!enabled.getAsBoolean()) { done.accept("Renewal stopped"); return; }
            JSONObject state = new JSONObject();
            try { state.put("0", MailAddress.tag(card.phrase)).put("1", card.key).put("2", "0x" + card.sig); }
            catch (org.json.JSONException e) { done.accept("Invalid card"); return; }
            String cmd = "send amount:" + CommsTransport.MESSAGE_AMOUNT + " address:" + ADDRESS + " tokenid:0x00 state:" + state;
            SendPin.pin(node, cmd, pinned -> {
                if (!enabled.getAsBoolean()) { done.accept("Renewal stopped"); return; }
                if (!pinned.contains(" fromaddress:")) { done.accept("No suitable signing coin"); return; }
                node.cmd(pinned, new NodeApi.Cb() {
                    public void onError(String e) { done.accept(e); }
                    public void onResult(JSONObject j) {
                        if (j.optBoolean("pending")) done.accept(PENDING_STATUS);
                        else if (j.optBoolean("status")) done.accept("Beacon submitted to Core");
                        else done.accept("Core rejected the beacon");
                    }
                });
            });
        });
    }
    private MailBeacon() {}
}
