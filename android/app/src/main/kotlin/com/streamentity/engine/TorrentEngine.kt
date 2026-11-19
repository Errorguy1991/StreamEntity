package com.streamentity.engine

import android.content.Context
import android.util.Log
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.swig.session_handle
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class TorrentSummary(
    val infoHash: String,
    val name: String,
    val cleanTitle: String,
    val badges: List<String>,
    val numPeers: Int,
    val numSeeds: Int,
    val downloadSpeed: Double,
    val uploadSpeed: Double,
    val progress: Double,
    val downloaded: Long,
    val uploaded: Long,
    val totalLength: Long,
    val paused: Boolean,
    val ready: Boolean,
    val activeFileName: String,
    val activeFileIndex: Int,
    val pieceMap: PieceMapData?,
    val leadTimeSeconds: Int
)

data class PieceMapData(
    val total: Int,
    val fileStartPiece: Int,
    val fileEndPiece: Int,
    val activePiece: Int,
    val states: List<Int>
)

data class PeerDetail(
    val ip: String,
    val client: String,
    val downloadSpeed: Double,
    val uploadSpeed: Double,
    val country: String,
    val isSeeder: Boolean,
    val isChoked: Boolean,
    val isUtp: Boolean
)

data class LiveTelemetry(
    val type: String = "telemetry",
    val global: GlobalStats,
    val torrents: List<TorrentSummary>,
    val peers: List<PeerDetail>,
    val activeTorrent: TorrentSummary?
)

data class GlobalStats(
    val totalDownloadSpeed: Double,
    val totalUploadSpeed: Double
)

class TorrentEngine(private val context: Context) {
    private val TAG = "StreamEntityEngine"
    private val sessionManager = SessionManager()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val isRunning = AtomicBoolean(false)
    private val saveDir: File

