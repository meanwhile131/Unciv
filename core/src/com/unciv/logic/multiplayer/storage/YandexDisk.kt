package com.unciv.logic.multiplayer.storage

import com.unciv.Constants
import com.unciv.json.json
import com.unciv.ui.components.extensions.UncivDateFormat.parseDate
import com.unciv.utils.debug
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File storage backend based on the Yandex Disk REST API, mirroring [DropBox].
 *
 * Identified by [Constants.yandexDiskMultiplayerServer] (and a few aliases) in the multiplayer settings.
 */
object YandexDisk : FileStorage {
    private const val API_BASE = "https://cloud-api.yandex.net/v1/disk"
    private const val ROOT_FOLDER = "/MultiplayerGames"

    // OAuth token for the shared Yandex Disk account.
    //
    // The token is intentionally NOT hard-coded: it is supplied through a GitHub
    // secret (YANDEX_OAUTH_TOKEN and its aliases) and injected at build time, or
    // provided via an environment variable / system property for local testing.
    // The (git-ignored, locally-generated) classpath resource
    // "/yandex_disk_token.txt" is also consulted as a fallback.
    private val OAUTH_TOKEN: String = loadYandexOAuthToken()

    private fun loadYandexOAuthToken(): String {
        for (envName in listOf(
            "YANDEX_OAUTH_TOKEN",
            "YANDEX_DISK_OAUTH_TOKEN",
            "YANDEX_DISK_TOKEN",
            "YANDEX_TOKEN"
        )) {
            val fromEnv = System.getenv(envName)
            if (!fromEnv.isNullOrBlank()) return fromEnv.trim()
        }
        val fromProp = System.getProperty("yandex.disk.oauth.token")
        if (!fromProp.isNullOrBlank()) return fromProp.trim()
        return try {
            YandexDisk::class.java.getResourceAsStream("/yandex_disk_token.txt")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
    private var remainingRateLimitSeconds = 0

    private fun localPath(fileName: String) = "$ROOT_FOLDER/$fileName"

    private fun urlEncodePath(path: String): String {
        return path.replace(" ", "%20")
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        includeAuth: Boolean = true
    ): HttpResponse {
        if (remainingRateLimitSeconds > 0) {
            throw FileStorageRateLimitReached(remainingRateLimitSeconds)
        }

        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true

            if (includeAuth && OAUTH_TOKEN.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "OAuth $OAUTH_TOKEN")
            }
            for ((k, v) in headers) conn.setRequestProperty(k, v)

            if (body != null) {
                conn.doOutput = true
                DataOutputStream(conn.outputStream).use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code == 429) {
                throw FileStorageRateLimitReached(60)
            }
            return HttpResponse(code, text)
        } catch (ex: FileStorageRateLimitReached) {
            throw ex
        } catch (ex: Exception) {
            debug("Yandex Disk exception", ex)
            val msg = ex.message.orEmpty()
            throw Exception(msg, ex)
        } finally {
            conn.disconnect()
        }
    }

    private fun throwForCode(code: Int, body: String, throwable: Throwable? = null) {
        when (code) {
            401, 403 -> throw MultiplayerAuthException(throwable ?: Exception(body))
            404 -> throw MultiplayerFileNotFoundException(throwable ?: Exception(body))
            429 -> throw FileStorageRateLimitReached(60)
        }
    }

    /** Outcome of a status check against the Yandex Disk API. See [checkStatus]. */
    sealed class Status {
        /** The API is reachable and accepted this build's OAuth token. */
        object Online : Status()

        /** The API is reachable, but this build has no OAuth token configured. */
        object NoToken : Status()

        /** The API is reachable, but rejected this build's OAuth token. */
        object InvalidToken : Status()

        /**
         * The API could not be reached, or answered with an unexpected status code.
         * [cause] is for logging only - it is never shown to the user.
         */
        class Unreachable(val cause: Throwable?) : Status()

        /** Untranslated, user-facing description of this status. */
        val description: String
            get() = when (this) {
                Online -> "Connected to Yandex Disk!"
                NoToken -> "Yandex Disk is reachable, but no OAuth token is configured!"
                InvalidToken -> "Yandex Disk rejected this build's OAuth token!"
                is Unreachable -> "Could not reach Yandex Disk!"
            }

        companion object {
            /**
             * Translates a response of the user-info endpoint into a [Status].
             *
             * Kept separate from [YandexDisk.checkStatus] so it can be tested without network access.
             *
             * @param hasToken whether an OAuth token is configured for this build
             */
            fun fromResponse(code: Int, hasToken: Boolean): Status = when (code) {
                in 200..299 -> Online
                // Without a token we never got far enough to have one rejected
                401, 403 -> if (hasToken) InvalidToken else NoToken
                else -> Unreachable(Exception("Yandex Disk answered with HTTP $code"))
            }
        }
    }

