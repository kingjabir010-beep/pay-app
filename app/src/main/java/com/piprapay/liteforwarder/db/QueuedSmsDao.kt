package com.piprapay.liteforwarder.db

import androidx.room.*

@Dao
interface QueuedSmsDao {

    @Insert
    suspend fun insert(item: QueuedSms): Long

    @Query("SELECT * FROM queued_sms WHERE status != :sent ORDER BY id ASC")
    suspend fun getUnsent(sent: String = QueuedSms.STATUS_SENT): List<QueuedSms>

    @Query("SELECT * FROM queued_sms ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<QueuedSms>

    @Query("SELECT COUNT(*) FROM queued_sms WHERE status != :sent")
    suspend fun countUnsent(sent: String = QueuedSms.STATUS_SENT): Int

    @Query("SELECT COUNT(*) FROM queued_sms")
    suspend fun countAll(): Int

    @Update
    suspend fun update(item: QueuedSms)

    @Query("DELETE FROM queued_sms WHERE status = :sent AND createdAtMillis < :beforeMillis")
    suspend fun pruneOldSent(sent: String = QueuedSms.STATUS_SENT, beforeMillis: Long)
}
