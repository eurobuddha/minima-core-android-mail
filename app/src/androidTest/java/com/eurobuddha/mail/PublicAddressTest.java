package com.eurobuddha.mail;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.Sodium;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;

/** Fixed interop vector independently generated with the desktop Mail libsodium implementation. */
public class PublicAddressTest {
    private static final String KEY = "0xf5c4c50a4d94600b5e627399c7c5f425a10e48eb0b30f8f7f31e121b77a0474f1670f4a31f47d1d404648a8e118e6aa0ae5fef863163c89ac90e333f208269c6";
    private static final String WORDS = "reunion-sting-lazy-tornado-liberty-bubble";
    private static final String SIG = "27c067e038ba079f96ff07b42e0c26e49ee5a91db0963edf364e846192aec8479bde0782bd70c669e956ba3d3f4d6f4a3cad5b4c6fd61f955794690536d5ca05";
    @Before public void load() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MailAddress.init(context);
    }
    @Test public void unlockedVaultProducesTheDesktopMailAddress() throws Exception {
        String sample = "0xa5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5";
        org.json.JSONObject vault = new org.json.JSONObject().put("locked", false)
                .put("seed", sample).put("phrase", "fixture words");
        byte[] seed = NodeIdentitySeed.take(new org.json.JSONObject().put("status", true).put("response", vault));
        CommsIdentity id = CommsIdentity.fromSeed(Sodium.get(), seed);
        assertEquals("0xf1c17825d7c99e703a34e7bc20b13653de34f663fd9ab91a771d2c03b79abb0f"
                + "9ca62f408455ca596471b9476fb61a7755d4af8d59cfaf9eba634f97d616e002", id.publicId());
        assertEquals("assist-purpose-unveil-talk-tomato-subject", MailAddress.phrase(id.publicId()));
    }
    @Test public void desktopAndAndroidShareAddressAndSignedCard() {
        CommsIdentity id = CommsIdentity.fromSeed(Sodium.get(), "test-fixture-only-mail-address-A".getBytes(StandardCharsets.UTF_8));
        MailAddress.Card card = MailAddress.sign(id);
        assertEquals(KEY, card.key);
        assertEquals(WORDS, card.phrase);
        assertEquals(SIG, card.sig);
        assertEquals(KEY, MailAddress.link(card.link()).key);
        assertEquals(KEY, MailAddress.directKey(MailAddress.directLink(KEY)));
        assertEquals(KEY, MailAddress.directKey(WORDS + "\n" + MailAddress.directLink(KEY)));
        assertNull(MailAddress.directKey(WORDS.replace("reunion", "lemon") + "\n" + MailAddress.directLink(KEY)));
        assertNull(MailAddress.link(card.link().replace(SIG, "00" + SIG.substring(2))));
        assertNull(MailAddress.directKey(MailAddress.directLink(KEY).replace("reunion", "lemon")));
        assertNull(MailAddress.directKey(MailAddress.directLink(KEY) + "|address"));
        assertNull(MailAddress.verify(KEY + "1", SIG, WORDS));
    }
    @Test public void completePasteFillsExactlySixPublicBoxes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
            AddressWordEntry entry = new AddressWordEntry(c, android.graphics.Color.BLACK);
            android.widget.LinearLayout grid = (android.widget.LinearLayout) entry.view.getChildAt(0);
            android.widget.LinearLayout row = (android.widget.LinearLayout) grid.getChildAt(0);
            android.widget.EditText first = (android.widget.EditText) row.getChildAt(0);
            first.setText(WORDS);
            assertEquals(WORDS.replace('-', ' '), entry.phrase());
            first.setText(WORDS + "-extra");
            assertNull(entry.phrase());
        });
    }
    @Test public void printedWordsAndGuidedPrefixesUseTheSameDictionary() {
        assertEquals(WORDS, MailAddress.normalize(" Reunion STING   lazy-tornado LIBERTY bubble "));
        for (String word : MailAddress.dictionary()) {
            if (word.length() >= 4) assertEquals(java.util.Collections.singletonList(word),
                    AddressWordEntry.matches(word.substring(0,4), 2));
        }
        assertEquals(WORDS.replace('-', ' '), AddressWordEntry.join(AddressWordEntry.splitPasted(WORDS)));
        assertNull(MailAddress.normalize(WORDS + "-extra"));
    }
}
