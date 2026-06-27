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
            return m;
        } catch (Exception e) {
            return null;
        }
    }
}
