package com.eurobuddha.mail;

import android.content.Context;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.Sodium;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** V1 hashes the raw box + signing public keys. Each successive 11 bits selects one English word. */
final class MailAddress {
    static final int COUNT = 6;
    private static final Pattern KEY = Pattern.compile("0[xX][0-9a-fA-F]{128}");
    private static final Pattern LINK = Pattern.compile("minimamail://contact/([a-z]+(?:-[a-z]+){5})\\?id=(0x[0-9a-f]{128})&sig=([0-9a-f]{128})");
    private static List<String> words;
    static synchronized void init(Context context) {
        if (words != null) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(R.raw.mail_address_words), StandardCharsets.US_ASCII))) {
            java.util.ArrayList<String> entries = new java.util.ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) entries.add(line.trim());
            load(entries);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    static synchronized void load(List<String> entries) {
        if (entries.size() != 2048) throw new IllegalArgumentException("Invalid address dictionary");
        words = Collections.unmodifiableList(new java.util.ArrayList<>(entries));
    }
    static List<String> dictionary() { return words; }
    static boolean isKey(String s) { return s != null && KEY.matcher(s).matches(); }
    static String canonicalKey(String value) {
        if (value == null || !value.matches("(?i)(?:0x)?[a-f0-9]{128}")) return null;
        return (value.length() == 128 ? "0x" + value : value).toLowerCase(Locale.ROOT);
    }

    static String display(String value) {
        String key = canonicalKey(value);
        return key == null ? value : phrase(key);
    }

    static boolean lookupReady(String input) {
        int bar = input.indexOf('|');
        return canonicalKey(bar < 0 ? input : input.substring(0, bar).trim()) != null
                || input.contains("minimamail:") || normalize(input) != null;
    }

    static String normalize(String s) {
        if (s == null || s.length() > 100) return null;
        String[] parts = s.trim().toLowerCase(Locale.ROOT).split("[\\s-]+", -1);
        if (parts.length != COUNT) return null;
        for (String w : parts) if (Collections.binarySearch(words, w) < 0) return null;
        return String.join("-", parts);
    }
    private static byte[] tagged(String domain, String key) {
        if (!isKey(key)) throw new IllegalArgumentException("Invalid Mail key");
        byte[] prefix = (domain + "\0").getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(prefix.length + 64).put(prefix).put(Hex.from(key)).array();
    }
    static String phrase(String key) {
        byte[] hash;
        try { hash = MessageDigest.getInstance("SHA-256").digest(tagged("minima-mail-address-v1", key)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        String[] result = new String[COUNT];
        for (int word = 0; word < COUNT; word++) {
            int index = 0;
            for (int bit = word * 11; bit < (word + 1) * 11; bit++)
                index = index * 2 + ((hash[bit / 8] >>> (7 - bit % 8)) & 1);
            result[word] = words.get(index);
        }
        return String.join("-", result);
    }
    static String tag(String phrase) {
        return "0x" + Hex.to(phrase.getBytes(StandardCharsets.US_ASCII)).toUpperCase(Locale.ROOT);
    }
    static final class Card {
        final String key, sig, phrase;
        private Card(String key, String sig) {
            this.key = key.toLowerCase(Locale.ROOT);
            this.sig = sig.toLowerCase(Locale.ROOT);
            this.phrase = phrase(key);
        }
        String link() { return "minimamail://contact/" + phrase + "?id=" + key + "&sig=" + sig; }
    }
    static Card sign(CommsIdentity identity) {
        String key = identity.publicId();
        byte[] body = tagged("minima-mail-card-v1", key);
        byte[] sig = new byte[64];
        if (!Sodium.get().cryptoSignDetached(sig, body, body.length, identity.signSk))
            throw new IllegalStateException("Could not sign contact card");
        return new Card(key, Hex.to(sig));
    }
    static Card verify(String key, String signature, String phrase) {
        if (!isKey(key) || signature == null || !signature.matches("[a-fA-F0-9]{128}") ||
                !phrase(key).equals(phrase)) return null;
        byte[] body = tagged("minima-mail-card-v1", key);
        byte[] signingKey = Arrays.copyOfRange(Hex.from(key), 32, 64);
        return Sodium.get().cryptoSignVerifyDetached(Hex.from(signature), body, body.length, signingKey)
                ? new Card(key, signature) : null;
    }
    static String directLink(String key) {
        return "minimamail://contact/" + phrase(key) + "?id=" + key.toLowerCase(Locale.ROOT);
    }
    static String directKey(String value) {
        if (value == null || value.length() > 400) return null;
        int newline = value.indexOf('\n');
        if (newline >= 0) {
            String words = normalize(value.substring(0, newline));
            String link = value.substring(newline + 1).trim();
            if (words == null || !link.startsWith("minimamail://contact/" + words + "?id=")) return null;
            value = link;
        }
        int split = value.indexOf("?id=");
        if (split < 0) return null;
        String key = value.substring(split + 4);
        return isKey(key) && directLink(key).equals(value) ? key : null;
    }
    static Card link(String value) {
        if (value == null || value.length() > 400) return null;
        Matcher m = LINK.matcher(value);
        return m.matches() ? verify(m.group(2), m.group(3), m.group(1)) : null;
    }
    private MailAddress() {}
}
