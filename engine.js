import WebTorrent from 'webtorrent';
import path from 'path';
import fs from 'fs';
import { EventEmitter } from 'events';
import geoip from 'geoip-country';
import { PieceScheduler } from './scheduler.js';

function getFlagEmoji(countryCode) {
  if (!countryCode || countryCode.length !== 2) return '🌐';
  const codePoints = countryCode
    .toUpperCase()
    .split('')
    .map(char => 127397 + char.charCodeAt(0));
  return String.fromCodePoint(...codePoints);
}

// Default high-performance open trackers for maximal swarm discovery
export const DEFAULT_TRACKERS = [
  'udp://tracker.opentrackr.org:1337/announce',
  'udp://open.tracker.cl:1337/announce',
  'udp://open.demonii.com:1337/announce',
  'udp://tracker.openbittorrent.com:6969/announce',
  'udp://tracker.coppersurfer.tk:6969/announce',
  'udp://tracker.leechers-paradise.org:6969/announce',
  'udp://9.rarbg.to:2920/announce',
  'udp://tracker.torrent.eu.org:451/announce',
  'wss://tracker.openwebtorrent.com',
  'wss://tracker.btorrent.xyz',
  'wss://tracker.fastcast.nz'
];

export const VIDEO_EXTENSIONS = ['.mp4', '.mkv', '.webm', '.avi', '.mov', '.m4v', '.ts'];

export class StreamEntityEngine extends EventEmitter {
  constructor(options = {}) {
    super();
    this.cacheDir = options.cacheDir || path.join(process.cwd(), 'cache');
    if (!fs.existsSync(this.cacheDir)) {
      fs.mkdirSync(this.cacheDir, { recursive: true });
    }

    this.client = new WebTorrent({
      maxConns: 80,
      dht: true,
      tracker: true
    });

    this.torrents = new Map();
    this.schedulers = new Map();
    this.primaryFiles = new Map();

    this.client.on('error', (err) => {
      console.error('[StreamEntity Engine Error]:', err.message);
      this.emit('error', err);
    });
  }

  /**
   * Add a magnet link, infohash, or torrent file buffer
   */
  async addTorrent(torrentId, options = {}) {
    let normalizedId = typeof torrentId === 'string' ? torrentId.trim() : torrentId;
    if (typeof normalizedId === 'string' && /^[0-9a-fA-F]{40}$/.test(normalizedId)) {
      normalizedId = `magnet:?xt=urn:btih:${normalizedId}`;
    }

    // Check if already present in our active torrents map
    const existing = await this.client.get(normalizedId);
    if (existing) {
      const lowHash = String(existing.infoHash || '').toLowerCase().trim();
      if (!this.torrents.has(lowHash)) {
        this.torrents.set(lowHash, existing);
        this.setupTorrent(existing, options.fileIndex);
      }
      return this.formatTorrentSummary(existing);
    }

    const addOpts = {
      path: this.cacheDir,
      announce: DEFAULT_TRACKERS,
      ...options
    };

    return new Promise((resolve, reject) => {
      try {
        const torrent = this.client.add(normalizedId, addOpts);

        const onRegistered = () => {
          if (torrent.infoHash) {
            const lowHash = String(torrent.infoHash).toLowerCase().trim();
            this.torrents.set(lowHash, torrent);
          }
          this.setupTorrent(torrent, options.fileIndex);
          resolve(this.formatTorrentSummary(torrent));
        };

        if (torrent.infoHash) {
          onRegistered();
        } else {
          torrent.once('infoHash', onRegistered);
        }

        torrent.once('error', (err) => {
          console.error('[Torrent Error]:', err.message);
          reject(err);
        });
      } catch (err) {
        reject(err);
      }
    });
  }

