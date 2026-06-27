package org.minimarex.comms;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Local, persistent message + contact store (the web ChainMail kept this in MDS.sql; we use Android
 * SQLite). Messages dedup on (hashref, randomid) so re-scanning the chain never double-inserts.
 */
public class CommsDb extends SQLiteOpenHelper {

    private static final String DB = "minima_mail.db";
    private static final int VERSION = 1;
    private static final String MSG = "messages";
    private static final String CON = "contacts";
    private static final String META = "meta";

    public CommsDb(Context ctx) { super(ctx, DB, null, VERSION); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + MSG + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "hashref TEXT NOT NULL, fromname TEXT, frompublickey TEXT, topublickey TEXT," +
                "subject TEXT, message TEXT, randomid TEXT NOT NULL," +
                "incoming INTEGER, read INTEGER, date INTEGER," +
                "UNIQUE(hashref, randomid))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_hashref ON " + MSG + "(hashref)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + CON + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT, publickey TEXT UNIQUE)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + META + " (k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { onCreate(db); }

    // ----- messages -----

    /** Insert; returns true if NEW (false if this (hashref, randomid) was already stored). Idempotent. */
    public boolean insert(MailMessage m) {
        ContentValues v = new ContentValues();
        v.put("hashref", m.hashref); v.put("fromname", m.fromname);
        v.put("frompublickey", m.frompublickey); v.put("topublickey", m.topublickey);
        v.put("subject", m.subject); v.put("message", m.message); v.put("randomid", m.randomid);
        v.put("incoming", m.incoming ? 1 : 0); v.put("read", m.read ? 1 : 0); v.put("date", m.date);
        long rid = getWritableDatabase().insertWithOnConflict(MSG, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return rid != -1;
    }

    /** One row per thread = its latest message (SQLite bare-columns picks the MAX(date) row), newest first. */
    public List<MailMessage> threads() {
        return query("SELECT id,hashref,fromname,frompublickey,topublickey,subject,message,randomid," +
                "incoming,read,MAX(date) AS date FROM " + MSG + " GROUP BY hashref ORDER BY date DESC", null);
    }

    /** All messages in a thread, oldest first (conversation order). */
    public List<MailMessage> thread(String hashref) {
        return query("SELECT id,hashref,fromname,frompublickey,topublickey,subject,message,randomid," +
                "incoming,read,date FROM " + MSG + " WHERE hashref=? ORDER BY date ASC", new String[]{hashref});
    }

    public int unreadCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + MSG + " WHERE incoming=1 AND read=0", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    public void markThreadRead(String hashref) {
        ContentValues v = new ContentValues(); v.put("read", 1);
        getWritableDatabase().update(MSG, v, "hashref=?", new String[]{hashref});
    }

    public void deleteThread(String hashref) {
        getWritableDatabase().delete(MSG, "hashref=?", new String[]{hashref});
    }

    private List<MailMessage> query(String sql, String[] args) {
        List<MailMessage> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            while (c.moveToNext()) {
                MailMessage m = new MailMessage();
                m.id = c.getLong(0); m.hashref = c.getString(1); m.fromname = c.getString(2);
                m.frompublickey = c.getString(3); m.topublickey = c.getString(4);
                m.subject = c.getString(5); m.message = c.getString(6); m.randomid = c.getString(7);
                m.incoming = c.getInt(8) == 1; m.read = c.getInt(9) == 1; m.date = c.getLong(10);
                out.add(m);
            }
        } finally { c.close(); }
        return out;
    }

    // ----- contacts -----

    public boolean addContact(String username, String publickey) {
        ContentValues v = new ContentValues(); v.put("username", username); v.put("publickey", publickey);
        return getWritableDatabase().insertWithOnConflict(CON, null, v, SQLiteDatabase.CONFLICT_REPLACE) != -1;
    }

    public void deleteContact(long id) { getWritableDatabase().delete(CON, "id=?", new String[]{String.valueOf(id)}); }

    public List<String[]> contacts() {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id,username,publickey FROM " + CON + " ORDER BY username COLLATE NOCASE", null);
        try { while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1), c.getString(2)}); }
        finally { c.close(); }
        return out;
    }

    /** Contact name for a publickey, or null. */
    public String contactName(String publickey) {
        Cursor c = getReadableDatabase().rawQuery("SELECT username FROM " + CON + " WHERE publickey=?", new String[]{publickey});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    // ----- meta -----

    public void setMeta(String k, String v) {
        ContentValues cv = new ContentValues(); cv.put("k", k); cv.put("v", v);
        getWritableDatabase().insertWithOnConflict(META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getMeta(String k, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM " + META + " WHERE k=?", new String[]{k});
        try { return c.moveToFirst() ? c.getString(0) : def; } finally { c.close(); }
    }
}
