package com.eurobuddha.mail;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.View;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.test.core.app.ActivityScenario;
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
    @Test public void ownAddressDefaultsOnAndRemembersStop() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CommsIdentity id = CommsIdentity.fromSeed(Sodium.get(), "default-publication-test".getBytes(StandardCharsets.UTF_8));
        String key = id.publicId();
        try {
            AddressRenewal.ensureOwn(context, id);
            assertTrue(AddressRenewal.enabled(context, key));
            MailAddress.Card card = MailAddress.link(AddressRenewal.prefs(context).getString(key, ""));
            assertNotNull(card);
            assertEquals(key, card.key);
            AddressRenewal.stopOwn(context, key);
            AddressRenewal.ensureOwn(context, id);
            assertFalse(AddressRenewal.enabled(context, key));
            AddressRenewal.enable(context, card);
            AddressRenewal.ensureOwn(context, id);
            assertTrue(AddressRenewal.enabled(context, key));
        } finally {
            AddressRenewal.disable(context, key);
            AddressRenewal.prefs(context).edit().remove("off-" + key).commit();
        }
    }

    @Test public void openDrawerShowsAddressWhenAnExistingIdentityLoads() {
        CommsIdentity id = CommsIdentity.fromSeed(Sodium.get(), "test-fixture-only-mail-address-A".getBytes(StandardCharsets.UTF_8));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(activity -> {
                android.view.ViewGroup content = activity.findViewById(android.R.id.content);
                DrawerLayout drawer = (DrawerLayout) content.getChildAt(0);
                drawer.openDrawer(androidx.core.view.GravityCompat.START, false);
                activity.adoptIdentity(id);
                java.util.ArrayList<View> matches = new java.util.ArrayList<>();
                drawer.findViewsWithText(matches, "you · " + WORDS, View.FIND_VIEWS_WITH_TEXT);
                assertEquals(1, matches.size());
                assertTrue(matches.get(0).isShown());
            });
        }
    }
    private static android.widget.TextView visibleText(View root, String text) {
        java.util.ArrayList<View> matches = new java.util.ArrayList<>();
        root.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
        for (View v : matches) {
            if (v.isShown() && v instanceof android.widget.TextView
                    && text.contentEquals(((android.widget.TextView) v).getText())) return (android.widget.TextView) v;
        }
        return null;
    }

    @Test public void tappingLatestReplyOpensSavedThreadWithOriginalAndComposer() throws Exception {
        assertEquals(WORDS, MailAddress.display(KEY.substring(2)));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String thread = java.util.UUID.randomUUID().toString();
        try (com.eurobuddha.comms.CommsDb store = new com.eurobuddha.comms.CommsDb(context)) {
            com.eurobuddha.comms.MailMessage m = new com.eurobuddha.comms.MailMessage();
            m.hashref = thread; m.incoming = true; m.frompublickey = KEY;
            m.subject = ""; m.message = "earlier"; m.randomid = "first"; m.date = System.currentTimeMillis();
            long first = store.insert(m);
            m.incoming = false; m.topublickey = KEY; m.frompublickey = "";
            m.message = "yo"; m.randomid = "second"; m.date++;
            long second = store.insert(m);
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                boolean[] clicked = {false};
                for (int i = 0; i < 100 && !clicked[0]; i++) {
                    screen.onActivity(a -> {
                        View root = a.findViewById(android.R.id.content);
                        android.widget.TextView body = visibleText(root, "You: yo");
                        if (body == null) return;
                        android.widget.TextView sender = visibleText(root, WORDS);
                        assertNotNull(sender);
                        assertTrue(body.getTextSize() > sender.getTextSize());
                        assertEquals(1, sender.getMaxLines());
                        View row = body;
                        while (!row.isClickable()) row = (View) row.getParent();
                        clicked[0] = row.performClick();
                    });
                    if (!clicked[0]) Thread.sleep(50);
                }
                assertTrue("tapped the inbox row", clicked[0]);
                for (int i = 0; i < 100 && !store.message(second).read; i++) Thread.sleep(50);
                assertTrue(store.message(second).read);
                assertTrue(store.message(first).read);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                screen.onActivity(a -> {
                    View root = a.findViewById(android.R.id.content);
                    android.widget.TextView body = visibleText(root, "yo"), sender = visibleText(root, WORDS);
                    assertNotNull(body); assertTrue(body.isTextSelectable());
                    assertNotNull(sender); assertEquals(1, sender.getMaxLines());
                    assertTrue(body.getTextSize() > sender.getTextSize());
                    assertNotNull(visibleText(root, "earlier"));
                    View focused = a.getCurrentFocus();
                    assertTrue(focused instanceof android.widget.EditText);
                    assertEquals("Reply", ((android.widget.EditText) focused).getHint().toString());
                    assertNull(visibleText(root, KEY));
                });
            } finally { store.deleteThread(thread); }
        }
    }

    @Test public void savedContactDisplaysAndCopiesItsSixWords() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String key = KEY.substring(2).toUpperCase(java.util.Locale.ROOT);
        try (com.eurobuddha.comms.CommsDb store = new com.eurobuddha.comms.CommsDb(ctx)) {
            assertNull(store.contactName(key));
            assertTrue(store.addContact("Address fixture", key));
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                screen.onActivity(a -> {
                    android.view.ViewGroup content = a.findViewById(android.R.id.content);
                    DrawerLayout drawer = (DrawerLayout) content.getChildAt(0);
                    drawer.openDrawer(androidx.core.view.GravityCompat.START, false);
                    View item = visibleText(drawer, "Contacts");
                    assertNotNull(item);
                    while (!item.isClickable()) item = (View) item.getParent();
                    item.performClick();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                screen.onActivity(a -> {
                    View root = a.findViewById(android.R.id.content);
                    assertNotNull(visibleText(root, "Address fixture"));
                    android.widget.TextView address = visibleText(root, WORDS);
                    assertNotNull(address); assertNull(visibleText(root, key));
                    assertTrue(address.performClick());
                    android.content.ClipboardManager clip = a.getSystemService(android.content.ClipboardManager.class);
                    assertEquals(WORDS, clip.getPrimaryClip().getItemAt(0).getText().toString());
                });
            } finally {
                for (String[] c : store.contacts()) if (c[2].equals(key)) store.deleteContact(Long.parseLong(c[0]));
            }
        }
    }

    @Test public void currencyPickerKeepsMxUsdVisibleAndUsesItsExactTokenAndSpendableBalance() throws Exception {
        org.json.JSONArray balances = new org.json.JSONArray();
        balances.put(new org.json.JSONObject().put("tokenid", "0x00").put("confirmed", "9"));
        assertEquals("0", PaymentCurrency.options(balances).get(1)[2]);
        balances.put(new org.json.JSONObject().put("tokenid", PaymentCurrency.MXUSD.toLowerCase(java.util.Locale.ROOT))
                .put("confirmed", "3").put("sendable", "0.001"));
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> {
                PaymentCurrency picker = new PaymentCurrency(a, PaymentCurrency.options(balances));
                assertEquals("0x00", picker.selected()[0]);
                picker.getChildAt(1).performClick();
                assertArrayEquals(new String[]{PaymentCurrency.MXUSD, "MxUSD", "0.001"}, picker.selected());
                picker.clearCheck();
                assertNull(picker.selected());
            });
        }
    }

    @Test public void composeCompletesSixPrefixesAndResolvesBeforeSend() throws Exception {
        CommsIdentity id = CommsIdentity.fromSeed(Sodium.get(), "test-fixture-only-mail-address-A".getBytes(StandardCharsets.UTF_8));
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        android.widget.LinearLayout[] current = new android.widget.LinearLayout[1];
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(activity -> {
                activity.adoptIdentity(id);
                android.widget.LinearLayout form = (android.widget.LinearLayout) activity.buildCompose(null, null);
                activity.setContentView(form);
                current[0] = form;
                android.widget.TextView status = (android.widget.TextView) form.getChildAt(4);
                status.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int st, int n, int after) {}
                    public void onTextChanged(CharSequence s, int st, int before, int count) {
                        if ("Recipient ready".contentEquals(s)) ready.countDown();
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                android.widget.LinearLayout header = (android.widget.LinearLayout) form.getChildAt(0);
                View send = header.getChildAt(header.getChildCount() - 1);
                android.widget.LinearLayout words = (android.widget.LinearLayout) form.getChildAt(2);
                android.widget.LinearLayout grid = (android.widget.LinearLayout) words.getChildAt(0);
                android.widget.EditText box = null;
                int i = 0;
                for (String word : WORDS.split("-")) {
                    assertFalse(send.isEnabled());
                    box = (android.widget.EditText) ((android.widget.LinearLayout) grid.getChildAt(i / 3)).getChildAt(i % 3);
                    if (i == 0) box.requestFocus();
                    assertTrue("focus on word " + (i + 1), box.hasFocus());
                    for (char letter : word.substring(0, 4).toCharArray()) box.append(String.valueOf(letter));
                    assertEquals(word, box.getText().toString());
                    i++;
                }
                assertEquals(View.GONE, words.getVisibility());
                assertNotNull(visibleText(form, WORDS));
            });
            assertTrue("sixth word resolves before Send", ready.await(30, java.util.concurrent.TimeUnit.SECONDS));
            screen.onActivity(activity -> {
                android.widget.LinearLayout form = current[0];
                android.widget.LinearLayout header = (android.widget.LinearLayout) form.getChildAt(0);
                View send = header.getChildAt(header.getChildCount() - 1);
                android.widget.LinearLayout grid = (android.widget.LinearLayout) ((android.widget.LinearLayout) form.getChildAt(2)).getChildAt(0);
                android.widget.EditText box = (android.widget.EditText) ((android.widget.LinearLayout) grid.getChildAt(1)).getChildAt(2);
                assertTrue(send.isEnabled());
                assertEquals("Recipient ready", ((android.widget.TextView) form.getChildAt(4)).getText().toString());
                android.widget.TextView summary = visibleText(form, WORDS);
                assertTrue(summary.performClick());
                assertFalse(send.isEnabled());
                assertTrue(grid.isShown());
                assertEquals("bubble", box.getText().toString());
                box.setText("bub");
                assertFalse(send.isEnabled());
                ((android.widget.TextView) form.getChildAt(3)).performClick();
                android.widget.EditText raw = (android.widget.EditText) ((android.widget.LinearLayout) form.getChildAt(1)).getChildAt(1);
                raw.setText(MailAddress.directLink(KEY));
                assertTrue(send.isEnabled());
                raw.setText("");
                assertFalse(send.isEnabled());
            });
        }
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
