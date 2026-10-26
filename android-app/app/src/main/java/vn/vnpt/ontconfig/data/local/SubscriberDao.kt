package vn.vnpt.ontconfig.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import vn.vnpt.ontconfig.data.excel.ExcelManager
import vn.vnpt.ontconfig.data.model.SubscriberRecord

@Dao
interface SubscriberDao {

    @Query("SELECT * FROM subscribers ORDER BY rowIndex ASC")
    fun observeAll(): Flow<List<SubscriberEntity>>

    @Query("SELECT * FROM subscribers ORDER BY rowIndex ASC")
    suspend fun getAll(): List<SubscriberEntity>

    @Query("SELECT * FROM subscribers WHERE rowIndex = :rowIndex LIMIT 1")
    suspend fun getByRowIndex(rowIndex: Int): SubscriberEntity?

    @Query(
        """
        SELECT * FROM subscribers
        WHERE (:query = ''
            OR LOWER(accountPppoe) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(slid) LIKE '%' || LOWER(:query) || '%'
            OR (LTRIM(:query, '0') != '' AND LTRIM(LOWER(slid), '0') LIKE '%' || LTRIM(LOWER(:query), '0') || '%')
            OR LOWER(tenTb) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(diachiTb) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(soDt) LIKE '%' || LOWER(:query) || '%'
            OR (:normPhone != '' AND soDtNormalized LIKE '%' || :normPhone || '%'))
        AND (:statusFilter IS NULL OR status = :statusFilter)
        ORDER BY rowIndex ASC
        """
    )
    suspend fun search(query: String, normPhone: String, statusFilter: String? = null): List<SubscriberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SubscriberEntity>)

    @Update
    suspend fun update(entity: SubscriberEntity)

    @Query("DELETE FROM subscribers")
    suspend fun clearAll()

    @Transaction
    suspend fun upsertPreservingEdits(incomingRecords: List<SubscriberRecord>): List<SubscriberRecord> {
        val existing = getAll().map { it.toRecord() }
        val merged = ExcelManager.mergeRecordsPreservingEdits(existing, incomingRecords)
        clearAll()
        insertAll(merged.map { SubscriberEntity.fromRecord(it) })
        return merged
    }
}
