package dev.ironlog.app.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RenameColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec

/**
 * 13 -> 14: the import-provenance column on `workouts` was renamed to generic wording
 * (it holds the previous tracker's workout number). Rename only, no data change.
 */
@RenameColumn(
    tableName = "workouts",
    fromColumnName = "strongWorkoutNumber",
    toColumnName = "importedWorkoutNumber",
)
class RenameImportedWorkoutNumber : AutoMigrationSpec

@Database(
    entities = [
        Exercise::class,
        Workout::class,
        SetEntry::class,
        Template::class,
        TemplateExercise::class,
        TemplateSet::class,
        PlateInventory::class,
        MuscleVolumeTarget::class,
        ProgressionSuggestion::class,
        GymProfile::class,
        GymProfileEquipment::class,
        Measurement::class,
        CoachSummary::class,
        ReminderConfig::class,
        PlanEntry::class,
    ],
    version = 14,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12), // + templates.position (additive)
        AutoMigration(from = 12, to = 13), // + exercises.plateCalcMode (F2, additive)
        // 13 -> 14 renames the import-provenance column on workouts
        // (generic naming for the previous tracker's import provenance; no data change).
        AutoMigration(from = 13, to = 14, spec = RenameImportedWorkoutNumber::class),
    ],
)
@TypeConverters(Converters::class)
abstract class IronlogDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun setEntryDao(): SetEntryDao
    abstract fun templateDao(): TemplateDao
    abstract fun templateExerciseDao(): TemplateExerciseDao
    abstract fun templateSetDao(): TemplateSetDao
    abstract fun plateInventoryDao(): PlateInventoryDao
    abstract fun muscleVolumeTargetDao(): MuscleVolumeTargetDao
    abstract fun progressionSuggestionDao(): ProgressionSuggestionDao
    abstract fun gymProfileDao(): GymProfileDao
    abstract fun gymProfileEquipmentDao(): GymProfileEquipmentDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun coachSummaryDao(): CoachSummaryDao
    abstract fun reminderConfigDao(): ReminderConfigDao
    abstract fun planEntryDao(): PlanEntryDao

    companion object {
        @Volatile
        private var INSTANCE: IronlogDatabase? = null

        fun get(context: Context): IronlogDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    IronlogDatabase::class.java,
                    "ironlog.db",
                )
                    // fallbackToDestructiveMigration() removed in M1a — real AutoMigration
                    // 2->3 is in place; user data is now preserved across schema changes.
                    .build().also { INSTANCE = it }
            }
    }
}
