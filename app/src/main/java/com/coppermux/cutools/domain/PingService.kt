package com.coppermux.cutools.domain

import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class PingService(
    private val client: OkHttpClient
) {
    suspend fun pingAll(sessions: List<WorkspaceSession>): Map<String, ConnectionStatus> =
        withContext(Dispatchers.IO) {
            sessions.map { session ->
                async {
                    val status = try {
                        val request = Request.Builder()
                            .url(session.targetUrl)
                            .head()
                            .build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) ConnectionStatus.ACTIVE else ConnectionStatus.INACTIVE
                    } catch (e: Exception) {
                        ConnectionStatus.INACTIVE
                    }
                    session.id to status
                }
            }.awaitAll().associate { it.first to it.second }
        }
}