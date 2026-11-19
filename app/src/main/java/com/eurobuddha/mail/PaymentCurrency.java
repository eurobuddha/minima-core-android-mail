package com.eurobuddha.mail;

import android.content.Context;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.Arrays;
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
        String[] minima = {"0x00", "Minima", "0"};
        String[] mxusd = {MXUSD, "MxUSD", "0"};
        for (int i = 0; i < balances.length(); i++) {
            JSONObject row = balances.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("tokenid", "");
            String[] currency;
            if ("0x00".equalsIgnoreCase(id)) currency = minima;
            else if (MXUSD.equalsIgnoreCase(id)) currency = mxusd;
            else continue;

            String balance = row.optString("sendable", row.optString("confirmed", "0"));
            try { if (new BigDecimal(balance).signum() > 0) currency[2] = balance; }
            catch (NumberFormatException ignored) { /* Keep the zero balance. */ }
        }
        return Arrays.asList(minima, mxusd);
    }
}
