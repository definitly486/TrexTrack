package org.definitly.trextrack

/**
 * Concrete cloud repository: direct auth + Keystore-backed session.
 * Workout list / track download remain stubs until route API is verified.
 */
class ZeppCloudRepositoryImpl(
    private val auth: ZeppAuthClient = ZeppAuthClient(),
    private val store: SessionStore
) : ZeppCloudRepository {

    override suspend fun authenticate(account: String, credential: CharArray): AuthResult {
        return auth.login(account, credential).fold(
            onSuccess = { session ->
                store.save(session)
                AuthResult.Success
            },
            onFailure = { e ->
                AuthResult.Failure(e.message ?: "Ошибка авторизации")
            }
        )
    }

    override suspend fun listWorkouts(): List<WorkoutSummary> {
        val session = store.load() ?: error("Не авторизован")
        // TODO: paginated history with header apptoken = session.appToken
        // against session.regionHost after endpoint is confirmed.
        return emptyList()
    }

    override suspend fun downloadTrack(workoutId: String): GpxTrack {
        val session = store.load() ?: error("Не авторизован")
        // TODO: GPS points → GPX 1.1
        return GpxTrack(workoutId, "")
    }

    fun logout() {
        store.clear()
    }

    fun isLoggedIn(): Boolean = store.isLoggedIn()

    fun currentSession(): ZeppSession? = store.load()
}
