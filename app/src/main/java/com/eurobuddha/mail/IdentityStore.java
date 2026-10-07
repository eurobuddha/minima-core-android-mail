package com.eurobuddha.mail;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import com.eurobuddha.comms.CommsIdentity;

/** Reuses the integrated ETH vault's non-resetting encrypted store. Never persists a node seed. */
final class IdentityStore {
    private final SharedPreferences prefs;
    IdentityStore(Context context) throws Exception {
        MasterKey master = new MasterKey.Builder(context, "minima_mail_identity_master")
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
        prefs = EncryptedSharedPreferences.create(context, "minima_mail_identity", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }
    CommsIdentity load() throws Exception {
        String value = prefs.getString("identity", null);
        return value == null ? null : IdentityRecord.decode(value);
    }
    void save(CommsIdentity identity) throws Exception {
        if (!prefs.edit().putString("identity", IdentityRecord.encode(identity)).commit())
            throw new java.io.IOException("Mail identity could not be saved");
    }
}
