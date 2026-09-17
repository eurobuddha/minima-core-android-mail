package com.eurobuddha.comms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * SendPin must ask the node ONLY for coins `send` will accept. `sendable:true` is Wallet.isAddressSimple, not
 * the mempool: `coins` defaults checkmempool:false and still lists a coin committed to an unconfirmed
 * transaction, which `send` refuses unconditionally — pinning fromaddress: to it killed the second payment or
 * message inside the first one's confirmation window ("Insufficient funds.. you only have 0") until a node
 * restart. Same defect and fix as minimaCore Desktop 0.17.2 (main/sendpin.js).
 */
public class SendPinQueryTest {

    private static final String ADDR = "0x" + "AB".repeat(32);

    @Test public void coinsQueryExcludesMempoolCommittedCoins() {
        NodeApi node = mock(NodeApi.class);
        final List<String> commands = new ArrayList<>();
        doAnswer(inv -> {
            String c = inv.getArgument(0);
            commands.add(c);
            NodeApi.Cb cb = inv.getArgument(1);
            JSONObject r = new JSONObject().put("status", true);
            if (c.startsWith("coins ")) r.put("response", new JSONArray().put(new JSONObject().put("amount", "5").put("address", ADDR)));
            else if (c.startsWith("checkaddress ")) r.put("response", new JSONObject().put("simple", true));
            else r.put("response", new JSONObject());
            cb.onResult(r);
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));

        final String[] out = {null};
        SendPin.pin(node, "send address:MxTO amount:1", cmd -> out[0] = cmd);

        assertTrue("first read is the coins query", !commands.isEmpty() && commands.get(0).startsWith("coins "));
        String q = commands.get(0);
        assertTrue("relevant only: " + q, q.contains("relevant:true"));
        assertTrue("sendable only: " + q, q.contains("sendable:true"));
        assertTrue("MUST exclude coins already committed in the mempool: " + q, q.contains("checkmempool:true"));
        assertTrue("MINIMA only: " + q, q.contains("tokenid:0x00"));
        assertEquals("pinned to the covering signable coin", "send address:MxTO amount:1 fromaddress:" + ADDR, out[0]);
    }

    @Test public void nonMinimaSendIsUntouched() {
        NodeApi node = mock(NodeApi.class);
        final List<String> commands = new ArrayList<>();
        doAnswer(inv -> { commands.add(inv.getArgument(0)); return null; }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        final String[] out = {null};
        SendPin.pin(node, "send address:MxTO amount:1 tokenid:0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90", cmd -> out[0] = cmd);
        assertTrue("no node read for a token send", commands.isEmpty());
        assertTrue("command passes through unchanged", out[0].endsWith("0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90"));
    }
}
