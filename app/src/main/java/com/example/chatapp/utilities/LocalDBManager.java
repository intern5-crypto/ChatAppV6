package com.example.chatapp.utilities;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.chatapp.models.ChatMessage;

import java.util.ArrayList;
import java.util.List;

public class LocalDBManager extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "ChatAppLocal.db";
    private static final int DATABASE_VERSION = 2;

    public LocalDBManager(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "senderId TEXT, " +
                "receiverId TEXT, " +
                "message TEXT, " +
                "type TEXT, " +
                "dateTime TEXT)";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS messages");
        onCreate(db);
    }

    public void saveMessage(String senderId, String receiverId, String message, String type, String dateTime) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("senderId", senderId);
        values.put("receiverId", receiverId);
        values.put("message", message);
        values.put("type", type);
        values.put("dateTime", dateTime);
        db.insert("messages", null, values);
        db.close();
    }

    public List<ChatMessage> getLocalChatHistory(String myId, String peerId) {
        List<ChatMessage> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        String query = "SELECT * FROM messages WHERE (senderId=? AND receiverId=?) OR (senderId=? AND receiverId=?)";
        Cursor cursor = db.rawQuery(query, new String[]{myId, peerId, peerId, myId});

        if (cursor.moveToFirst()) {
            do {
                ChatMessage msg = new ChatMessage();
                msg.senderId = cursor.getString(1);
                msg.message = cursor.getString(3);
                msg.type = cursor.getString(4);
                msg.dateTime = cursor.getString(5);
                if (msg.type == null) msg.type = "text";
                list.add(msg);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return list;
    }

    public ChatMessage getLastMessage(String myId, String peerId) {
        SQLiteDatabase db = this.getReadableDatabase();
        String query = "SELECT * FROM messages WHERE (senderId=? AND receiverId=?) OR (senderId=? AND receiverId=?) ORDER BY id DESC LIMIT 1";
        Cursor cursor = db.rawQuery(query, new String[]{myId, peerId, peerId, myId});

        ChatMessage msg = null;
        if (cursor.moveToFirst()) {
            msg = new ChatMessage();
            msg.senderId = cursor.getString(1);
            msg.message = cursor.getString(3);
            msg.type = cursor.getString(4);
            msg.dateTime = cursor.getString(5);
            if (msg.type == null) msg.type = "text";
        }
        cursor.close();
        db.close();
        return msg;
    }
}