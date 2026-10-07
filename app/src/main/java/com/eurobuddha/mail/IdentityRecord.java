package com.eurobuddha.mail;

import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.Hex;
import org.json.JSONObject;

/** The same four derived keys already carried by Mail's encrypted backup format. */
final class IdentityRecord {
    static String encode(CommsIdentity id) throws Exception {
        validate(id.boxPk, id.boxSk, id.signPk, id.signSk);
        return new JSONObject().put("boxPk", Hex.to(id.boxPk)).put("boxSk", Hex.to(id.boxSk))
                .put("signPk", Hex.to(id.signPk)).put("signSk", Hex.to(id.signSk)).toString();
    }
    static CommsIdentity decode(String value) throws Exception {
        JSONObject j = new JSONObject(value);
        byte[] boxPk=Hex.from(j.getString("boxPk")), boxSk=Hex.from(j.getString("boxSk"));
        byte[] signPk=Hex.from(j.getString("signPk")), signSk=Hex.from(j.getString("signSk"));
        validate(boxPk,boxSk,signPk,signSk);
        return CommsIdentity.fromKeys(boxPk,boxSk,signPk,signSk);
    }
    private static void validate(byte[] boxPk,byte[] boxSk,byte[] signPk,byte[] signSk) {
        if(boxPk==null || boxSk==null || signPk==null || signSk==null ||
                boxPk.length!=32 || boxSk.length!=32 || signPk.length!=32 || signSk.length!=64)
            throw new IllegalArgumentException("Invalid Mail identity key lengths");
    }
}
