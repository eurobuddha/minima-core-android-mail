package com.eurobuddha.mail;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;

import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;



import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Public address adaptation of Dexicon WordEntry, itself ported from Entropy LastWordView. */
final class AddressWordEntry {
    private static final int WORDS = MailAddress.COUNT;
    private static final int GREEN = 0xFF2E9E5B, RED = 0xFFD64545, BORDER = 0x55888888;


    /** Lower-case letters only: anything else can never be part of a word. */
    static String letters(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""); }

    /** The words in pasted text, in order: numbers, line breaks, commas and full stops separate words. */
    static List<String> splitPasted(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String w : raw.toLowerCase(Locale.ROOT).split("[^a-z]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    static boolean isWord(String w) {
        if (w == null || w.isEmpty()) return false;
        for (String x : MailAddress.dictionary()) if (x.equals(w)) return true;
        return false;
    }

    /** Up to {@code max} words starting with {@code prefix} (none for an empty prefix). */
    static List<String> matches(String prefix, int max) {
        List<String> out = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) return out;
        for (String w : MailAddress.dictionary()) if (w.startsWith(prefix)) { out.add(w); if (out.size() >= max) break; }
        return out;
    }

    /** The six words joined by single spaces, or null while any box is empty or not a word. */
    static String join(List<String> words) {
        if (words.size() != WORDS) return null;
        for (String w : words) if (!isWord(w)) return null;
        return String.join(" ", words);
    }

    // ---- the view ----

    private final Context c;
    private final Typeface mono;
    private final int textColor;
    public final LinearLayout view;
    private final List<EditText> boxes = new ArrayList<>();
    private final List<Integer> prevLens = new ArrayList<>();
    private final LinearLayout suggRow;
    private final HorizontalScrollView suggScroll;
    private final TextView count;

    private boolean mute;
    /** After an auto-complete: the rest of that word, swallowed if it is typed straight on into the next box (review M3:
     *  "acco" completed ACCOUNT and the typed "unt" became UNTIL). */
    private String tail = "";
    private int tailBox = -1;
    private long tailUntil;

    AddressWordEntry(Context c, int textColor) {
        this.c = c;
        this.mono = Typeface.MONOSPACE;
        this.textColor = textColor;
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);

        LinearLayout grid = new LinearLayout(c); grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < WORDS; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(c);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rl.topMargin = dp(6); grid.addView(row, rl);
            }
            EditText e = box(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = i % 3 == 0 ? 0 : dp(6);
            row.addView(e, lp);
            boxes.add(e); prevLens.add(0);
        }
        view.addView(grid);

        suggScroll = new HorizontalScrollView(c); suggScroll.setHorizontalScrollBarEnabled(false);
        suggRow = new LinearLayout(c); suggScroll.addView(suggRow);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(6); view.addView(suggScroll, sl);

        count = new TextView(c); count.setTextColor(this.textColor); count.setTextSize(12); count.setTypeface(this.mono);
        view.addView(count);
        refresh();
    }

    String phrase() {
        List<String> w = new ArrayList<>(WORDS);
        for (EditText e : boxes) w.add(e.getText().toString());
        return join(w);
    }

    /** Position of the first box that is empty or not a word (1-based), 0 when all are words. */
    public int firstProblem() {
        for (int i = 0; i < boxes.size(); i++) if (!isWord(boxes.get(i).getText().toString())) return i + 1;
        return 0;
    }

    private EditText box(int i) {
        EditText e = new EditText(c);
        e.setHint(String.valueOf(i + 1));
        e.setTextColor(textColor); e.setHintTextColor(BORDER | 0xFF000000);
        e.setTextSize(14); e.setSingleLine(true);
        // VISIBLE_PASSWORD: keyboards treat it as a password - no personal-dictionary learning, no cloud sync of typed
        // words (NO_SUGGESTIONS alone does not stop that). The typeface goes AFTER the input type, which resets it.
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_NEXT);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        e.setTypeface(mono);
        e.setPadding(dp(8), dp(6), dp(8), dp(6));
        e.setBackground(rounded(BORDER));
        final int idx = i;
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int x) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int x) {}
            @Override public void afterTextChanged(Editable s) { if (!mute) onEdit(idx); }
        });
        e.setOnFocusChangeListener((v, has) -> { if (has) showSuggestions(idx); });
        return e;
    }

    private void onEdit(int idx) {
        EditText e = boxes.get(idx);
        String raw = e.getText().toString();
        List<String> pasted = splitPasted(raw);
        // Pasted words (or a typed space): spread them over this box and the ones after it.
        if (pasted.size() > 1) { spread(idx, pasted); return; }
        if (idx == tailBox && !tail.isEmpty() && System.currentTimeMillis() < tailUntil) {
            String typed = letters(raw);
            if (!typed.isEmpty() && tail.startsWith(typed)) {   // still typing the word that just completed: drop it
                tail = tail.substring(typed.length()); tailUntil = System.currentTimeMillis() + 1500;
                if (tail.isEmpty()) tailBox = -1;
                set(e, ""); prevLens.set(idx, 0); style(idx);
                return;
            }
        }
        tail = ""; tailBox = -1;
        String v = letters(raw);
        if (!v.equals(raw)) set(e, v);
        boolean grew = v.length() > prevLens.get(idx);
        prevLens.set(idx, v.length());
        List<String> m = matches(v, 2);
        // A word is certain once only one matches (4 letters always suffice). Only while TYPING: completing on a
        // backspace would make a finished word impossible to edit.
        if (grew && m.size() == 1) {
            String w = m.get(0);
            tail = w.substring(Math.min(v.length(), w.length()));
            tailBox = commit(idx, w); tailUntil = System.currentTimeMillis() + 1500;
            return;
        }
        style(idx); showSuggestions(idx); refresh();
    }

    /** Pasted words. A whole phrase (six or more) always fills from box 1; words are never dropped - too many is refused with the
     *  count (review M2: "Seed word list: ..." filled 3 boxes with SEED WORD LIST and lost the last 3 words). */
    private void spread(int idx, List<String> words) {
        int start = words.size() >= WORDS ? 0 : idx;
        if (start + words.size() > WORDS) {
            set(boxes.get(idx), ""); prevLens.set(idx, 0); style(idx);
            count.setText(words.size() + " words pasted, but " + (start == 0 ? WORDS : WORDS - idx) + " fit"
                    + (start == 0 ? "" : " from box " + (idx + 1)) + ". Nothing was filled in: paste exactly six address words.");
            count.setTextColor(RED);
            return;
        }
        int i = start;
        for (String w : words) {
            List<String> m = matches(w, 2);
            String v = isWord(w) ? w : m.size() == 1 ? m.get(0) : w;   // a unique prefix completes, as when typing
            set(boxes.get(i), v); prevLens.set(i, v.length()); style(i); i++;
        }
        tail = ""; tailBox = -1;
        focusNextProblem(i - 1);
        refresh();
    }

    /** Returns the box now focused, -1 when none. */
    private int commit(int idx, String w) {
        set(boxes.get(idx), w); prevLens.set(idx, w.length());
        style(idx); refresh();
        return focusNextProblem(idx);
    }

    /** Forward only: wrapping to an earlier empty box let a finished word's typed tail land there (review M3). */
    private int focusNextProblem(int from) {
        for (int k = from + 1; k < boxes.size(); k++) {
            if (!isWord(boxes.get(k).getText().toString())) { boxes.get(k).requestFocus(); showSuggestions(k); return k; }
        }
        if (from >= 0 && from < boxes.size()) boxes.get(from).clearFocus();
        suggRow.removeAllViews();
        return -1;
    }

    private void set(EditText e, String v) { mute = true; e.setText(v); e.setSelection(v.length()); mute = false; }

    private void style(int idx) {
        String v = boxes.get(idx).getText().toString();
        int border = BORDER;
        if (!v.isEmpty()) border = isWord(v) ? GREEN : matches(v, 1).isEmpty() ? RED : BORDER;
        boxes.get(idx).setBackground(rounded(border));
    }

    private void showSuggestions(int idx) {
        suggRow.removeAllViews();
        for (String w : matches(boxes.get(idx).getText().toString(), 8)) {
            Button b = button(w, v -> commit(idx, w));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6); suggRow.addView(b, lp);
        }
    }

    private void refresh() {
        int ok = 0; for (EditText e : boxes) if (isWord(e.getText().toString())) ok++;
        count.setText(ok == WORDS ? "All six words entered" : ok + " of six words");
        count.setTextColor(ok == WORDS ? GREEN : textColor);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(c); b.setText(text); b.setAllCaps(false); b.setOnClickListener(l); return b;
    }

    private GradientDrawable rounded(int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x22000000); g.setCornerRadius(dp(6)); g.setStroke(dp(stroke == BORDER ? 1 : 2), stroke);
        return g;
    }

    private int dp(int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
