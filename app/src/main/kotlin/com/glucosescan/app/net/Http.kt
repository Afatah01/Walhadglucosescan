package com.glucosescan.app.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One question to a server on the internet. */
class HttpRequest(
    /** "GET", "POST" or "DELETE". */
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** The text to send (JSON), or null. */
    val body: String? = null,
    /** How long to wait for the answer. An AI can need a minute or two. */
    val readTimeoutMillis: Int = 30_000
)

/** The answer of the server. */
class HttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    val isSuccess: Boolean
        get() = code in 200..299

    /** The header [name], whatever its capital letters are, or null. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** Sends a request and waits for the answer. Tests put a pretended server here. */
interface HttpTransport {
    /** @throws IOException if there is no connection or no answer */
    @Throws(IOException::class)
    fun send(request: HttpRequest): HttpResponse
}

/**
 * The real thing, made of Android's own classes. It talks only to addresses that start with
 * "https://": everything the app sends is encrypted on its way.
 */
class UrlTransport : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val url = try {
            URL(request.url)
        } catch (e: Exception) {
            throw IOException("not an internet address: " + request.url.take(60))
        }
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw IOException("only https addresses are allowed")
        }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = request.readTimeoutMillis
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            for ((name, value) in request.headers) {
                connection.setRequestProperty(name, value)
            }
            val body = request.body
            if (body != null) {
                // (The body is small and is handed over in one piece. "Streaming" it would make
                // Android throw an error instead of giving the answer when a server says "401".)
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream: InputStream? = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { read(it) } ?: ""
            val headers = HashMap<String, String>()
            for ((name, values) in connection.headerFields) {
                if (name != null && values != null && values.isNotEmpty()) {
                    headers[name] = values.joinToString(", ")
                }
            }
            return HttpResponse(code, text, headers)
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            // Android's classes throw this for some broken answers
            throw IOException(e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            connection.disconnect()
        }
    }

    private fun read(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) {
                break
            }
            out.write(buffer, 0, count)
            if (out.size() > MAX_ANSWER_BYTES) {
                throw IOException("the answer is too long")
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val MAX_ANSWER_BYTES = 8 * 1024 * 1024
    }
}

/** The one door of the app to the internet. */
object Net {
    /** Tests put a pretended server here. */
    @Volatile
    var transport: HttpTransport = UrlTransport()

    /**
     * Runs the work that waits for the internet, one piece after the other, away from the
     * screen. Tests put an executor here that runs the work at once.
     */
    @Volatile
    var background: Executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "walhad-net").also { it.isDaemon = true }
    }

    /**
     * For work that may wait for minutes (an AI that thinks, the online copy of many values),
     * so that it never holds up the question to LibreLinkUp that is due each minute.
     */
    @Volatile
    var patient: Executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "walhad-net-slow").also { it.isDaemon = true }
    }

    /** Short words about why a request failed, for the technical details. */
    fun words(error: Throwable): String {
        val name = error.javaClass.simpleName
        val message = error.message
        return if (message.isNullOrBlank()) name else "$name: ${message.take(120)}"
    }
}