  /**
   * Configure primary file selection and sequential piece scheduling
   */
  setupTorrent(torrent, preferredFileIndex) {
    // When metadata is ready, find the main media file
    const onMetadataReady = () => {
      let selectedFile = null;
      let selectedIndex = 0;

      if (preferredFileIndex !== undefined && torrent.files[preferredFileIndex]) {
        selectedIndex = preferredFileIndex;
        selectedFile = torrent.files[selectedIndex];
      } else {
        // Automatically select the largest video file
        let maxLen = -1;
        torrent.files.forEach((file, idx) => {
          const ext = path.extname(file.name).toLowerCase();
          const isVideo = VIDEO_EXTENSIONS.includes(ext);
          if (isVideo && file.length > maxLen) {
            maxLen = file.length;
            selectedFile = file;
            selectedIndex = idx;
          }
        });

        // Fallback to absolute largest file if no video extension matched
        if (!selectedFile) {
          torrent.files.forEach((file, idx) => {
            if (file.length > maxLen) {
              maxLen = file.length;
              selectedFile = file;
              selectedIndex = idx;
            }
          });
        }
      }

      if (selectedFile) {
        // Deselect other files to prevent bandwidth starvation
        torrent.files.forEach((f, idx) => {
          if (idx !== selectedIndex) {
            f.deselect();
          }
        });

        // Explicitly select the chosen media file so WebTorrent actively requests pieces
        selectedFile.select();

        const lowHash = String(torrent.infoHash || '').toLowerCase().trim();
        this.primaryFiles.set(lowHash, { file: selectedFile, index: selectedIndex });

        // Initialize Tiramisu-style Piece Scheduler
        const scheduler = new PieceScheduler(torrent, selectedFile);
        this.schedulers.set(lowHash, scheduler);
        console.log(`[StreamEntity]: Initialized Sequential Scheduler for "${selectedFile.name}" (${(selectedFile.length / 1024 / 1024).toFixed(1)} MB)`);
        this.emit('metadata', { torrentHash: lowHash, torrent });
      }
    };

    if (torrent.files && torrent.files.length > 0) {
      onMetadataReady();
    } else {
      torrent.on('metadata', onMetadataReady);
    }

    torrent.on('wire', (wire, addr) => {
      const lowHash = String(torrent.infoHash || '').toLowerCase().trim();
      this.emit('wire', { torrentHash: lowHash, wire, addr });
    });
  }

  /**
   * Get primary streaming file and its piece scheduler
   */
  getStreamingContext(infoHash, fileIndex) {
    const key = String(infoHash || '').toLowerCase().trim();
    const torrent = this.torrents.get(key) || (this.client ? this.client.get(key) : null);
    if (!torrent) return null;

    let primary = this.primaryFiles.get(key);
    if (!primary || (fileIndex !== undefined && primary.index !== fileIndex)) {
      if (torrent.files && torrent.files.length > 0) {
        const idx = fileIndex !== undefined ? fileIndex : 0;
        const file = torrent.files[idx] || torrent.files[0];
        if (file) {
          primary = { file, index: idx };
          this.primaryFiles.set(key, primary);
          this.schedulers.set(key, new PieceScheduler(torrent, file));
        }
      }
    }

    return {
      torrent,
      file: primary?.file,
      fileIndex: primary?.index,
      scheduler: this.schedulers.get(key)
    };
  }

  /**
   * Format summary for REST list
   */
  formatTorrentSummary(torrent) {
    const primary = this.primaryFiles.get(torrent.infoHash);
    const rawName = torrent.name || 'Resolving metadata...';
    const badges = extractBadges(rawName);
    const scheduler = this.schedulers.get(torrent.infoHash);
    const pieceMap = scheduler ? scheduler.getPieceMap() : { total: 0, pieces: [] };

    // Calculate lead time buffer seconds ahead of current playhead
    let leadTimeSeconds = 42;
    if (pieceMap.states && pieceMap.states.length > 0) {
      let activeIdx = pieceMap.activePiece || 0;
      let contiguousCached = 0;
      for (let i = activeIdx; i < pieceMap.states.length; i++) {
        if (pieceMap.states[i] === 2) contiguousCached++;
        else break;
      }
      if (contiguousCached > 0) {
        // Average ~1.5s per piece
        leadTimeSeconds = Math.max(12, Math.min(180, Math.round(contiguousCached * 1.8)));
      }
    }

    return {
      infoHash: torrent.infoHash,
      name: rawName,
      cleanTitle: cleanMediaTitle(rawName),
      year: extractYear(rawName),
      badges,
      magnetURI: torrent.magnetURI,
      numPeers: torrent.numPeers || 0,
      numSeeds: torrent.wires ? torrent.wires.filter(w => !w.peerChoking).length : 0,
      downloadSpeed: torrent.downloadSpeed || 0,
      uploadSpeed: torrent.uploadSpeed || 0,
      progress: torrent.progress || 0,
      downloaded: torrent.downloaded || 0,
      uploaded: torrent.uploaded || 0,
      totalLength: primary?.file ? primary.file.length : (torrent.length || 0),
      timeRemaining: torrent.timeRemaining || 0,
      paused: Boolean(torrent.paused || torrent.isPaused),
      ready: Boolean(torrent.files && torrent.files.length > 0),
      files: (torrent.files || []).map((f, i) => ({
        index: i,
        name: f.name,
        length: f.length,
        progress: f.progress
      })),
      activeFileIndex: primary?.index || 0,
      activeFileName: primary?.file?.name || '',
      pieceMap,
      leadTimeSeconds
    };
  }

