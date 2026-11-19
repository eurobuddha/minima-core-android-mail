package com.eurobuddha.mail;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Sodium;
import com.goterl.lazysodium.LazySodium;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** The wallet must only post after a successful, empty freshness query and a signable funding check. */
public class MailBeaconTest {
    @BeforeClass public static void load() throws Exception {
        MailAddress.load(Files.readAllLines(Paths.get("src/main/res/raw/mail_address_words.txt")));
    }

    @Test public void renewalOnlyWritesWhenDueAndExplicitlyEnabled() throws Exception {
        LazySodium sodium = mock(LazySodium.class);
        try (MockedStatic<Sodium> nativeApi = mockStatic(Sodium.class)) {
            nativeApi.when(Sodium::get).thenReturn(sodium);
            when(sodium.cryptoSignDetached(any(), any(), anyLong(), any())).thenReturn(true);
            when(sodium.cryptoSignVerifyDetached(any(), any(), anyInt(), any())).thenReturn(true);
            MailAddress.Card card = MailAddress.sign(CommsIdentity.fromKeys(new byte[32], new byte[32], new byte[32], new byte[64]));
            JSONObject empty = new JSONObject().put("status",true).put("response", new JSONArray());
            JSONObject coin = new JSONObject().put("address",MailBeacon.ADDRESS).put("tokenid","0x00")
                    .put("state", new JSONObject().put("0",MailAddress.tag(card.phrase)).put("1",card.key).put("2","0x"+card.sig));
            JSONObject fresh = new JSONObject().put("status",true).put("response",new JSONArray().put(coin));
            coin.put("created", "2427841");
            NodeApi reader = mock(NodeApi.class);
            doAnswer(call -> {
                ((NodeApi.Cb) call.getArgument(1)).onResult(fresh);
                return null;
            }).when(reader).cmd(anyString(), any(NodeApi.Cb.class));
            AtomicReference<Long> confirmed = new AtomicReference<>();
            MailBeacon.inspect(reader, card.phrase, MailBeacon.DISCOVERY, (observed, block, error) -> {
                assertNull(error);
                assertEquals(card.key, observed.key);
                confirmed.set(block);
            });
            assertEquals(Long.valueOf(2427841), confirmed.get());
            assertEquals(0, run(card, fresh, true, false));
            assertEquals(0, run(card, new JSONObject().put("status",false), true, false));
            assertEquals(0, run(card, empty, false, false));
            assertEquals(0, run(card, empty, true, true));
            assertEquals(1, run(card, empty, true, false));
        }
    }

    private static int run(MailAddress.Card card, JSONObject lookup, boolean enabled, boolean pending) throws Exception {
        String funding = "0x" + "AB".repeat(32);
        NodeApi node = mock(NodeApi.class);
        List<String> posts = new ArrayList<>();
        doAnswer(inv -> {
            String cmd = inv.getArgument(0);
            NodeApi.Cb cb = inv.getArgument(1);
            JSONObject ok = new JSONObject().put("status",true);
            if (cmd.startsWith("coins address:")) {
                assertTrue(cmd.contains(" depth:1000"));
                assertTrue(cmd.contains(" state:" + MailAddress.tag(card.phrase)));
                cb.onResult(lookup);
            } else if (cmd.startsWith("coins relevant:")) {
                cb.onResult(ok.put("response",new JSONArray().put(new JSONObject().put("address",funding).put("amount","10"))));
            } else if (cmd.startsWith("checkaddress ")) {
                cb.onResult(ok.put("response",new JSONObject().put("simple",true)));
            } else {
                assertTrue(cmd.startsWith("send amount:0.000000001 address:" + MailBeacon.ADDRESS));
                assertTrue(cmd.endsWith(" fromaddress:" + funding));
                posts.add(cmd); cb.onResult(ok);
            }
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        AtomicReference<String> status = new AtomicReference<>();
        MailBeacon.renew(node, card, () -> enabled, pending, status::set);
        assertNotNull(status.get());
        return posts.size();
    }
}
