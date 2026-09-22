package dev.ironlog.app.data

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room type converters: ExerciseType enum + List<String> ↔ JSON TEXT. */
class Converters {
    @TypeConverter
    fun fromExerciseType(type: ExerciseType): String = type.name

    @TypeConverter
    fun toExerciseType(value: String): ExerciseType = ExerciseType.valueOf(value)

    @TypeConverter
    fun fromStringList(list: List<String>?): String? =
        list?.let { Json.encodeToString(it) }

    @TypeConverter
    fun toStringList(value: String?): List<String>? =
        value?.let { Json.decodeFromString(it) }
}
