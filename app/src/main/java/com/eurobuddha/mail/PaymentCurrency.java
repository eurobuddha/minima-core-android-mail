package com.eurobuddha.mail;

import android.content.Context;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Parlons' currency radio picker, with the selected node balance carried through to Mail's review. */
final class PaymentCurrency extends RadioGroup {
    static final String MXUSD = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    PaymentCurrency(Context context, List<String[]> tokens) {
        super(context);
        setOrientation(VERTICAL);
        for (String[] token : tokens) {
            RadioButton button = new RadioButton(context);
            button.setId(View.generateViewId());
            button.setText(token[1] + "  ·  " + token[2]);
            button.setTextColor(Design.TEXT);
            button.setTag(token);
            addView(button);
        }
        check(getChildAt(0).getId());
    }

    String[] selected() {
        View button = findViewById(getCheckedRadioButtonId());
        return button == null ? null : (String[]) button.getTag();
    }

    static List<String[]> options(JSONArray balances) {
        List<String[]> tokens = new ArrayList<>();
        String[] minima = {"0x00", "Minima", "0"};
        String[] mxusd = {MXUSD, "MxUSD", "0"};
        tokens.add(minima);
        tokens.add(mxusd);
        for (int i = 0; i < balances.length(); i++) {
            JSONObject row = balances.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("tokenid", "");
            String balance = row.optString("sendable", row.optString("confirmed", "0"));
            try { if (new BigDecimal(balance).signum() <= 0) continue; }
            catch (NumberFormatException ignored) { continue; }
            if ("0x00".equalsIgnoreCase(id)) minima[2] = balance;
            else if (MXUSD.equalsIgnoreCase(id)) mxusd[2] = balance;
            else if (id.matches("0x[0-9a-fA-F]{64}")) {
                JSONObject meta = row.optJSONObject("token");
                String name = meta == null ? row.optString("token", id) : meta.optString("name", id);
                if ("Minima".equalsIgnoreCase(name) || "MxUSD".equalsIgnoreCase(name)) name = id;
                tokens.add(new String[]{id, name, balance});
            }
        }
        return tokens;
    }
}
