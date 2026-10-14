package com.nas.messageredirect;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class SmsStore extends SQLiteOpenHelper {
    static final String CHANGED = "com.nas.messageredirect.CHANGED";
    SmsStore(Context context) { this(context, "messages.db"); }
    SmsStore(Context context, String databaseName) { super(context, databaseName, null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY, sender TEXT NOT NULL, body TEXT NOT NULL, sent_at INTEGER NOT NULL, captured_at INTEGER NOT NULL, source TEXT NOT NULL, message_id TEXT NOT NULL UNIQUE, uploaded_at INTEGER, UNIQUE(sender, body, sent_at))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN message_id TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN uploaded_at INTEGER");
            try (Cursor c = db.rawQuery("SELECT _id FROM messages", null)) {
                while (c.moveToNext()) {
                    ContentValues v = new ContentValues(); v.put("message_id", UUID.randomUUID().toString());
                    db.update("messages", v, "_id=?", new String[]{c.getString(0)});
                }
            }
            db.execSQL("CREATE UNIQUE INDEX message_id_idx ON messages(message_id)");
        }
    }
    boolean save(String sender, String body, long sentAt, String source) {
        ContentValues v = new ContentValues();
        v.put("sender", sender == null ? "未知号码" : sender); v.put("body", body == null ? "" : body);
        v.put("sent_at", sentAt); v.put("captured_at", System.currentTimeMillis()); v.put("source", source);
        v.put("message_id", UUID.randomUUID().toString());
        return getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }
    private List<Message> query(String selection, String order, String limit) {
        List<Message> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("messages", null, selection, null, null, null, order, limit)) {
            while (c.moveToNext()) rows.add(new Message(c.getString(c.getColumnIndexOrThrow("sender")), c.getString(c.getColumnIndexOrThrow("body")), c.getLong(c.getColumnIndexOrThrow("sent_at")), c.getString(c.getColumnIndexOrThrow("source")), c.getString(c.getColumnIndexOrThrow("message_id")), c.getLong(c.getColumnIndexOrThrow("captured_at")), !c.isNull(c.getColumnIndexOrThrow("uploaded_at"))));
        }
        return rows;
    }
    List<Message> recent() { return query(null, "sent_at DESC, _id DESC", "100"); }
    List<Message> pending() { return query("uploaded_at IS NULL", "_id ASC", "5"); }
    void markUploaded(List<String> ids) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues values = new ContentValues(); values.put("uploaded_at", System.currentTimeMillis());
            for (String id : ids) db.update("messages", values, "message_id=? AND uploaded_at IS NULL", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    long pendingCount() { return countWhere(" WHERE uploaded_at IS NULL"); }
    long count() { return countWhere(""); }
    private long countWhere(String where) { try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages" + where, null)) { c.moveToFirst(); return c.getLong(0); } }
    static final class Message {
        final String sender, body, source, id; final long time, capturedAt; final boolean uploaded;
        Message(String s, String b, long t, String o, String id, long capturedAt, boolean uploaded) {
            sender = s; body = b; time = t; source = o; this.id = id; this.capturedAt = capturedAt; this.uploaded = uploaded;
        }
    }
}
