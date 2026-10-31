package com.engperini.meshcore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Conversation key: "c:<contact pubkey hex>" for direct chats, "g:<channel secret hex>" for groups. */
object ConvKey {
    fun contact(pubKeyHex: String) = "c:$pubKeyHex"
    fun channel(secretHex: String) = "g:$secretHex"
}

enum class MsgStatus(val code: Int) {
    RECEIVED(0), SENDING(1), SENT(2), DELIVERED(3), FAILED(4);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: RECEIVED
    }
}

data class Message(
    val id: Long,
    val conv: String,
    val outgoing: Boolean,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val status: MsgStatus,
    val ack: Long,
    val snr: Float?,
    val hops: Int?,
    val roundTripMs: Long,
)

data class ConversationSummary(val conv: String, val lastText: String, val lastSender: String, val lastTime: Long, val unread: Int)

/** Message history, kept on the phone (the node itself only queues unread messages). */
class MessageStore(context: Context) : SQLiteOpenHelper(context, "messages.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conv TEXT NOT NULL,
                outgoing INTEGER NOT NULL,
                sender TEXT NOT NULL,
                text TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                status INTEGER NOT NULL,
                ack INTEGER NOT NULL DEFAULT 0,
                snr REAL,
                hops INTEGER,
                rtt INTEGER NOT NULL DEFAULT 0,
                unread INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL("CREATE INDEX idx_conv ON messages(conv, id)")
        db.execSQL("CREATE INDEX idx_ack ON messages(ack)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(
        conv: String, outgoing: Boolean, sender: String, text: String, timestamp: Long, status: MsgStatus,
        snr: Float? = null, hops: Int? = null, unread: Boolean = false,
    ): Long {
        val v = ContentValues().apply {
            put("conv", conv)
            put("outgoing", if (outgoing) 1 else 0)
            put("sender", sender)
            put("text", text)
            put("timestamp", timestamp)
            put("status", status.code)
            if (snr != null) put("snr", snr)
            if (hops != null) put("hops", hops)
            put("unread", if (unread) 1 else 0)
        }
        return writableDatabase.insert("messages", null, v)
    }

    /** True if an identical incoming message is already stored (mesh floods can deliver duplicates). */
    fun isDuplicate(conv: String, sender: String, text: String, timestamp: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE conv=? AND sender=? AND text=? AND timestamp=? AND outgoing=0 LIMIT 1",
            arrayOf(conv, sender, text, timestamp.toString()),
        ).use { it.moveToFirst() }

    fun setSent(id: Long, ack: Long, status: MsgStatus) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("ack", ack); put("status", status.code) },
            "id=?", arrayOf(id.toString()),
        )
    }

    fun setStatus(id: Long, status: MsgStatus) {
        writableDatabase.update("messages", ContentValues().apply { put("status", status.code) }, "id=?", arrayOf(id.toString()))
    }

    /** Marks the message waiting for this ACK as delivered. Returns true if one matched. */
    fun confirmAck(ack: Long, rttMs: Long): Boolean =
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("status", MsgStatus.DELIVERED.code); put("rtt", rttMs) },
            "ack=? AND outgoing=1 AND status IN (?, ?)",
            arrayOf(ack.toString(), MsgStatus.SENT.code.toString(), MsgStatus.FAILED.code.toString()),
        ) > 0

    fun failIfNotDelivered(id: Long) {
        writableDatabase.update(
            "messages", ContentValues().apply { put("status", MsgStatus.FAILED.code) },
            "id=? AND status=?", arrayOf(id.toString(), MsgStatus.SENT.code.toString()),
        )
    }

    fun messages(conv: String, limit: Int = 500): List<Message> =
        readableDatabase.rawQuery(
            "SELECT * FROM (SELECT * FROM messages WHERE conv=? ORDER BY id DESC LIMIT ?) ORDER BY id ASC",
            arrayOf(conv, limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Message(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            conv = c.getString(c.getColumnIndexOrThrow("conv")),
                            outgoing = c.getInt(c.getColumnIndexOrThrow("outgoing")) != 0,
                            sender = c.getString(c.getColumnIndexOrThrow("sender")),
                            text = c.getString(c.getColumnIndexOrThrow("text")),
                            timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                            status = MsgStatus.of(c.getInt(c.getColumnIndexOrThrow("status"))),
                            ack = c.getLong(c.getColumnIndexOrThrow("ack")),
                            snr = c.getColumnIndexOrThrow("snr").let { if (c.isNull(it)) null else c.getFloat(it) },
                            hops = c.getColumnIndexOrThrow("hops").let { if (c.isNull(it)) null else c.getInt(it) },
                            roundTripMs = c.getLong(c.getColumnIndexOrThrow("rtt")),
                        ),
                    )
                }
            }
        }

    fun summaries(): Map<String, ConversationSummary> =
        readableDatabase.rawQuery(
            """SELECT m.conv, m.text, m.sender, m.timestamp,
                      (SELECT COUNT(*) FROM messages u WHERE u.conv=m.conv AND u.unread=1)
               FROM messages m
               WHERE m.id = (SELECT MAX(id) FROM messages x WHERE x.conv=m.conv)""",
            null,
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.getString(0), ConversationSummary(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)))
                }
            }
        }

    fun markRead(conv: String) {
        writableDatabase.update("messages", ContentValues().apply { put("unread", 0) }, "conv=? AND unread=1", arrayOf(conv))
    }

    fun clear(conv: String) {
        writableDatabase.delete("messages", "conv=?", arrayOf(conv))
    }
}