  /**
   * Detailed Swarm Telemetry for Live 60 FPS Visualizer
   */
  getSwarmTelemetry(infoHash) {
    const key = String(infoHash || '').toLowerCase().trim();
    const ctx = this.getStreamingContext(key);
    if (!ctx || !ctx.torrent) return null;

    const { torrent, file, scheduler } = ctx;
    const rawName = torrent.name || (file ? file.name : '') || '';
    const clean = cleanMediaTitle(rawName);

    // Build peers list with client parsing, live throughput & GeoIP country resolution
    const peers = [];
    const countryStats = {};
    let seederCount = 0;
    let leecherCount = 0;

    if (torrent.wires) {
      for (const wire of torrent.wires) {
        const clientName = wire.peerExtendedHandshake?.v || wire.client || parseClientFromPeerId(wire.peerId) || 'Unknown Peer';
        const dSpeed = typeof wire.downloadSpeed === 'function' ? wire.downloadSpeed() : (wire.downloadSpeed || 0);
        const uSpeed = typeof wire.uploadSpeed === 'function' ? wire.uploadSpeed() : (wire.uploadSpeed || 0);

        const rawIp = wire.remoteAddress || '127.0.0.1';
        const cleanIp = rawIp.replace(/^::ffff:/, '');
        const geo = cleanIp && cleanIp !== '127.0.0.1' ? geoip.lookup(cleanIp) : null;
        const countryCode = geo?.country || '';
        const countryName = geo?.name || '';
        const flag = countryCode ? getFlagEmoji(countryCode) : '🌐';

        const isSeeder = !wire.peerChoking;
        if (isSeeder) seederCount++;
        else leecherCount++;

        if (countryCode) {
          countryStats[countryCode] = (countryStats[countryCode] || 0) + 1;
        }

        peers.push({
          id: wire.peerId ? wire.peerId.toString('hex').slice(0, 16) : Math.random().toString(36).slice(2, 10),
          ip: cleanIp,
          port: wire.remotePort || 0,
          country: countryCode,
          countryName: countryName,
          flag: flag,
          client: clientName,
          type: wire.type || 'tcp',
          downloadSpeed: dSpeed,
          uploadSpeed: uSpeed,
          downloaded: wire.downloaded || 0,
          uploaded: wire.uploaded || 0,
          isChoked: Boolean(wire.peerChoking),
          isSeeder: isSeeder,
          isLeecher: !isSeeder,
          isInterested: Boolean(wire.peerInterested),
          requestsCount: wire.requests ? wire.requests.length : 0
        });
      }
    }

    const pieceMap = scheduler ? scheduler.getPieceMap() : { total: 0, pieces: [] };

    return {
      infoHash: torrent.infoHash,
      name: rawName,
      cleanTitle: clean,
      numPeers: torrent.numPeers || peers.length,
      numSeeds: seederCount,
      numLeechers: leecherCount,
      countryStats,
      downloadSpeed: torrent.downloadSpeed || 0,
      uploadSpeed: torrent.uploadSpeed || 0,
      progress: torrent.progress || 0,
      downloaded: torrent.downloaded || 0,
      uploaded: torrent.uploaded || 0,
      totalLength: file ? file.length : (torrent.length || 0),
      pieceLength: torrent.pieceLength || 0,
      activeFileName: file ? file.name : '',
      activeFileSize: file ? file.length : 0,
      peers,
      pieceMap
    };
  }

  /**
   * Remove a torrent from client
   */
  async removeTorrent(infoHash, destroyFiles = false) {
    return new Promise((resolve) => {
      const key = String(infoHash || '').toLowerCase().trim();
      const torrent = this.torrents.get(key) || (this.client ? this.client.get(key) : null);
      if (!torrent) return resolve(false);

      this.torrents.delete(key);
      this.schedulers.delete(key);
      this.primaryFiles.delete(key);

      torrent.destroy({ destroyStore: destroyFiles }, () => {
        resolve(true);
      });
    });
  }

  /**
   * Pause a torrent
   */
  pauseTorrent(infoHash) {
    const key = String(infoHash || '').toLowerCase().trim();
    const torrent = this.torrents.get(key) || (this.client ? this.client.get(key) : null);
    if (!torrent) return false;
    if (typeof torrent.pause === 'function') {
      try { torrent.pause(); } catch (e) {}
    }
    torrent.isPaused = true;
    return true;
  }

  /**
   * Resume a torrent
   */
  resumeTorrent(infoHash) {
    const key = String(infoHash || '').toLowerCase().trim();
    const torrent = this.torrents.get(key) || (this.client ? this.client.get(key) : null);
    if (!torrent) return false;
    if (typeof torrent.resume === 'function') {
      try { torrent.resume(); } catch (e) {}
    }
    torrent.isPaused = false;
    return true;
  }

  /**
   * List all torrents
   */
  listTorrents() {
    return Array.from(this.torrents.values()).map(t => this.formatTorrentSummary(t));
  }
}

/**
 * Heuristic client identifier from 8-character peer ID prefix
 */
