package dev.ironlog.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.ironlog.app.progression.GoalPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ironlog_settings")

object SettingsKeys {
    val DEFAULT_BAR_LB = doublePreferencesKey("default_bar_lb")
    val DEFAULT_REST_SEC = intPreferencesKey("default_rest_sec")
    val DEFAULT_GOAL_PRESET = stringPreferencesKey("default_goal_preset")
    // M6 toggles
    val LOCK_COMPLETED_SETS = booleanPreferencesKey("lock_completed_sets")
    val DELETE_SET_CONFIRMATION = booleanPreferencesKey("delete_set_confirmation")
    val PREVENT_SLEEP = booleanPreferencesKey("prevent_sleep")
    val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    val XRM_FORMULA = stringPreferencesKey("xrm_formula")
    val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
    val AUTO_BACKUP_URI = stringPreferencesKey("auto_backup_uri")
    // F9: last backup outcome ("Backed up <name> (<time>)" / "Backup failed: <reason>"), shown
    // in Settings so the toggle isn't a black box.
    val LAST_BACKUP_STATUS = stringPreferencesKey("last_backup_status")
    // Last HC sync epoch ms (for dedup window)
    val LAST_HC_SYNC_MS = stringPreferencesKey("last_hc_sync_ms")
    // F1 one-time repair: imported "Rest Timer"/"Note" metadata rows purged from sets
    val IMPORTED_METADATA_REPAIR_DONE = booleanPreferencesKey("imported_metadata_repair_done")
    val CARDIO_TYPE_REPAIR_DONE = booleanPreferencesKey("cardio_type_repair_v2_done")  // v2 adds the catalog relabel; reruns once, idempotent
    // F6: rest-over alert sound. Absent = system default; NONE_SENTINEL = explicitly silent
    // (vibration only); anything else is a content:// URI string from the ringtone picker.
    val REST_SOUND_URI = stringPreferencesKey("rest_sound_uri")
}

class AppSettings(private val context: Context) {

    val defaultBarLb: Flow<Double> = context.dataStore.data
        .map { it[SettingsKeys.DEFAULT_BAR_LB] ?: 45.0 }

    val defaultRestSec: Flow<Int> = context.dataStore.data
        .map { it[SettingsKeys.DEFAULT_REST_SEC] ?: 60 }

    suspend fun setDefaultBarLb(lb: Double) {
        context.dataStore.edit { it[SettingsKeys.DEFAULT_BAR_LB] = lb }
    }

    suspend fun setDefaultRestSec(sec: Int) {
        context.dataStore.edit { it[SettingsKeys.DEFAULT_REST_SEC] = sec }
    }

    /** App default goal preset (M4 step 12) -- HYPERTROPHY by design, chooser surfaced in UI. */
    val defaultGoalPreset: Flow<GoalPreset> = context.dataStore.data
        .map { GoalPreset.fromNameOrDefault(it[SettingsKeys.DEFAULT_GOAL_PRESET]) }

    suspend fun setDefaultGoalPreset(preset: GoalPreset) {
        context.dataStore.edit { it[SettingsKeys.DEFAULT_GOAL_PRESET] = preset.name }
    }

    // --- M6 settings ---

    // RETIRED FEATURES — DO NOT RE-WIRE without asking the owner. See
    // specs/decisions/settings-lock-and-delete-confirm-removed.md.
    // `lockCompletedSets` (lock a checked set so it can't be unchecked) and
    // `deleteSetConfirmation` (confirm-before-swipe-delete) were M6 toggles that were briefly
    // wired on 2026-06-22 and then DELIBERATELY REMOVED the same day: set-locking was never
    // wanted, and delete-confirmation had defaulted ON and silently changed the instant
    // swipe-delete. The Settings UI toggles + the active-workout wiring are GONE. This storage
    // plumbing is kept ONLY so the persisted DataStore keys remain readable (no orphaned keys);
    // nothing consumes these flows. Don't add a Settings toggle or active-workout behavior back.

    /** RETIRED (see note above). Persisted-but-unconsumed: was "lock checked sets". */
    val lockCompletedSets: Flow<Boolean> = context.dataStore.data
        .map { it[SettingsKeys.LOCK_COMPLETED_SETS] ?: false }

    suspend fun setLockCompletedSets(on: Boolean) {
        context.dataStore.edit { it[SettingsKeys.LOCK_COMPLETED_SETS] = on }
    }

    /** RETIRED (see note above). Persisted-but-unconsumed: was "confirm before deleting a set". */
    val deleteSetConfirmation: Flow<Boolean> = context.dataStore.data
        .map { it[SettingsKeys.DELETE_SET_CONFIRMATION] ?: true }

    suspend fun setDeleteSetConfirmation(on: Boolean) {
        context.dataStore.edit { it[SettingsKeys.DELETE_SET_CONFIRMATION] = on }
    }

    /** When true, the screen stays on during an active workout (WindowManager.FLAG_KEEP_SCREEN_ON). */
    val preventSleep: Flow<Boolean> = context.dataStore.data
        .map { it[SettingsKeys.PREVENT_SLEEP] ?: true }

