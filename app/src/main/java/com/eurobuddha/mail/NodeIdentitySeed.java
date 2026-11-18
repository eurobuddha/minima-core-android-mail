package com.eurobuddha.mail;

import com.eurobuddha.comms.Hex;
import org.json.JSONObject;

/** Consumes a Core vault reply. The caller clears the decoded seed after deriving the Mail keys. */
final class NodeIdentitySeed {
    private NodeIdentitySeed() {}

    static byte[] take(JSONObject reply) {
        JSONObject vault = reply.optJSONObject("response");
        // Core includes both representations. Drop the recovery phrase without reading it.
        Object seed = null;
        if (vault != null) {
            vault.remove("phrase");
            seed = vault.remove("seed");
        }
        if (!Boolean.TRUE.equals(reply.opt("status")) || vault == null)
            throw new IllegalStateException("Check that your node is running and Mail has Admin access in Core → Apps, then try again.");
        if (Boolean.TRUE.equals(vault.opt("locked")))
            throw new IllegalStateException("Unlock your wallet in Minima Core, then return to Mail.");
        if (!Boolean.FALSE.equals(vault.opt("locked")) || !(seed instanceof String)
                || !((String) seed).matches("0[xX][0-9a-fA-F]{64}"))
            throw new IllegalStateException("Your node did not return a valid wallet seed. Check the wallet in Core and try again.");
        return Hex.from((String) seed);
    }
}