function parseClientFromPeerId(peerIdBuf) {
  if (!peerIdBuf) return 'BitTorrent Node';
  const str = peerIdBuf.toString('utf8', 0, 8);
  if (str.startsWith('-qB')) return 'qBittorrent';
  if (str.startsWith('-TR')) return 'Transmission';
  if (str.startsWith('-UT')) return 'µTorrent';
  if (str.startsWith('-LT')) return 'libtorrent';
  if (str.startsWith('-WW') || str.startsWith('-WT')) return 'WebTorrent';
  if (str.startsWith('-DE')) return 'Deluge';
  if (str.startsWith('-AZ')) return 'Azureus / Vuze';
  if (str.startsWith('-FD')) return 'Free Download Manager';
  return 'BitTorrent Node';
}

function extractBadges(name = '') {
  const badges = [];
  const lower = name.toLowerCase();
  if (lower.includes('2160p') || lower.includes('4k')) badges.push('4K');
  else if (lower.includes('1080p') || lower.includes('fhd')) badges.push('1080P');
  else if (lower.includes('720p')) badges.push('720P');

  if (lower.includes('dv') || lower.includes('dovi') || lower.includes('dolby vision')) badges.push('DV');
  if (lower.includes('hdr') || lower.includes('hdr10')) badges.push('HDR');
  if (lower.includes('truehd') || lower.includes('atmos')) badges.push('TRUEHD 7.1');
  else if (lower.includes('eac3') || lower.includes('ddp') || lower.includes('5.1')) badges.push('EAC3 5.1');

  badges.push('PRIORITY');
  return badges;
}

function cleanMediaTitle(raw = '') {
  if (!raw) return '';
  let s = String(raw).trim();

  // Strip file extension if present (.mkv, .mp4, etc.)
  s = s.replace(/\.(mp4|mkv|webm|avi|mov|wmv|m4v|flv|vob|iso)$/i, '');

  // Extract release year (1900-2099)
  let year = '';
  const yMatch = s.match(/(?:[\s\.\(\[\-_]|^)((?:19|20)\d{2})(?:[\s\.\)\]\-_]|$)/);
  if (yMatch) {
    year = yMatch[1];
  }

  // 1. Remove all bracketed contents [ ... ] and { ... } (tracker/release-group/spec tags)
  s = s.replace(/\[[^\]]*\]/g, ' ');
  s = s.replace(/\{[^\}]*\}/g, ' ');

  // 2. Remove parenthesized content unless it's just the 4-digit year: (2026)
  s = s.replace(/\((?!\s*(?:19|20)\d{2}\s*\))[^)]*\)/g, ' ');

  // 3. Cut off at year or standard technical cutoff markers
  if (year) {
    const yIdx = s.search(new RegExp(`(?:[\\s\\.\\(\\[\\-_]|^)${year}(?:[\\s\\.\\)\\]\\-_]|$)`));
    if (yIdx > 0) {
      s = s.substring(0, yIdx);
    }
  }

  const cutoffRegex = /\b(?:2160p|1080p|1080i|720p|576p|480p|4k|uhd|bluray|blu-ray|bdrip|brrip|web-dl|webdl|webrip|web|hdrip|hdtv|remux|imax|x264|x265|h\.?264|h\.?265|hevc|avc|10bit|8bit|hdr10\+|hdr10|hdr|dolby|dovi|dv|aac|ac3|eac3|ddp|dts|atmos|truehd|yts|yify|rarbg|eztv|tgx|galaxyrg|flux|kyogo|terminal|cmrg|aspen|prolover)\b/i;
  const cutMatch = s.search(cutoffRegex);
  if (cutMatch > 0) {
    s = s.substring(0, cutMatch);
  }

  // 4. Replace separators (. , _ +) with spaces
  s = s.replace(/[\._\+]/g, ' ');

  // 5. Remove any leftover empty brackets () [] {}
  s = s.replace(/[\(\)\[\]\{\}]/g, ' ');

  // 6. Clean leading/trailing punctuation and whitespace
  s = s.replace(/^[\s\-\–—:,\.]+/, '').replace(/[\s\-\–—:,\.]+$/, '').trim();
  s = s.replace(/\s+/g, ' ');

  // 7. Title-case words nicely
  if (s) {
    s = s.split(' ').map(w => {
      if (/^s\d+e\d+$/i.test(w)) return w.toUpperCase();
      if (/^[A-Z0-9]{2,}$/.test(w)) return w;
      if (w.includes('-')) {
        return w.split('-').map(part => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase()).join('-');
      }
      return w.charAt(0).toUpperCase() + w.slice(1).toLowerCase();
    }).join(' ');

    if (year && !s.includes(year)) {
      s = `${s} (${year})`;
    }
  }

  return s || raw;
}

function extractYear(name = '') {
  const match = name.match(/\b(19\d\d|20\d\d)\b/);
  return match ? match[1] : '';
}
