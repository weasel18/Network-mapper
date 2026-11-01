package com.networkmapper.app.sync

import com.networkmapper.app.net.HttpStatusException
import com.networkmapper.app.net.PinnedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** The sync server's API (see server/server.py). Swappable for tests. */
interface SyncTransport {
    /** Sends changes; returns {"applied","ignored","seq"}. */
    suspend fun push(changes: List<JSONObject>): JSONObject

    /** Changes after [since]; returns {"changes":[…],"next":n,"more":bool}. */
    suspend fun pull(since: Long, sites: Set<String>?, collections: List<String>?, limit: Int): JSONObject

    /** {"sites":[{id,name,notes,updatedAt,assets,paths,sessions}]} */
    suspend fun sites(): JSONObject

    suspend fun uploadPhoto(uid: String, bytes: ByteArray)

    /** Null when the server doesn't have it. */
    suspend fun downloadPhoto(uid: String): ByteArray?
}

class HttpSyncTransport(
    url: String,
    pinnedFingerprint: String?,
    private val key: String,
    private val deviceId: String,
    private val deviceName: String,
) : SyncTransport {

    private val http = PinnedHttp(url, pinnedFingerprint)

    private val headers: Map<String, String>
        get() = mapOf(
            "Authorization" to "Bearer $key",
            "X-Device-Id" to deviceId,
            "X-Device-Name" to deviceName,
        )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun checked(r: PinnedHttp.Response, what: String): PinnedHttp.Response {
        if (r.ok) return r
        val msg = when (r.code) {
            401 -> "the access key was refused"
            404 -> "not found – is this the sync server address?"
            413 -> "too large for the server"
            else -> "HTTP ${r.code}"
        }
        throw HttpStatusException(r.code, "Sync server: couldn't $what: $msg")
    }

    /** Unauthenticated reachability check. */
    suspend fun health(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(checked(http.request("GET", "/api/health"), "connect").text)
    }

    override suspend fun push(changes: List<JSONObject>): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("changes", JSONArray(changes)).toString().toByteArray()
        JSONObject(checked(http.request("POST", "/api/push", body, headers), "upload changes").text)
    }

    override suspend fun pull(since: Long, sites: Set<String>?, collections: List<String>?, limit: Int): JSONObject =
        withContext(Dispatchers.IO) {
            val q = StringBuilder("/api/pull?since=$since&limit=$limit&exclude_device=${enc(deviceId)}")
            if (!sites.isNullOrEmpty()) q.append("&sites=").append(enc(sites.joinToString(",")))
            if (!collections.isNullOrEmpty()) q.append("&collections=").append(enc(collections.joinToString(",")))
            JSONObject(checked(http.request("GET", q.toString(), headers = headers), "download changes").text)
        }

    override suspend fun sites(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(checked(http.request("GET", "/api/sites", headers = headers), "list sites").text)
    }

    override suspend fun uploadPhoto(uid: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            checked(
                http.request("PUT", "/api/photos/${enc(uid)}", bytes, headers, contentType = "application/octet-stream", readTimeoutMs = 120_000),
                "upload a photo",
            )
        }
    }

    override suspend fun downloadPhoto(uid: String): ByteArray? = withContext(Dispatchers.IO) {
        val r = http.request("GET", "/api/photos/${enc(uid)}", headers = headers, readTimeoutMs = 120_000)
        when {
            r.ok -> r.body
            r.code == 404 -> null
            else -> checked(r, "download a photo").body
        }
    }
}