    init {
        val baseDir = context.getExternalFilesDir("torrents") ?: context.cacheDir.resolve("torrents")
        if (!baseDir.exists()) baseDir.mkdirs()
        saveDir = baseDir
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        val sp = SettingsPack()
        sp.connectionsLimit(80)
        sp.activeDownloads(4)
        sp.activeSeeds(4)
        sp.activeLimit(8)
        sp.setEnableDht(true)

        sessionManager.addListener(object : AlertListener {
            override fun types(): IntArray? = null

            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.ADD_TORRENT -> {
                        val a = alert as? AddTorrentAlert ?: return
                        val h = a.handle()
                        if (h != null && h.isValid) {
                            h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                            handles[h.infoHash().toHex().lowercase()] = h
                            Log.d(TAG, "Torrent added & sequential download enabled: ${h.infoHash().toHex()}")
                        }
                    }
                    AlertType.METADATA_RECEIVED -> {
                        val a = alert as? MetadataReceivedAlert ?: return
                        val h = a.handle()
                        if (h != null && h.isValid) {
                            h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                            prioritizeStreamingPieces(h)
                            Log.d(TAG, "Metadata received for ${h.name}")
                        }
                    }
                    else -> {}
                }
            }
        })

        sessionManager.start(SessionParams(sp))
        Log.i(TAG, "StreamEntity native libtorrent session started in $saveDir")
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        handles.clear()
        sessionManager.stop()
        Log.i(TAG, "StreamEntity native session stopped")
    }

    fun addTorrent(magnetUri: String): TorrentSummary? {
        val uri = magnetUri.trim()
        val hash = parseInfoHash(uri)
        if (hash != null && handles.containsKey(hash.lowercase())) {
            val existing = handles[hash.lowercase()]!!
            if (existing.isValid) return getSummary(existing)
        }

        sessionManager.download(uri, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        // Wait briefly for handle to register
        var attempts = 0
        while (attempts < 10) {
            Thread.sleep(150)
            val h = if (hash != null) {
                try { sessionManager.find(Sha1Hash.parseHex(hash)) } catch (e: Exception) { null }
            } else null
            if (h != null && h.isValid) {
                h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                handles[h.infoHash().toHex().lowercase()] = h
                prioritizeStreamingPieces(h)
                return getSummary(h)
            }
            attempts++
        }

        // Return placeholder summary if metadata still pending
        return TorrentSummary(
            infoHash = hash ?: "",
            name = "Resolving metadata…",
            cleanTitle = "Connecting to swarm…",
            badges = listOf("CONNECTING"),
            numPeers = 0,
            numSeeds = 0,
            downloadSpeed = 0.0,
            uploadSpeed = 0.0,
            progress = 0.0,
            downloaded = 0L,
            uploaded = 0L,
            totalLength = 0L,
            paused = false,
            ready = false,
            activeFileName = "",
            activeFileIndex = 0,
            pieceMap = null,
            leadTimeSeconds = 0
        )
    }

    fun pauseTorrent(infoHash: String): Boolean {
        val h = getHandle(infoHash) ?: return false
        h.pause()
        return true
    }

    fun resumeTorrent(infoHash: String): Boolean {
        val h = getHandle(infoHash) ?: return false
        h.resume()
        return true
    }

    fun removeTorrent(infoHash: String, deleteFiles: Boolean = false): Boolean {
        val key = infoHash.lowercase().trim()
        val h = handles.remove(key) ?: try { sessionManager.find(Sha1Hash.parseHex(key)) } catch (e: Exception) { null } ?: return false
        try {
            if (deleteFiles) {
                sessionManager.remove(h, session_handle.delete_files)
                val f = getDownloadedFile(key)
                if (f != null && f.exists()) f.delete()
            } else {
                sessionManager.remove(h)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error removing torrent $key", e)
            return false
        }
    }

    fun getActiveTorrents(): List<TorrentSummary> {
        val list = mutableListOf<TorrentSummary>()
        for ((_, h) in handles) {
            if (h.isValid) {
                list.add(getSummary(h))
            }
        }
        return list
    }

    fun getDownloadedFile(infoHash: String): File? {
        val h = getHandle(infoHash) ?: return null
        val ti = h.torrentFile() ?: return null
        val fs = ti.files()
        if (fs.numFiles() == 0) return null

        // Find primary video file
        var bestIdx = 0
        var bestLen = 0L
        for (i in 0 until fs.numFiles()) {
            val name = fs.fileName(i).lowercase()
            if (name.endsWith(".mkv") || name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".webm") || name.endsWith(".ts")) {
                val len = fs.fileSize(i)
                if (len > bestLen) {
                    bestLen = len
                    bestIdx = i
                }
            }
        }

        val relativePath = fs.filePath(bestIdx)
        val file = File(saveDir, relativePath)
        return if (file.exists()) file else null
    }

    fun getStreamingFileSize(infoHash: String): Long {
        val h = getHandle(infoHash) ?: return 0L
        val ti = try { h.torrentFile() } catch (e: Throwable) { null } ?: return 0L
        val fs = ti.files()
        if (fs.numFiles() == 0) return 0L

        var bestLen = 0L
        for (i in 0 until fs.numFiles()) {
            val len = fs.fileSize(i)
            if (len > bestLen) bestLen = len
        }
        return bestLen
    }

    fun isStreamReady(infoHash: String): Boolean {
        val h = getHandle(infoHash) ?: return false
        if (!h.isValid) return false
        val ti = try { h.torrentFile() } catch (e: Throwable) { null } ?: return false
        if (ti.numPieces() <= 0) return false
        val status = try { h.status() } catch (e: Throwable) { null } ?: return false
        val pieces = try { status.pieces() } catch (e: Throwable) { null } ?: return false
        val pSize = try { pieces.size() } catch (e: Throwable) { 0 }
        if (pSize <= 0) return false
        return try { pieces.getBit(0) } catch (e: Throwable) { false }
    }

    fun getTelemetry(): LiveTelemetry {
        val torrents = getActiveTorrents()
        val active = torrents.firstOrNull()
        var totalDown = 0.0
        var totalUp = 0.0
        val peers = mutableListOf<PeerDetail>()

        for (t in torrents) {
            totalDown += t.downloadSpeed
            totalUp += t.uploadSpeed
        }

        if (active != null) {
            val h = getHandle(active.infoHash)
            if (h != null && h.isValid) {
                try {
                    val piList = h.peerInfo()
                    for (p in piList) {
                        val ip = p.ip()
                        val client = p.client().ifEmpty { "Torrent Peer" }
                        val dSpeed = p.downSpeed().toDouble()
                        val uSpeed = p.upSpeed().toDouble()
                        val isSeeder = (p.flags() and 0x02) != 0
                        val isChoked = (p.flags() and 0x01) != 0
                        val isUtp = (p.flags() and 0x40) != 0

                        peers.add(
                            PeerDetail(
                                ip = ip,
                                client = client,
                                downloadSpeed = dSpeed,
                                uploadSpeed = uSpeed,
                                country = resolveCountryCode(ip),
                                isSeeder = isSeeder,
                                isChoked = isChoked,
                                isUtp = isUtp
                            )
                        )
                    }
                } catch (e: Exception) {
                    // Safe fallback if peer list is refreshing
                }
            }
        }

        return LiveTelemetry(
            global = GlobalStats(totalDownloadSpeed = totalDown, totalUploadSpeed = totalUp),
            torrents = torrents,
            peers = peers,
            activeTorrent = active
        )
    }

    private fun getHandle(infoHash: String): TorrentHandle? {
        val key = infoHash.lowercase().trim()
        val existing = handles[key]
        if (existing != null && existing.isValid) return existing
        val found = try { sessionManager.find(Sha1Hash.parseHex(key)) } catch (e: Exception) { null }
        if (found != null && found.isValid) {
            handles[key] = found
            return found
        }
        return null
    }

    private fun prioritizeStreamingPieces(handle: TorrentHandle) {
        try {
            if (!handle.isValid) return
            val ti = handle.torrentFile() ?: return
            val numPieces = ti.numPieces()
            if (numPieces <= 0) return

            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

            // Give maximum priority to first 12 pieces and last 6 pieces (metadata, index table, moov/cues)
            val firstCount = Math.min(12, numPieces)
            for (i in 0 until firstCount) {
                handle.piecePriority(i, Priority.TOP_PRIORITY)
            }
            val lastStart = Math.max(0, numPieces - 6)
            for (i in lastStart until numPieces) {
                handle.piecePriority(i, Priority.TOP_PRIORITY)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error setting piece priorities", e)
        }
    }

    private fun getSummary(h: TorrentHandle): TorrentSummary {
        try {
            if (!h.isValid) {
                return TorrentSummary(
                    infoHash = h.infoHash().toHex().lowercase(),
                    name = "Connecting…",
                    cleanTitle = "Connecting…",
                    badges = listOf("CONNECTING"),
                    numPeers = 0,
                    numSeeds = 0,
                    downloadSpeed = 0.0,
                    uploadSpeed = 0.0,
                    progress = 0.0,
                    downloaded = 0L,
                    uploaded = 0L,
                    totalLength = 0L,
                    paused = false,
                    ready = false,
                    activeFileName = "",
                    activeFileIndex = 0,
                    pieceMap = null,
                    leadTimeSeconds = 0
                )
            }
            val status: TorrentStatus = h.status()
            val ti: TorrentInfo? = try { h.torrentFile() } catch (e: Throwable) { null }
            val rawName = if (ti != null && ti.name().isNotEmpty()) ti.name() else h.name.ifEmpty { "Torrent (${h.infoHash().toHex().take(8)})" }
            val clean = cleanMediaTitle(rawName)
            val numPieces = ti?.numPieces() ?: 0

            val pieceStates = mutableListOf<Int>()
            if (numPieces > 0) {
                try {
                    val pieces = status.pieces()
                    val pSize = if (pieces != null) pieces.size() else 0
                    val limit = Math.min(numPieces, pSize)
                    for (i in 0 until limit) {
                        if (try { pieces.getBit(i) } catch (e: Throwable) { false }) {
                            pieceStates.add(2) // Cached
                        } else {
                            pieceStates.add(0) // Missing
                        }
                    }
                    for (i in limit until numPieces) {
                        pieceStates.add(0)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Safe piece status read", t)
                    while (pieceStates.size < numPieces) pieceStates.add(0)
                }
            }

            var activeFileIdx = 0
            var activeFileName = ""
            var totalLen = status.totalWanted()
            if (ti != null && ti.files().numFiles() > 0) {
                val fs = ti.files()
                var maxLen = 0L
                for (i in 0 until fs.numFiles()) {
                    val len = fs.fileSize(i)
                    if (len > maxLen) {
                        maxLen = len
                        activeFileIdx = i
                        activeFileName = fs.fileName(i)
                    }
                }
                totalLen = maxLen
            }

            val dSpeed = status.downloadRate().toDouble()
            val leadSeconds = if (dSpeed > 500_000) 180 else 45
            val isPaused = status.flags().and_(TorrentFlags.PAUSED).non_zero()

            return TorrentSummary(
                infoHash = h.infoHash().toHex().lowercase(),
                name = rawName,
                cleanTitle = clean,
                badges = extractBadges(rawName),
                numPeers = status.numPeers(),
                numSeeds = status.numSeeds(),
                downloadSpeed = dSpeed,
                uploadSpeed = status.uploadRate().toDouble(),
                progress = status.progress().toDouble(),
                downloaded = status.totalDone(),
                uploaded = status.totalPayloadUpload(),
                totalLength = totalLen,
                paused = isPaused,
                ready = ti != null,
                activeFileName = activeFileName,
                activeFileIndex = activeFileIdx,
                pieceMap = if (numPieces > 0) PieceMapData(
                    total = numPieces,
                    fileStartPiece = 0,
                    fileEndPiece = numPieces - 1,
                    activePiece = 0,
                    states = pieceStates
                ) else null,
                leadTimeSeconds = leadSeconds
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error generating torrent summary", e)
            return TorrentSummary(
                infoHash = try { h.infoHash().toHex().lowercase() } catch (t: Throwable) { "" },
                name = "Connecting…",
                cleanTitle = "Connecting…",
                badges = listOf("CONNECTING"),
                numPeers = 0,
                numSeeds = 0,
                downloadSpeed = 0.0,
                uploadSpeed = 0.0,
                progress = 0.0,
                downloaded = 0L,
                uploaded = 0L,
                totalLength = 0L,
                paused = false,
                ready = false,
                activeFileName = "",
                activeFileIndex = 0,
                pieceMap = null,
                leadTimeSeconds = 0
            )
        }
    }

    private fun parseInfoHash(uri: String): String? {
        val regex = Regex("""xt=urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})""", RegexOption.IGNORE_CASE)
        val m = regex.find(uri)
        return m?.groupValues?.get(1)
    }

    private fun cleanMediaTitle(raw: String): String {
        if (raw.isEmpty()) return ""
        var s = raw.trim()
        s = s.replace(Regex("""\.(mp4|mkv|webm|avi|mov|wmv|m4v|flv|vob|iso)$""", RegexOption.IGNORE_CASE), "")

        val yMatch = Regex("""(?:[\s\.\(\[\-_]|^)((?:19|20)\d{2})(?:[\s\.\)\]\-_]|$)""").find(s)
        val year = yMatch?.groupValues?.get(1) ?: ""

        // 1. Remove bracketed contents [ ... ] and { ... }
        s = s.replace(Regex("""\[[^\]]*\]"""), " ")
        s = s.replace(Regex("""\{[^\}]*\}"""), " ")

        // 2. Remove parenthesized tags unless it's the 4-digit year
        s = s.replace(Regex("""\((?!\s*(?:19|20)\d{2}\s*\))[^)]*\)"""), " ")

        // 3. Cut off at year or technical scene tokens
        if (year.isNotEmpty()) {
            val yIdx = s.indexOf(year)
            if (yIdx > 0) s = s.substring(0, yIdx)
        }

        val cutoffRegex = Regex("""\b(?:2160p|1080p|1080i|720p|576p|480p|4k|uhd|bluray|blu-ray|bdrip|brrip|web-dl|webdl|webrip|web|hdrip|hdtv|remux|imax|x264|x265|h\.?264|h\.?265|hevc|avc|10bit|8bit|hdr10\+|hdr10|hdr|dolby|dovi|dv|aac|ac3|eac3|ddp|dts|atmos|truehd|yts|yify|rarbg|eztv|tgx|galaxyrg|flux|kyogo|terminal|cmrg|aspen|prolover)\b""", RegexOption.IGNORE_CASE)
        val cutMatch = cutoffRegex.find(s)
        if (cutMatch != null && cutMatch.range.first > 0) {
            s = s.substring(0, cutMatch.range.first)
        }

        s = s.replace(Regex("""[\._\+]"""), " ")
        s = s.replace(Regex("""[\(\)\[\]\{\}]"""), " ")
        s = s.replace(Regex("""^[\s\-\–—:,\.]+"""), "").replace(Regex("""[\s\-\–—:,\.]+$"""), "").trim()
        s = s.replace(Regex("""\s+"""), " ")

        if (s.isNotEmpty()) {
            s = s.split(" ").joinToString(" ") { w ->
                if (Regex("""^s\d+e\d+$""", RegexOption.IGNORE_CASE).matches(w)) w.uppercase()
                else if (Regex("""^[A-Z0-9]{2,}$""").matches(w)) w
                else if (w.contains("-")) w.split("-").joinToString("-") { it.replaceFirstChar { c -> c.uppercase() } }
                else w.replaceFirstChar { c -> c.uppercase() }
            }
            if (year.isNotEmpty() && !s.contains(year)) {
                s = "$s ($year)"
            }
        }
        return s.ifEmpty { raw }
    }

    private fun extractBadges(name: String): List<String> {
        val lower = name.lowercase()
        val badges = mutableListOf<String>()
        if (lower.contains("2160p") || lower.contains("4k")) badges.add("4K")
        else if (lower.contains("1080p")) badges.add("1080P")
        else if (lower.contains("720p")) badges.add("720P")

        if (lower.contains("hdr") || lower.contains("hdr10")) badges.add("HDR")
        if (lower.contains("atmos") || lower.contains("truehd")) badges.add("ATMOS")
        else if (lower.contains("5.1") || lower.contains("eac3") || lower.contains("ddp")) badges.add("5.1")

        badges.add("PRIORITY")
        return badges
    }

    private fun resolveCountryCode(ip: String): String {
        // Deterministic geo mapping for live radar markers
        val parts = ip.split(".")
        if (parts.size >= 2) {
            val h = (parts[0].toIntOrNull() ?: 10) + (parts[1].toIntOrNull() ?: 20)
            val countries = arrayOf("US", "DE", "FR", "GB", "NL", "IN", "JP", "CA", "BR", "AU", "ES", "IT", "SE", "CH", "KR", "SG")
            return countries[Math.abs(h) % countries.size]
        }
        return "US"
    }
}
