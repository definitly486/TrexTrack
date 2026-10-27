package org.definitly.trextrack

/**
 * Cloud integration boundary.
 *
 * This interface intentionally contains no guessed endpoints or fake activity data.
 * Implement only after validating a lawful, working authentication flow and the
 * route/workout response schema against a test account.
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

/** Ошибка загрузки трека с понятной категорией — чтобы в отчёте видеть, ПОЧЕМУ трек не скачался. */
class TrackDownloadException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    enum class Kind(val label: String, val retryable: Boolean) {
        NO_GPS("нет GPS-данных", false),
        NOT_FOUND("не найдено (404)", false),
        AUTH("сессия отклонена (401/403)", false),
        PARSE("неожиданный ответ", false),
        RATE_LIMIT("лимит запросов (429)", true),
        SERVER("ошибка сервера (5xx)", true),
        NETWORK("сеть / таймаут", true)
    }
}
