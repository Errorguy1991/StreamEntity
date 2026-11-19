package com.streamentity.engine

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class StreamServer(
    private val context: Context,
    private val engine: TorrentEngine,
    port: Int = 3000
) : NanoWSD(port) {

    private val TAG = "StreamEntityServer"
    private val gson = Gson()
    private val activeSockets = CopyOnWriteArrayList<TelemetryWebSocket>()
    private var telemetryRunning = true

    init {
        // Start background telemetry broadcast loop (2 updates/sec to all connected UI clients)
        thread(isDaemon = true, name = "TelemetryBroadcaster") {
            while (telemetryRunning) {
                try {
                    Thread.sleep(500)
                    if (activeSockets.isNotEmpty()) {
                        val tele = engine.getTelemetry()
                        val json = gson.toJson(tele)
                        for (ws in activeSockets) {
                            try {
                                if (ws.isOpen) ws.send(json)
                            } catch (e: Exception) {
                                activeSockets.remove(ws)
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in telemetry broadcast", e)
                }
            }
        }
    }

    override fun stop() {
        telemetryRunning = false
        for (ws in activeSockets) {
            try { ws.close(CloseCode.NormalClosure, "Server stopping", false) } catch (e: Exception) {}
        }
        activeSockets.clear()
        super.stop()
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return TelemetryWebSocket(handshake)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "text/plain", "")
            addCorsHeaders(r)
            return r
        }

        try {
            return when {
                uri == "/api/health" -> handleHealth()
                uri == "/api/torrents" && method == Method.GET -> handleGetTorrents()
                uri == "/api/torrents/add" && method == Method.POST -> handleAddTorrent(session)
                uri.startsWith("/api/torrents/") && uri.endsWith("/pause") && method == Method.POST -> handlePauseTorrent(uri)
                uri.startsWith("/api/torrents/") && uri.endsWith("/resume") && method == Method.POST -> handleResumeTorrent(uri)
                uri.startsWith("/api/torrents/") && method == Method.DELETE -> handleDeleteTorrent(uri, session)
                uri.startsWith("/stream/") -> handleStream(uri, session)
                uri == "/" || uri == "/index.html" -> serveAsset("www/index.html", "text/html")
                else -> {
                    val assetPath = "www" + if (uri.startsWith("/")) uri else "/$uri"
                    if (assetExists(assetPath)) {
                        serveAsset(assetPath, resolveMimeType(assetPath))
                    } else {
                        serveAsset("www/index.html", "text/html")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error handling $uri", e)
            val errJson = """{"error":"${e.message?.replace("\"", "\\\"") ?: "Internal server error"}"}"""
            val r = NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", errJson)
            addCorsHeaders(r)
            return r
        }
    }

    private fun handleHealth(): Response {
        val json = """{"status":"ok","engine":"StreamEntity Android Native","version":"1.0.0","local":true,"ffmpeg":false}"""
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", json)
        addCorsHeaders(r)
        return r
    }

    private fun handleGetTorrents(): Response {
        val torrents = engine.getActiveTorrents()
        val json = gson.toJson(torrents)
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", json)
        addCorsHeaders(r)
        return r
    }

    private fun handleAddTorrent(session: IHTTPSession): Response {
        val body = parseBody(session)
        val map = gson.fromJson(body, Map::class.java)
        val torrentId = map["torrentId"] as? String ?: ""
        if (torrentId.isEmpty()) {
            val r = NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, "application/json", """{"error":"Missing torrentId"}""")
            addCorsHeaders(r)
            return r
        }

        val summary = engine.addTorrent(torrentId)
        val json = if (summary != null) {
            """{"success":true,"torrent":${gson.toJson(summary)}}"""
        } else {
            """{"success":false,"error":"Failed to add torrent"}"""
        }
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", json)
        addCorsHeaders(r)
        return r
    }

    private fun handlePauseTorrent(uri: String): Response {
        val hash = uri.substringAfter("/api/torrents/").substringBefore("/pause")
        val success = engine.pauseTorrent(hash)
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", """{"success":$success}""")
        addCorsHeaders(r)
        return r
    }

    private fun handleResumeTorrent(uri: String): Response {
        val hash = uri.substringAfter("/api/torrents/").substringBefore("/resume")
        val success = engine.resumeTorrent(hash)
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", """{"success":$success}""")
        addCorsHeaders(r)
        return r
    }

    private fun handleDeleteTorrent(uri: String, session: IHTTPSession): Response {
        val hash = uri.substringAfter("/api/torrents/").trim()
        val deleteFiles = session.parms["deleteFiles"] == "true"
        val success = engine.removeTorrent(hash, deleteFiles)
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", """{"success":$success}""")
        addCorsHeaders(r)
        return r
    }

    /**
     * HTTP 206 Partial Content Video Streaming for MKV, MP4, etc.
     */
    private fun handleStream(uri: String, session: IHTTPSession): Response {
        val parts = uri.substringAfter("/stream/").split("/")
        val infoHash = parts[0]

        // Allow up to 3 seconds for file and initial piece to begin buffering
        var file = engine.getDownloadedFile(infoHash)
        var totalLength = if (file != null && file.exists()) file.length() else engine.getStreamingFileSize(infoHash)
        var waited = 0
        while ((file == null || !file.exists() || totalLength <= 0) && waited < 3000) {
            Thread.sleep(200)
            waited += 200
            file = engine.getDownloadedFile(infoHash)
            totalLength = if (file != null && file.exists()) file.length() else engine.getStreamingFileSize(infoHash)
        }

        if (totalLength <= 0) {
            val r = NanoHTTPD.newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE,
                "application/json",
                """{"error":"Torrent stream not ready yet — resolving metadata and buffering initial pieces."}"""
            )
            r.addHeader("Retry-After", "1")
            addCorsHeaders(r)
            return r
        }

        val mimeType = if (file?.name?.lowercase()?.endsWith(".mkv") == true) "video/x-matroska" else "video/mp4"
        val rangeHeader = session.headers["range"] ?: session.headers["Range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.substring(6).trim()
            val dashIdx = rangeVal.indexOf('-')
            var start = 0L
            var end = totalLength - 1

            if (dashIdx != -1) {
                val startStr = rangeVal.substring(0, dashIdx).trim()
                val endStr = rangeVal.substring(dashIdx + 1).trim()
                if (startStr.isNotEmpty()) start = startStr.toLongOrNull() ?: 0L
                if (endStr.isNotEmpty()) end = endStr.toLongOrNull() ?: (totalLength - 1)
            }

            if (start > totalLength - 1) start = totalLength - 1
            if (end > totalLength - 1) end = totalLength - 1
            if (start > end) start = end

            val contentLength = end - start + 1
            val stream = createPartialStream(file, start, contentLength)

            val r = NanoHTTPD.newFixedLengthResponse(Status.PARTIAL_CONTENT, mimeType, stream, contentLength)
            r.addHeader("Content-Range", "bytes $start-$end/$totalLength")
            r.addHeader("Accept-Ranges", "bytes")
            r.addHeader("Content-Length", contentLength.toString())
            addCorsHeaders(r)
            return r
        }

        // Full file response with Accept-Ranges
        val actualStreamLength = if (file != null && file.exists()) file.length() else 0L
        val stream = if (actualStreamLength > 0) FileInputStream(file!!) else ByteArrayInputStream(ByteArray(0))
        val r = NanoHTTPD.newFixedLengthResponse(Status.OK, mimeType, stream, actualStreamLength)
        r.addHeader("Accept-Ranges", "bytes")
        r.addHeader("Content-Length", actualStreamLength.toString())
        addCorsHeaders(r)
        return r
    }

    private fun createPartialStream(file: File?, start: Long, length: Long): InputStream {
        if (file == null || !file.exists()) {
            return object : InputStream() {
                private var bytesRemaining = length
                override fun read(): Int {
                    if (bytesRemaining <= 0) return -1
                    bytesRemaining--
                    return 0
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (bytesRemaining <= 0) return -1
                    val toRead = Math.min(len.toLong(), bytesRemaining).toInt()
                    b.fill(0, off, off + toRead)
                    bytesRemaining -= toRead
                    return toRead
                }
            }
        }
        val raf = try { RandomAccessFile(file, "r") } catch (e: Exception) { null }
        if (raf == null) {
            return ByteArrayInputStream(ByteArray(0))
        }
        try {
            val fileLen = raf.length()
            val safeStart = Math.min(start, fileLen)
            raf.seek(safeStart)
        } catch (e: Exception) {}

        return object : InputStream() {
            private var bytesRemaining = length

            override fun read(): Int {
                if (bytesRemaining <= 0) return -1
                val b = try { raf.read() } catch (e: Exception) { -1 }
                if (b != -1) {
                    bytesRemaining--
                    return b
                }
                // If sparse file EOF reached before contentLength, fill with 0x00
                bytesRemaining--
                return 0
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (bytesRemaining <= 0) return -1
                val toRead = Math.min(len.toLong(), bytesRemaining).toInt()
                val read = try { raf.read(b, off, toRead) } catch (e: Exception) { -1 }
                if (read > 0) {
                    bytesRemaining -= read
                    return read
                } else if (read == -1) {
                    // Sparse region or end of partial file: fill with 0s up to toRead
                    b.fill(0, off, off + toRead)
                    bytesRemaining -= toRead
                    return toRead
                }
                return 0
            }

            override fun close() {
                try { raf.close() } catch (e: Exception) {}
            }
        }
    }

    private fun serveAsset(assetPath: String, mime: String): Response {
        return try {
            val stream = context.assets.open(assetPath)
            val r = NanoHTTPD.newChunkedResponse(Status.OK, mime, stream)
            addCorsHeaders(r)
            r
        } catch (e: IOException) {
            val r = NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Asset not found: $assetPath")
            addCorsHeaders(r)
            r
        }
    }

    private fun assetExists(path: String): Boolean {
        return try {
            context.assets.open(path).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun parseBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD")
        response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Range")
        response.addHeader("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length")
    }

    private fun resolveMimeType(filename: String): String {
        return when {
            filename.endsWith(".html") -> "text/html"
            filename.endsWith(".css") -> "text/css"
            filename.endsWith(".js") -> "application/javascript"
            filename.endsWith(".png") -> "image/png"
            filename.endsWith(".svg") -> "image/svg+xml"
            filename.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
    }

    inner class TelemetryWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            activeSockets.add(this)
            Log.d(TAG, "WebSocket client connected. Total clients: ${activeSockets.size}")
        }

        override fun onClose(code: CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            activeSockets.remove(this)
            Log.d(TAG, "WebSocket client closed. Total clients: ${activeSockets.size}")
        }

        override fun onMessage(message: WebSocketFrame?) {
            // No client actions needed via WS; telemetry is push-only
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            activeSockets.remove(this)
        }
    }
}
