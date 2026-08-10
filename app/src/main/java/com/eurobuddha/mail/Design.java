package com.eurobuddha.mail;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Theme token engine: two palettes ("light" paper — the default — and the family "dark") behind one
 * set of token names, so every screen is theme-agnostic. Call {@link #load} with the persisted theme
 * BEFORE building any view; MainActivity recreates itself on toggle.
 */
public final class Design {

    public static boolean LIGHT;

    public static int BG;
    public static int SURFACE;
    public static int SURFACE2;
    public static int HAIRLINE;
    public static int ACCENT;
    public static int ON_ACCENT;
    public static int TEXT;
    public static int DIM;
    public static int DIM2;
    public static int GREEN;
    public static int RED;
    public static int IN;        // received (green)
    public static int OUT;       // sent (orange)

    public static void load(String theme) {
        LIGHT = !"dark".equals(theme);
        if (LIGHT) {
            BG        = 0xFFFAF9F6;   // warm paper
            SURFACE   = 0xFFFFFFFF;
            SURFACE2  = 0xFFF1EFEA;
            HAIRLINE  = 0xFFE8E6E0;
            TEXT      = 0xFF1C1B1A;   // ink
            DIM       = 0xFF6E6A63;
            DIM2      = 0xFF9B968D;
            ACCENT    = 0xFFF7931A;   // Minima orange
            ON_ACCENT = 0xFFFFFFFF;
            GREEN     = 0xFF1F9D5B;
            RED       = 0xFFD9483B;
        } else {
            BG        = 0xFF0A0A0F;   // family near-black
            SURFACE   = 0xFF15151F;
            SURFACE2  = 0xFF1F1F2B;
            HAIRLINE  = 0xFF262633;
            TEXT      = 0xFFFFFFFF;
            DIM       = 0xFF9A9AA8;
            DIM2      = 0xFF6A6A78;
            ACCENT    = 0xFFF7931A;
            ON_ACCENT = 0xFF1A1000;
            GREEN     = 0xFF2ECC71;
            RED       = 0xFFE74C3C;
        }
        IN = GREEN;
        OUT = ACCENT;
    }

    static { load("light"); }

    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** A rounded chip-style TextView. */
    public static TextView pill(Context c, String text, int bg, int fg) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(11f);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 6), w = dp(c, 10);
        t.setPadding(w, h, w, h);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(c, 14));
        t.setBackground(d);
        return t;
    }

    /** A rounded filled background drawable (for cards / inputs). */
    public static GradientDrawable roundBg(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** A rounded card with a 1dp hairline border (the paper-email card look). */
    public static GradientDrawable cardBg(Context c, int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), HAIRLINE);
        return d;
    }

    /** A rounded outline (transparent fill) in the given stroke colour. */
    public static GradientDrawable outlineBg(Context c, int strokeColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), strokeColor);
        return d;
    }

    private Design() {}
}
