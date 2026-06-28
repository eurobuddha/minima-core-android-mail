package org.minimarex.comms;

import org.json.JSONObject;

/**
 * One message — both the local DB row and (via {@link #toWire}/{@link #fromWire}) the JSON that gets
 * sealed into coin state[99]. Wire fields are sender-authored; {@code hashref/incoming/read} are local.
 */
public class MailMessage {
    public long id;
    public String hashref;        // thread key = threadKey(from, to, subject) — local
    public String fromname;
    public String frompublickey;  // sender publicId
    public String topublickey;    // recipient publicId
    public String subject;
    public String message;
    public String randomid;       // 32 random hex bytes — dedup within a thread
    public boolean incoming;      // local
    public boolean read;          // local
    public long date;
    public String status = "";    // outgoing only: "" / "sent" / "confirmed" — local
    public long sentblock = 0;    // chain block at send time, for the confirmed heuristic — local

    // ---- v3: payments + address exchange ----
    public String type = "text";  // text | payment | payaddr-req | payaddr-reply
    public String payaddr = "";   // sender's Minima receiving address — piggybacked on every message
    public String amount = "";    // payment: decimal string
    public String tokenid = "";   // payment: 0x00 = Minima
    public String tokenname = ""; // payment: display name
    public String txpowid = "";   // payment: the value tx's on-chain id

    /** The sealed payload: only the sender-authored fields travel on-chain. */
    public byte[] toWire() {
        try {
            JSONObject o = new JSONObject();
            o.put("from", frompublickey);
            o.put("fromname", fromname == null ? "" : fromname);
            o.put("to", topublickey);
            o.put("subject", subject == null ? "" : subject);
            o.put("message", message == null ? "" : message);
            o.put("randomid", randomid);
            o.put("date", date);
            o.put("type", type == null ? "text" : type);
            o.put("payaddr", payaddr == null ? "" : payaddr);
            if ("payment".equals(type)) {
                o.put("amount", amount); o.put("tokenid", tokenid);
                o.put("tokenname", tokenname); o.put("txpowid", txpowid);
            }
            return o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("toWire failed", e);
        }
    }

    /** Parse a sealed payload back into a message (hashref/incoming/read set by the caller). */
    public static MailMessage fromWire(byte[] wire) {
        try {
            JSONObject o = new JSONObject(new String(wire, java.nio.charset.StandardCharsets.UTF_8));
            MailMessage m = new MailMessage();
            m.frompublickey = o.optString("from", "");
            m.fromname = o.optString("fromname", "");
            m.topublickey = o.optString("to", "");
            m.subject = o.optString("subject", "");
            m.message = o.optString("message", "");
            m.randomid = o.optString("randomid", "");
            m.date = o.optLong("date", 0);
            m.type = o.optString("type", "text");
            m.payaddr = o.optString("payaddr", "");
            m.amount = o.optString("amount", "");
            m.tokenid = o.optString("tokenid", "");
            m.tokenname = o.optString("tokenname", "");
            m.txpowid = o.optString("txpowid", "");
            return m;
        } catch (Exception e) {
            return null;
        }
    }
}
