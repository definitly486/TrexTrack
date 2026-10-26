package org.definitly.trextrack

/**
 * Cloud integration boundary.
 *
 * Authentication is implemented via [ZeppCloudRepositoryImpl] (direct login +
 * Android Keystore session). Workout/track endpoints stay unimplemented until
 * the route response schema is verified against a test account.
 */
interface ZeppCloudRepository {
    suspend fun authenticate(account: String, credential: CharArray): AuthResult
    suspend fun listWorkouts(): List<WorkoutSummary>
    suspend fun downloadTrack(workoutId: String): GpxTrack
}

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val message: String) : AuthResult
}

data class WorkoutSummary(
    val id: String,
    val title: String,
    val startTimeIso: String?,
    val distanceMeters: Double?,
    val durationSeconds: Long?,
    val hasGpsTrack: Boolean
)

data class GpxTrack(
    val workoutId: String,
    val gpxXml: String
)