    suspend fun setPreventSleep(on: Boolean) {
        context.dataStore.edit { it[SettingsKeys.PREVENT_SLEEP] = on }
    }

    /** When false, the rest-timer finish buzzes are silenced (vibration only). */
    val soundEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[SettingsKeys.SOUND_ENABLED] ?: true }

    suspend fun setSoundEnabled(on: Boolean) {
        context.dataStore.edit { it[SettingsKeys.SOUND_ENABLED] = on }
    }

    /** Selected 1RM formula ("EPLEY" | "BRZYCKI" | "LOMBARDI"). Default = Epley. */
    val xrmFormula: Flow<String> = context.dataStore.data
        .map { it[SettingsKeys.XRM_FORMULA] ?: "EPLEY" }

    suspend fun setXrmFormula(formula: String) {
        context.dataStore.edit { it[SettingsKeys.XRM_FORMULA] = formula }
    }

    /** Auto-backup to SAF URI on a weekly schedule. */
    val autoBackupEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[SettingsKeys.AUTO_BACKUP_ENABLED] ?: false }

    suspend fun setAutoBackupEnabled(on: Boolean) {
        context.dataStore.edit { it[SettingsKeys.AUTO_BACKUP_ENABLED] = on }
    }

    val autoBackupUri: Flow<String?> = context.dataStore.data
        .map { it[SettingsKeys.AUTO_BACKUP_URI] }

    suspend fun setAutoBackupUri(uri: String?) {
        context.dataStore.edit {
            if (uri != null) it[SettingsKeys.AUTO_BACKUP_URI] = uri
            else it.remove(SettingsKeys.AUTO_BACKUP_URI)
        }
    }

    /** F9: last backup outcome, shown as a status line under the auto-backup toggle. */
    val lastBackupStatus: Flow<String?> = context.dataStore.data
        .map { it[SettingsKeys.LAST_BACKUP_STATUS] }

    suspend fun setLastBackupStatus(status: String) {
        context.dataStore.edit { it[SettingsKeys.LAST_BACKUP_STATUS] = status }
    }

    val lastHcSyncMs: Flow<Long> = context.dataStore.data
        .map { it[SettingsKeys.LAST_HC_SYNC_MS]?.toLongOrNull() ?: 0L }

    suspend fun setLastHcSyncMs(ms: Long) {
        context.dataStore.edit { it[SettingsKeys.LAST_HC_SYNC_MS] = ms.toString() }
    }

    /** F1: one-time purge of imported metadata rows ("Rest Timer"/"Note") already done. */
    val importedMetadataRepairDone: Flow<Boolean> = context.dataStore.data
        .map { it[SettingsKeys.IMPORTED_METADATA_REPAIR_DONE] ?: false }

    suspend fun setImportedMetadataRepairDone() {
        context.dataStore.edit { it[SettingsKeys.IMPORTED_METADATA_REPAIR_DONE] = true }
    }

    /** One-time re-typing of distance+time exercises (treadmill/bike) to CARDIO already done. */
    val cardioTypeRepairDone: Flow<Boolean> = context.dataStore.data
        .map { it[SettingsKeys.CARDIO_TYPE_REPAIR_DONE] ?: false }

    suspend fun setCardioTypeRepairDone() {
        context.dataStore.edit { it[SettingsKeys.CARDIO_TYPE_REPAIR_DONE] = true }
    }

    /**
     * F6: rest-over alert sound, as the raw stored preference (null = system default,
     * [RestSoundPref.NONE_SENTINEL] = explicitly silent, else a ringtone content:// URI string).
     * Kept as a raw String? flow (not resolved to an actual URI here) so RestAlarmReceiver --
     * which fires outside any Activity/Compose context -- can read it standalone.
     */
    val restSoundUri: Flow<String?> = context.dataStore.data
        .map { it[SettingsKeys.REST_SOUND_URI] }

    suspend fun setRestSoundUri(value: String?) {
        context.dataStore.edit {
            if (value != null) it[SettingsKeys.REST_SOUND_URI] = value
            else it.remove(SettingsKeys.REST_SOUND_URI)
        }
    }
}

/** F6: the stored rest-sound preference is a bare String? -- this documents its 3 states. */
object RestSoundPref {
    /** Stored value meaning "explicitly silent" (the picker's own "Silent" option was chosen). */
    const val NONE_SENTINEL = "NONE"

    /** Ringtone picker returned null URI ("Silent") -> [NONE_SENTINEL]; else the URI string as-is. */
    fun fromPickerResult(pickedUriString: String?): String = pickedUriString ?: NONE_SENTINEL

    /** True when the stored pref means "no sound at all" (vibration only). */
    fun isSilent(stored: String?): Boolean = stored == NONE_SENTINEL

    /** True when the stored pref means "follow the device's current default sound". */
    fun isSystemDefault(stored: String?): Boolean = stored == null
}
