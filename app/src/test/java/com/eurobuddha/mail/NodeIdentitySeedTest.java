package com.eurobuddha.mail;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NodeIdentitySeedTest {
    private static final String SAMPLE = "0x" + "a5".repeat(32);

    private static JSONObject reply(boolean success, Object locked, Object seed) throws Exception {
        return new JSONObject().put("status", success).put("response",
                new JSONObject().put("locked", locked).put("seed", seed).put("phrase", "fixture"));
    }

    @Test public void consumesOnlyTheBinaryNodeSeed() throws Exception {
        JSONObject reply = reply(true, false, SAMPLE);
        byte[] expected = new byte[32];
        java.util.Arrays.fill(expected, (byte) 0xa5);
        assertArrayEquals(expected, NodeIdentitySeed.take(reply));
        assertFalse(reply.getJSONObject("response").has("phrase"));
        assertFalse(reply.getJSONObject("response").has("seed"));
    }

    @Test public void lockedDeniedAndMalformedRepliesCannotCreateAnIdentity() throws Exception {
        for (JSONObject input : new JSONObject[]{
                reply(false, false, SAMPLE), reply(true, true, SAMPLE), reply(true, null, SAMPLE),
                reply(true, false, null), reply(true, false, "0x00"), reply(true, false, SAMPLE + "0"),
                reply(true, false, SAMPLE.replace('5', 'z')), reply(true, false, JSONObject.NULL),
                new JSONObject().put("status", false).put("error", "admin required")}) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> NodeIdentitySeed.take(input));
            assertFalse(error.getMessage().contains(SAMPLE));
            assertFalse(input.toString().contains("fixture"));
        }
    }
}
