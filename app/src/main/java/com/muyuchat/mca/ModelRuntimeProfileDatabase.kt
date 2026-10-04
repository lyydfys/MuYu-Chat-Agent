package com.muyuchat.mca

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

@Dao
internal interface ModelRuntimeProfileDao {
    @Query("SELECT * FROM runtime_installation_scope WHERE scopeKey = :scopeKey LIMIT 1")
    suspend fun installationScope(scopeKey: String): RuntimeInstallationScopeEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInstallationScope(scope: RuntimeInstallationScopeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIdentity(identity: RuntimeIdentityEntity)

    @Update
    suspend fun updateIdentity(identity: RuntimeIdentityEntity): Int

    @Query("SELECT * FROM runtime_identity WHERE identityKey = :identityKey LIMIT 1")
    suspend fun identity(identityKey: String): RuntimeIdentityEntity?

    @Query("SELECT * FROM runtime_identity WHERE modelId = :modelId ORDER BY updatedAt DESC, identityKey DESC LIMIT 1")
    suspend fun latestIdentityForModel(modelId: String): RuntimeIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: ExecutionProfileEntity)

    @Update
    suspend fun updateProfile(profile: ExecutionProfileEntity): Int

    @Query("SELECT * FROM execution_profile WHERE profileId = :profileId LIMIT 1")
    suspend fun profile(profileId: String): ExecutionProfileEntity?

    @Query("SELECT * FROM execution_profile WHERE identityKey = :identityKey ORDER BY revision DESC")
    suspend fun profiles(identityKey: String): List<ExecutionProfileEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertResourceBindings(bindings: List<RuntimeResourceBindingEntity>)

    @Query("SELECT * FROM runtime_resource_binding WHERE profileId = :profileId ORDER BY field ASC")
    suspend fun resourceBindings(profileId: String): List<RuntimeResourceBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPointers(pointers: ProfilePointersEntity)

    @Query("SELECT * FROM profile_pointers WHERE identityKey = :identityKey LIMIT 1")
    suspend fun pointers(identityKey: String): ProfilePointersEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: TuningJobEntity)

    @Query("SELECT * FROM tuning_job WHERE jobId = :jobId LIMIT 1")
    suspend fun job(jobId: String): TuningJobEntity?

    @Query("SELECT * FROM tuning_job WHERE identityKey = :identityKey AND state IN (:states) ORDER BY updatedAt DESC")
    suspend fun jobsInStates(identityKey: String, states: List<String>): List<TuningJobEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJournal(journal: TuningJournalEntity)

    @Update
    suspend fun updateJournal(journal: TuningJournalEntity): Int

    @Query("SELECT * FROM tuning_journal WHERE transactionId = :transactionId LIMIT 1")
    suspend fun journal(transactionId: String): TuningJournalEntity?

    @Query("SELECT * FROM tuning_journal WHERE state IN (:states) ORDER BY updatedAt ASC")
    suspend fun journalsInStates(states: List<String>): List<TuningJournalEntity>

    @Query("SELECT * FROM tuning_journal WHERE identityKey = :identityKey AND state IN (:states) ORDER BY updatedAt DESC")
    suspend fun journalsInStates(identityKey: String, states: List<String>): List<TuningJournalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMeasurement(measurement: CandidateMeasurementEntity)

    @Query("DELETE FROM candidate_measurement WHERE profileId = :profileId AND measurementId IN (SELECT measurementId FROM candidate_measurement WHERE profileId = :profileId ORDER BY createdAt DESC, measurementId DESC LIMIT -1 OFFSET :keepCount)")
    suspend fun pruneMeasurements(profileId: String, keepCount: Int)

    @Query("DELETE FROM execution_profile WHERE recordState = 'REJECTED' AND updatedAt < :olderThan AND profileId NOT IN (SELECT activeProfileId FROM profile_pointers WHERE activeProfileId IS NOT NULL) AND profileId NOT IN (SELECT pendingProfileId FROM profile_pointers WHERE pendingProfileId IS NOT NULL) AND profileId NOT IN (SELECT lastKnownGoodProfileId FROM profile_pointers WHERE lastKnownGoodProfileId IS NOT NULL) AND profileId NOT IN (SELECT rollbackTargetProfileId FROM profile_pointers WHERE rollbackTargetProfileId IS NOT NULL) AND profileId NOT IN (SELECT parentCommittedProfileId FROM execution_profile WHERE parentCommittedProfileId IS NOT NULL) AND profileId NOT IN (SELECT pendingProfileId FROM tuning_journal)")
    suspend fun pruneRejectedProfiles(olderThan: Long)

    @Query("DELETE FROM candidate_measurement WHERE profileId NOT IN (SELECT profileId FROM execution_profile)")
    suspend fun pruneOrphanMeasurements()

    @Query("DELETE FROM runtime_resource_binding WHERE profileId NOT IN (SELECT profileId FROM execution_profile)")
    suspend fun pruneOrphanResourceBindings()

    @Query("DELETE FROM tuning_journal WHERE state IN (:terminalStates) AND updatedAt < :olderThan")
    suspend fun pruneTerminalJournalsByAge(terminalStates: List<String>, olderThan: Long)

    @Query("DELETE FROM tuning_journal WHERE transactionId IN (SELECT transactionId FROM tuning_journal WHERE state IN (:terminalStates) ORDER BY updatedAt DESC, transactionId DESC LIMIT -1 OFFSET :keepCount)")
    suspend fun pruneTerminalJournalsByCount(terminalStates: List<String>, keepCount: Int)

    @Query("DELETE FROM tuning_job WHERE state IN (:terminalStates) AND updatedAt < :olderThan")
    suspend fun pruneTerminalJobsByAge(terminalStates: List<String>, olderThan: Long)

    @Query("DELETE FROM tuning_job WHERE jobId IN (SELECT jobId FROM tuning_job WHERE state IN (:terminalStates) ORDER BY updatedAt DESC, jobId DESC LIMIT -1 OFFSET :keepCount)")
    suspend fun pruneTerminalJobsByCount(terminalStates: List<String>, keepCount: Int)
}

@Database(
    entities = [
        RuntimeInstallationScopeEntity::class,
        RuntimeIdentityEntity::class,
        ExecutionProfileEntity::class,
        RuntimeResourceBindingEntity::class,
        ProfilePointersEntity::class,
        TuningJobEntity::class,
        TuningJournalEntity::class,
        CandidateMeasurementEntity::class
    ],
    version = 1,
    // Exported so the migration chain can be checked against the exact structure Room
    // validates at runtime; see the room.schemaLocation argument in app/build.gradle.kts.
    exportSchema = true
)
internal abstract class ModelRuntimeProfileDatabase : RoomDatabase() {
    abstract fun dao(): ModelRuntimeProfileDao

    companion object {
        const val DATABASE_NAME = "mca-runtime.db"

        @Volatile
        private var instance: ModelRuntimeProfileDatabase? = null

        fun get(context: Context): ModelRuntimeProfileDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ModelRuntimeProfileDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }
    }
}

