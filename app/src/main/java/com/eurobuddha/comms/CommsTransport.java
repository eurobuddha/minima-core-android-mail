package com.eurobuddha.comms;

import org.json.JSONObject;

/**
 * Sends a message: seal it in-app, then post it as a 0.001-Minima coin whose state[99] carries the
 * sealed blob, to the shared ChainMail address. The node is pure transport — it never sees plaintext.
 */
public final class CommsTransport {

    /** "CHAINMAIL" in hex — the one shared address everyone posts to + monitors (privacy is by encryption). */
    public static final String CHAINMAIL_ADDRESS = "0x434841494E4D41494C";

    /** Minima is feeless, so the only cost is this amount, which is locked (lost) at the shared address.
     *  1 nano-Minima — effectively free, still a clean valid coin. */
    public static final String MESSAGE_AMOUNT = "0.000000001";

    public interface SendCb {
        void onSent();
        void onFailed(String message);
    }

    public static void send(NodeApi node, CryptoProvider crypto, MailMessage m, SendCb cb) {
        final String blob;
        try {
            blob = crypto.seal(m.topublickey, m.toWire());
        } catch (Exception e) {
            cb.onFailed("encrypt failed: " + e.getMessage());
            return;
        }
        sendBlob(node, blob, cb);
    }

    /** Post an already-sealed blob — lets a caller seal once (e.g. to size-check it) and then send it.
     *  The send is pinned to a signable wallet coin ({@link SendPin}) so shared-node beacon dust can't
     *  be selected and NPE the signer — a failed send still burns a WOTS key-use for nothing. */
    public static void sendBlob(NodeApi node, String blobHex, SendCb cb) {
        try {
            JSONObject state = new JSONObject();
            state.put("99", "0x" + blobHex);   // hex-typed state value, read back the same way
            String cmd = "send amount:" + MESSAGE_AMOUNT + " address:" + CHAINMAIL_ADDRESS + " tokenid:0x00 state:" + state;
            SendPin.pin(node, cmd, pinned -> node.cmd(pinned, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    // status:true = accepted; pending:true = queued via the pending app (node locked)
                    if (j.optBoolean("status", false) || j.optBoolean("pending", false)) cb.onSent();
                    else cb.onFailed(j.optString("error", "the node rejected the send"));
                }
                @Override public void onError(String message) { cb.onFailed(message); }
            }));
        } catch (Exception e) {
            cb.onFailed(e.getMessage());
        }
    }

    private CommsTransport() {}
}
