package org.definitly.trextrack

/**
 * Minimal cloud repository: authentication + session persistence.
 * listWorkouts / downloadTrack remain stubs until workout API is wired.
 */
class ZeppCloudRepositoryImpl(
    private val sessionStore: SessionStore,
    private val authClient: ZeppAuthClient = ZeppAuthClient()
) : ZeppCloudRepository {

    override suspend fun authenticate(account: String, credential: CharArray): AuthResult {
        return try {
            val session = authClient.login(account.trim(), credential)
            credential.fill('\u0000')
            sessionStore.saveSession(
                appToken = session.appToken,
                loginToken = session.loginToken,
                userId = session.userId,
                accountHint = maskAccount(account)
            )
            AuthResult.Success
        } catch (e: ZeppAuthClient.AuthException) {
            credential.fill('\u0000')
            AuthResult.Failure(e.message ?: "Ошибка авторизации")
        } catch (e: Exception) {
            credential.fill('\u0000')
            AuthResult.Failure(e.message ?: "Сетевая или внутренняя ошибка")
        }
    }

    override suspend fun listWorkouts(): List<WorkoutSummary> {
        if (!sessionStore.isLoggedIn()) {
            throw IllegalStateException("Нет активной сессии")
        }
        // Next step: GET /v1/sport/run/history.json with apptoken header.
        return emptyList()
    }

    override suspend fun downloadTrack(workoutId: String): GpxTrack {
        if (!sessionStore.isLoggedIn()) {
            throw IllegalStateException("Нет активной сессии")
        }
        throw UnsupportedOperationException("Загрузка GPS-трека ещё не реализована")
    }

    fun logout() {
        sessionStore.clear()
    }

    private fun maskAccount(account: String): String {
        val a = account.trim()
        if (a.length <= 4) return "***"
        return a.take(2) + "***" + a.takeLast(2)
    }
}