    /**
     * Checks that the Yandex Disk API is reachable and usable with this build's OAuth token.
     *
     * Yandex Disk has no dedicated status endpoint, so this queries the user-info endpoint
     * ([API_BASE]), which doubles as a token validation - an unauthorized response means the
     * API answered, so the distinction between [Status.NoToken] and [Status.InvalidToken] holds.
     *
     * @throws FileStorageRateLimitReached if Yandex Disk is rate limiting this account
     */
    fun checkStatus(): Status {
        val response = try {
            request("GET", API_BASE)
        } catch (ex: FileStorageRateLimitReached) {
            throw ex
        } catch (ex: Throwable) {
            return Status.Unreachable(ex)
        }
        return Status.fromResponse(response.code, OAUTH_TOKEN.isNotEmpty())
    }

    private fun ensureFolder() {
        val url = "$API_BASE/resources?path=${urlEncodePath(ROOT_FOLDER)}"
        val resp = request("PUT", url)
        // 201 created, 409 already exists are fine
        if (resp.code !in setOf(201, 200, 409, 409)) {
            throwForCode(resp.code, resp.body)
        }
    }

    private class UploadLink {
        var href = ""
        var method = "PUT"
    }

    private class DownloadLink {
        var href = ""
        var method = "GET"
    }

    @Suppress("PropertyName")
    private class YaMetaData : FileMetaData {
        var modified = ""
        var name = ""

        override fun getLastModified(): Date? = parseDate(modified)
    }

    private fun parseDate(value: String): Date? {
        if (value.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )
        for (pattern in patterns) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(value)
            } catch (_: Exception) {
            }
        }
        return try {
            value.parseDate()
        } catch (_: Exception) {
            null
        }
    }

    override fun saveFileData(fileName: String, data: String) {
        ensureFolder()
        val path = urlEncodePath(localPath(fileName))
        val uploadUrl = "$API_BASE/resources/upload?path=$path&overwrite=true"
        val linkResp = request("GET", uploadUrl)
        if (linkResp.code !in 200..299) {
            throwForCode(linkResp.code, linkResp.body)
            throw Exception("${linkResp.code} ${linkResp.body}")
        }
        val href = try {
            json().fromJson(UploadLink::class.java, linkResp.body).href
        } catch (e: Exception) {
            debug("Yandex Disk upload link parse error", e)
            throw Exception(linkResp.body, e)
        }
        val putResp = request("PUT", href, body = data, includeAuth = false)
        if (putResp.code !in setOf(200, 201, 202)) {
            throwForCode(putResp.code, putResp.body)
            throw Exception("${putResp.code} ${putResp.body}")
        }
    }

    override fun loadFileData(fileName: String): String {
        val path = urlEncodePath(localPath(fileName))
        val downloadUrl = "$API_BASE/resources/download?path=$path"
        val linkResp = request("GET", downloadUrl)
        if (linkResp.code !in 200..299) {
            throwForCode(linkResp.code, linkResp.body)
            throw MultiplayerFileNotFoundException(Exception(linkResp.body))
        }
        val href = try {
            json().fromJson(DownloadLink::class.java, linkResp.body).href
        } catch (e: Exception) {
            throw Exception(linkResp.body, e)
        }
        val getResp = request("GET", href, includeAuth = false)
        if (getResp.code !in 200..299) {
            throwForCode(getResp.code, getResp.body)
            throw Exception("${getResp.code} ${getResp.body}")
        }
        return getResp.body
    }

    override fun getFileMetaData(fileName: String): FileMetaData {
        val path = urlEncodePath(localPath(fileName))
        val url = "$API_BASE/resources?path=$path"
        val resp = request("GET", url)
        if (resp.code !in 200..299) {
            throwForCode(resp.code, resp.body)
            throw MultiplayerFileNotFoundException(Exception(resp.body))
        }
        return try {
            json().fromJson(YaMetaData::class.java, resp.body)
        } catch (e: Exception) {
            throw Exception(resp.body, e)
        }
    }

    override fun deleteFile(fileName: String) {
        val path = urlEncodePath(localPath(fileName))
        val url = "$API_BASE/resources?path=$path&permanently=true"
        val resp = request("DELETE", url)
        if (resp.code !in 200..299 && resp.code != 204) {
            throwForCode(resp.code, resp.body)
            // 404 means already gone - ignore
            if (resp.code != 404) throw Exception("${resp.code} ${resp.body}")
        }
    }

    override fun authenticate(userId: String, password: String): Boolean {
        throw NotImplementedError()
    }

    override fun checkAuthStatus(userId: String, password: String): AuthStatus {
        throw NotImplementedError()
    }

    override fun setPassword(newPassword: String): Boolean {
        throw NotImplementedError()
    }

    // expose for possible music/download use; not part of interface
    fun downloadFile(fileName: String): java.io.InputStream {
        val path = urlEncodePath(fileName)
        val downloadUrl = "$API_BASE/resources/download?path=$path"
        val linkResp = request("GET", downloadUrl)
        if (linkResp.code !in 200..299) throw Exception("${linkResp.code} ${linkResp.body}")
        val href = json().fromJson(DownloadLink::class.java, linkResp.body).href
        val conn = URI(href).toURL().openConnection() as HttpURLConnection
        return conn.inputStream
    }
}
