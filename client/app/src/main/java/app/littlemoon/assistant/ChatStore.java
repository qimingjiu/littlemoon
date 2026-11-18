package app.littlemoon.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The durable timeline, outbox and sync cursor for each paired device and conversation. */
public final class ChatStore {
    public static final class Entry {
        public final String id, role, text, status;
        public final JSONObject tool;
        Entry(String id, String role, String text, String status, String specification) {
            this.id = id;
            this.role = role;
            this.text = text;
            this.status = status;
            JSONObject parsed = null;
            if (specification != null) {
                try { parsed = new JSONObject(specification); } catch (JSONException ignored) {}
            }
            this.tool = parsed;
        }
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) { super(context, "chat-v1.db", null, 2); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE messages (row_id INTEGER PRIMARY KEY, scope TEXT NOT NULL, chat TEXT NOT NULL, " +
                    "client_id TEXT, server_id TEXT, seq INTEGER, role TEXT NOT NULL, body TEXT NOT NULL, status TEXT NOT NULL, " +
                    "UNIQUE(scope,chat,client_id), UNIQUE(scope,chat,server_id), UNIQUE(scope,chat,seq))");
            db.execSQL("CREATE TABLE cursors (scope TEXT NOT NULL, chat TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(scope,chat))");
            createTools(db);
        }
        private static void createTools(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS tools (scope TEXT NOT NULL, chat TEXT NOT NULL, message TEXT NOT NULL, " +
                    "spec TEXT NOT NULL, state TEXT NOT NULL, result TEXT, PRIMARY KEY(scope,chat,message))");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion == 1 && newVersion == 2) { createTools(db); return; }
            throw new IllegalStateException("Unsupported chat database version");
        }
    }

    private static Database shared;
    private final Context context;
    private final Database helper;
    public ChatStore(Context context) {
        this.context = context.getApplicationContext();
        synchronized (ChatStore.class) {
            if (shared == null) shared = new Database(this.context);
            helper = shared;
        }
    }

    public List<Entry> messages(String chat) {
        String scope = AssistantApi.scope(context);
        List<Entry> entries = new ArrayList<>();
        if (scope.isEmpty() || chat == null) return entries;
        // Return the most recent window in chronological order, including unacknowledged local sends.
        String sql = "SELECT client_id,server_id,role,body,COALESCE(t.state,m.status),t.spec FROM (" +
                "SELECT * FROM messages WHERE scope=? AND chat=? ORDER BY (seq IS NULL) DESC,seq DESC,row_id DESC LIMIT 250" +
                ") m LEFT JOIN tools t ON t.scope=m.scope AND t.chat=m.chat AND t.message=m.server_id ORDER BY (seq IS NULL),seq,row_id";
        try (Cursor c = helper.getReadableDatabase().rawQuery(sql, new String[]{scope, chat})) {
            while (c.moveToNext()) entries.add(new Entry(c.isNull(0) ? c.getString(1) : c.getString(0),
                    c.getString(2), c.getString(3), c.getString(4), c.getString(5)));
        }
        return entries;
    }

    String enqueue(String scope, String chat, String body) {
        String id = UUID.randomUUID().toString();
        ContentValues v = new ContentValues();
        v.put("scope", scope); v.put("chat", chat); v.put("client_id", id);
        v.put("role", "user"); v.put("body", body); v.put("status", "pending");
        helper.getWritableDatabase().insertOrThrow("messages", null, v);
        return id;
    }

    String pendingBody(String scope, String chat, String id) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT body FROM messages WHERE scope=? AND chat=? AND client_id=? AND status='pending'",
                new String[]{scope, chat, id})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    void accepted(String scope, String chat, String id, String serverId) {
        ContentValues v = new ContentValues();
        v.put("server_id", serverId); v.put("status", "sent");
        helper.getWritableDatabase().update("messages", v, "scope=? AND chat=? AND client_id=?", new String[]{scope,chat,id});
    }

    long cursor(String scope, String chat) {
        try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT seq FROM cursors WHERE scope=? AND chat=?", new String[]{scope,chat})) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    long applyPage(String scope, String chat, long after, JSONObject page) throws JSONException {
        SQLiteDatabase db = helper.getWritableDatabase();
        JSONArray rows = page.getJSONArray("messages");
        db.beginTransaction();
        try {
            long last = after;
            for (int i=0; i<rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                long seq = row.getLong("seq");
                String sid = required(row,"id");
                String role = required(row,"role");
                boolean tool = "tool_request".equals(role);
                if (seq <= last || !("assistant".equals(role) || "user".equals(role) || tool))
                    throw new JSONException("Invalid message order or role");
                last = seq;
                ContentValues v = new ContentValues();
                v.put("scope",scope); v.put("chat",chat); v.put("seq",seq); v.put("server_id",sid);
                v.put("role",role); v.put("body",row.getString("text")); v.put("status","user".equals(role) ? "sent":"received");
                if (tool) {
                    JSONObject spec = row.getJSONObject("tool");
                    required(spec,"name");
                    ContentValues tv = new ContentValues();
                    tv.put("scope",scope); tv.put("chat",chat); tv.put("message",sid);
                    tv.put("spec",spec.toString()); tv.put("state","ready");
                    db.insertWithOnConflict("tools",null,tv,SQLiteDatabase.CONFLICT_IGNORE);
                }
                String cid = row.isNull("client_message_id") ? "" : row.getString("client_message_id");
                if ("user".equals(role) && !cid.isEmpty()) {
                    v.put("client_id",cid);
                    if (db.update("messages",v,"scope=? AND chat=? AND client_id=?",new String[]{scope,chat,cid}) > 0) continue;
                }
                if (db.update("messages",v,"scope=? AND chat=? AND server_id=?",new String[]{scope,chat,sid}) == 0)
                    db.insertOrThrow("messages", null, v);
            }
            if (page.getLong("next_seq") != last || (last == after && page.getBoolean("has_more")))
                throw new JSONException("Invalid sync cursor");
            ContentValues v = new ContentValues();
            v.put("scope",scope); v.put("chat",chat); v.put("seq",last);
            db.insertWithOnConflict("cursors",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
            return last;
        } finally { db.endTransaction(); }
    }

    synchronized boolean transitionTool(String scope,String chat,String id,String from,String to,String result) {
        ContentValues v = new ContentValues();
        v.put("state",to);
        if (result != null) v.put("result",result);
        return helper.getWritableDatabase().update("tools",v,"scope=? AND chat=? AND message=? AND state=?",
                new String[]{scope,chat,id,from}) == 1;
    }

    String toolResult(String scope,String chat,String id) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT result FROM tools WHERE scope=? AND chat=? AND message=? AND state='result_pending'",
                new String[]{scope,chat,id})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    static String required(JSONObject obj, String key) throws JSONException {
        String value = obj.isNull(key) ? "" : obj.getString(key);
        if (value.trim().isEmpty()) throw new JSONException("Missing " + key);
        return value;
    }
}
