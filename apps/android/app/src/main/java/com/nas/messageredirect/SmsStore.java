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
    SmsStore(Context context, String databaseName) { super(context, databaseName, null, 4); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY, sender TEXT NOT NULL, body TEXT NOT NULL, sent_at INTEGER NOT NULL, captured_at INTEGER NOT NULL, source TEXT NOT NULL, message_id TEXT NOT NULL UNIQUE, uploaded_at INTEGER, delivery_mode TEXT, delivery_target TEXT, delivery_state TEXT NOT NULL DEFAULT 'pending', receiving_number TEXT NOT NULL DEFAULT '', UNIQUE(sender, body, sent_at, receiving_number))");
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
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN delivery_mode TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN delivery_target TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN delivery_state TEXT NOT NULL DEFAULT 'pending'");
            db.execSQL("UPDATE messages SET delivery_mode='database', delivery_state='done' WHERE uploaded_at IS NOT NULL");
        }
        if (oldVersion < 4) {
            // Rebuild the uniqueness constraint so identical SMS received by two SIMs remain distinct.
            db.execSQL("ALTER TABLE messages RENAME TO legacy_messages");
            onCreate(db);
            db.execSQL("INSERT INTO messages (_id,sender,body,sent_at,captured_at,source,message_id,uploaded_at,delivery_mode,delivery_target,delivery_state) SELECT _id,sender,body,sent_at,captured_at,source,message_id,uploaded_at,delivery_mode,delivery_target,delivery_state FROM legacy_messages");
            db.execSQL("DROP TABLE legacy_messages");
        }
    }
    boolean save(String sender, String body, long sentAt, String source) {
        return save(sender, body, sentAt, source, "");
    }
    boolean save(String sender, String body, long sentAt, String source, String receivingNumber) {
        ContentValues v = new ContentValues();
        v.put("sender", sender == null ? "未知号码" : sender); v.put("body", body == null ? "" : body);
        v.put("sent_at", sentAt); v.put("captured_at", System.currentTimeMillis()); v.put("source", source);
        v.put("message_id", UUID.randomUUID().toString());
        v.put("receiving_number", receivingNumber == null ? "" : receivingNumber);
        return getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }
    private List<Message> query(String selection, String[] args, String order, String limit) {
        List<Message> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("messages", null, selection, args, null, null, order, limit)) {
            while (c.moveToNext()) rows.add(new Message(c.getString(c.getColumnIndexOrThrow("sender")), c.getString(c.getColumnIndexOrThrow("body")), c.getLong(c.getColumnIndexOrThrow("sent_at")), c.getString(c.getColumnIndexOrThrow("source")), c.getString(c.getColumnIndexOrThrow("message_id")), c.getLong(c.getColumnIndexOrThrow("captured_at")), !c.isNull(c.getColumnIndexOrThrow("uploaded_at")), c.getString(c.getColumnIndexOrThrow("delivery_mode")), c.getString(c.getColumnIndexOrThrow("delivery_target")), c.getString(c.getColumnIndexOrThrow("delivery_state")), c.getString(c.getColumnIndexOrThrow("receiving_number"))));
        }
        return rows;
    }
    List<Message> recent() { return query(null, null, "sent_at DESC, _id DESC", "100"); }
    List<Message> uncertain() { return query("uploaded_at IS NULL AND delivery_state='uncertain'", null, "_id ASC", null); }
    List<Message> pending() { return query("uploaded_at IS NULL AND delivery_state='pending'", null, "_id ASC", "5"); }
    private String eligible() { return "uploaded_at IS NULL AND delivery_state='pending' AND (delivery_mode IS NULL OR (delivery_mode=? AND delivery_target=?))"; }
    List<Message> pending(String mode, String target) { return query(eligible(), new String[]{mode, target}, "_id ASC", "5"); }
    long eligibleCount(String mode, String target) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE " + eligible(), new String[]{mode,target})) { c.moveToFirst(); return c.getLong(0); }
    }
    void bindDelivery(String id, String mode, String target) {
        ContentValues v = new ContentValues(); v.put("delivery_mode", mode); v.put("delivery_target", target);
        getWritableDatabase().update("messages", v, "message_id=? AND delivery_mode IS NULL AND uploaded_at IS NULL", new String[]{id});
    }
    void emailSending(String id) { setEmailState(id, "pending", "sending"); }
    void emailUncertain(String id) { setEmailState(id, "sending", "uncertain"); }
    void retryEmail(String id) { setEmailState(id, "uncertain", "pending"); }
    private void setEmailState(String id, String before, String after) {
        ContentValues v = new ContentValues(); v.put("delivery_state", after);
        if (getWritableDatabase().update("messages", v, "message_id=? AND uploaded_at IS NULL AND delivery_mode='email' AND delivery_state=?", new String[]{id,before}) != 1)
            throw new IllegalStateException("delivery state changed");
    }
    void recoverInFlightEmails() { getWritableDatabase().execSQL("UPDATE messages SET delivery_state='uncertain' WHERE uploaded_at IS NULL AND delivery_state='sending'"); }
    long uncertainCount() { return countWhere(" WHERE uploaded_at IS NULL AND delivery_state IN ('sending','uncertain')"); }
    long reviewCount() { return countWhere(" WHERE uploaded_at IS NULL AND delivery_state='uncertain'"); }
    void markUploaded(List<String> ids) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues values = new ContentValues(); values.put("uploaded_at", System.currentTimeMillis()); values.put("delivery_state", "done");
            for (String id : ids) db.update("messages", values, "message_id=? AND uploaded_at IS NULL", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    long pendingCount() { return countWhere(" WHERE uploaded_at IS NULL"); }
    long count() { return countWhere(""); }
    private long countWhere(String where) { try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages" + where, null)) { c.moveToFirst(); return c.getLong(0); } }
    static final class Message {
        final String sender, body, source, id, mode, target, deliveryState, receivingNumber; final long time, capturedAt; final boolean uploaded;
        Message(String s, String b, long t, String o, String id, long capturedAt, boolean uploaded) {
            this(s,b,t,o,id,capturedAt,uploaded,null,null,"pending");
        }
        Message(String s, String b, long t, String o, String id, long capturedAt, boolean uploaded, String mode, String target, String deliveryState) {
            this(s,b,t,o,id,capturedAt,uploaded,mode,target,deliveryState,"");
        }
        Message(String s, String b, long t, String o, String id, long capturedAt, boolean uploaded, String mode, String target, String deliveryState, String receivingNumber) {
            sender = s; body = b; time = t; source = o; this.id = id; this.capturedAt = capturedAt; this.uploaded = uploaded;
            this.mode=mode;this.target=target;this.deliveryState=deliveryState;
            this.receivingNumber=receivingNumber;
        }
    }
}
