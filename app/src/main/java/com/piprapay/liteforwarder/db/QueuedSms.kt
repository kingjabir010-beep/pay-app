package com.piprapay.liteforwarder.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// One row per SMS captured on the device. Nothing is ever deleted on
// failure — it just stays PENDING and gets retried, so a message can
// never be silently lost, whether the device was offline, the app was
// killed, or the phone rebooted.
@Entity(tableName = "queued_sms")
data class QueuedSms(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val message: String,
    val receivedAtMillis: Long,
    var status: String = STATUS_PENDING,
    var attempts: Int = 0,
    var lastError: String? = null,
    var provider: String? = null,
    var trxId: String? = null,
    var amount: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED" // still retried; kept for UI display of last outcome
    }
}
