/**
 * StreamEntity - Native HLS Transmuxer & Session Manager for MKV Streams
 * 
 * Replaces broken pipe:0 fMP4 with seekable, multi-track HTTP Live Streaming (HLS).
 * Supports:
 * - Direct on-disk seekable file reading
 * - Fast video passthrough (-c:v copy) for H.264/HEVC
 * - Adaptive libx264 re-encoding for VP9/AV1
 * - Multi-audio rendition playlists (EXT-X-MEDIA TYPE=AUDIO)
 * - WebVTT sidecar subtitle extraction & HLS subtitle renditions
 * - PieceScheduler critical window coordination
 * - Process lifecycle management (no orphaned ffmpeg instances)
 */

import { spawn, execSync } from 'child_process';
import path from 'path';
import fs from 'fs';
import mime from 'mime-types';

export class HlsManager {
  constructor(options = {}) {
    this.hlsBaseDir = options.hlsBaseDir || path.join(process.cwd(), 'hls');
    this.sessions = new Map(); // infoHash -> HlsSession

    if (!fs.existsSync(this.hlsBaseDir)) {
      fs.mkdirSync(this.hlsBaseDir, { recursive: true });
    }

    this.ffmpegInfo = this.detectFfmpeg();
    console.log(`[FFmpeg] Status: ${this.ffmpegInfo.ffmpeg ? 'Installed' : 'NOT found'} (${this.ffmpegInfo.version || 'none'})`);
    console.log(`[FFprobe] Status: ${this.ffmpegInfo.ffprobe ? 'Installed' : 'NOT found'}`);
  }

  detectFfmpeg() {
    let ffmpeg = false;
    let ffprobe = false;
    let version = '';

    try {
      const out = execSync('ffmpeg -version', { encoding: 'utf8', timeout: 4000, stdio: ['ignore', 'pipe', 'ignore'] });
      ffmpeg = true;
      const firstLine = out.split('\n')[0] || '';
      const vMatch = firstLine.match(/version\s+([^\s]+)/i);
      version = vMatch ? vMatch[1] : firstLine.trim();
    } catch (e) {
      ffmpeg = false;
    }

    try {
      execSync('ffprobe -version', { encoding: 'utf8', timeout: 4000, stdio: ['ignore', 'pipe', 'ignore'] });
      ffprobe = true;
    } catch (e) {
      ffprobe = false;
    }

    return { ffmpeg, ffprobe, version };
  }

  async getOrCreateSession(infoHash, engine, preferredFileIndex) {
    const key = String(infoHash || '').toLowerCase().trim();
    if (this.sessions.has(key)) {
      const s = this.sessions.get(key);
      if (s.status === 'error' || (s.ffmpegProc && s.ffmpegProc.exitCode !== null && s.ffmpegProc.exitCode !== 0)) {
        console.log(`[HlsManager] Clearing failed session for ${key} to reinitialize...`);
        this.sessions.delete(key);
        s.destroy().catch(() => {});
      } else {
        s.lastAccess = Date.now();
        return s;
      }
    }

    const session = new HlsSession(key, this, engine, preferredFileIndex);
    this.sessions.set(key, session);
    // Non-blocking initialization start
    session.initialize().catch((err) => {
      console.error(`[HlsSession:${key}] Initialization error:`, err.message);
    });
    return session;
  }

  getSession(infoHash) {
    const key = String(infoHash || '').toLowerCase().trim();
    return this.sessions.get(key) || null;
  }

  async stopSession(infoHash) {
    const key = String(infoHash || '').toLowerCase().trim();
    const session = this.sessions.get(key);
    if (session) {
      this.sessions.delete(key);
      await session.destroy();
    }
  }

  async destroyAll() {
    for (const [key, session] of this.sessions.entries()) {
      try {
        await session.destroy();
      } catch (e) {}
    }
    this.sessions.clear();
  }
}

class HlsSession {
  constructor(infoHash, manager, engine, fileIndex) {
    this.infoHash = infoHash;
    this.manager = manager;
    this.engine = engine;
    this.preferredFileIndex = fileIndex;

    this.dir = path.join(manager.hlsBaseDir, infoHash);
    this.masterPath = path.join(this.dir, 'master.m3u8');
    this.status = 'initializing'; // initializing, probing, transcoding, ready, error
    this.probed = null;
    this.ffmpegProc = null;
    this.stderrLog = [];
    this.lastError = '';
    this.created = Date.now();
    this.lastAccess = Date.now();

    this.readyPromise = new Promise((resolve, reject) => {
      this.readyResolve = resolve;
      this.readyReject = reject;
    });
  }

  async initialize() {
    try {
      if (!this.manager.ffmpegInfo.ffmpeg) {
        throw new Error('ffmpeg not installed — install it from ffmpeg.org and restart');
      }

      if (fs.existsSync(this.dir)) {
        try { fs.rmSync(this.dir, { recursive: true, force: true }); } catch (e) {}
      }
      fs.mkdirSync(this.dir, { recursive: true });

      let ctx = this.engine.getStreamingContext(this.infoHash, this.preferredFileIndex);
      if (!ctx || !ctx.torrent) {
        throw new Error('Torrent not found or not active');
      }

      if (!ctx.file && ctx.torrent) {
        await new Promise((resolve) => {
          const timeout = setTimeout(resolve, 30000);
          if (typeof ctx.torrent.once === 'function') {
            ctx.torrent.once('metadata', () => {
              clearTimeout(timeout);
              resolve();
            });
          } else {
            clearTimeout(timeout);
            resolve();
          }
        });
        ctx = this.engine.getStreamingContext(this.infoHash, this.preferredFileIndex);
      }

      const file = ctx?.file;
      if (!file) {
        throw new Error('Stream file metadata is resolving. Please wait for swarm peers.');
      }

      this.file = file;
      this.torrent = ctx.torrent;
      this.scheduler = ctx.scheduler;
      this.diskPath = path.join(this.engine.cacheDir, file.path);

      if (this.scheduler) {
        this.scheduler.scheduleInitialPieces();
      }

      // Wait for head piece so ffprobe can read container headers
      const startPiece = this.scheduler ? this.scheduler.fileStartPiece : 0;
      await this.waitForPiece(startPiece, 35000);

      this.status = 'probing';
      let probeError = null;
      const probeStartTime = Date.now();
      // Retry probe up to 35s while initial torrent pieces are downloading to disk
      while (Date.now() - probeStartTime < 35000) {
        try {
          this.probed = await this.probeMedia(this.diskPath);
          probeError = null;
          break;
        } catch (err) {
          probeError = err;
          await new Promise(r => setTimeout(r, 1500));
        }
      }

      if (!this.probed) {
        throw probeError || new Error('Failed to probe media container headers');
      }

      // Extract subtitles into WebVTT sidecars
      if (this.probed.subtitles && this.probed.subtitles.length > 0) {
        this.extractSubtitles(this.probed.subtitles).catch(() => {});
      }

      this.status = 'transcoding';
      this.startFfmpeg();

      // Track ffmpeg segment progression and coordinate with PieceScheduler
      this.startSchedulerSync();

      await this.waitForFirstSegment(40000);

      this.status = 'ready';
      if (this.readyResolve) this.readyResolve(this);
    } catch (err) {
      this.status = 'error';
      this.lastError = err.message;
      if (this.readyReject) this.readyReject(err);
      throw err;
    }
  }

  async waitForPiece(pieceIndex, timeoutMs = 35000) {
    if (!this.torrent || !this.torrent.pieces) return;
    if (this.scheduler && this.scheduler.isPieceDone(pieceIndex)) return;

    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        resolve();
      }, timeoutMs);

      const onPiece = (idx) => {
        if (idx === pieceIndex) {
          clearTimeout(timer);
          this.torrent.removeListener('piece', onPiece);
          resolve();
        }
      };

      this.torrent.on('piece', onPiece);
    });
  }

  async probeMedia(filePath) {
    return new Promise((resolve, reject) => {
      const proc = spawn('ffprobe', [
        '-v', 'error',
        '-show_entries', 'stream=index,codec_name,codec_type,width,height:stream_tags=language,title:format=duration',
        '-of', 'json',
        filePath
      ]);

      let stdout = '';
      let stderr = '';

      proc.stdout.on('data', d => stdout += d);
      proc.stderr.on('data', d => stderr += d);

      proc.on('close', (code) => {
        if (code !== 0) {
          return reject(new Error(`ffprobe failed (code ${code}): ${stderr.slice(-300) || 'Cannot probe media headers'}`));
        }

        try {
          const data = JSON.parse(stdout);
          const streams = data.streams || [];
          const format = data.format || {};

          let video = null;
          const audio = [];
          const subtitles = [];

          streams.forEach(s => {
            const tags = s.tags || {};
            const lang = (tags.language || tags.lang || 'und').toLowerCase();
            const title = tags.title || '';

            if (s.codec_type === 'video' && !video) {
              video = {
                index: s.index,
                codec: (s.codec_name || '').toLowerCase(),
                width: s.width,
                height: s.height
              };
            } else if (s.codec_type === 'audio') {
              audio.push({
                index: s.index,
                codec: (s.codec_name || '').toLowerCase(),
                language: lang,
                title: title || this.formatAudioTitle(lang, audio.length)
              });
            } else if (s.codec_type === 'subtitle') {
              subtitles.push({
                index: s.index,
                codec: (s.codec_name || '').toLowerCase(),
                language: lang,
                title: title || this.formatSubtitleTitle(lang, subtitles.length)
              });
            }
          });

          const duration = parseFloat(format.duration) || 0;
          resolve({ video, audio, subtitles, duration });
        } catch (e) {
          reject(new Error(`Failed to parse ffprobe json: ${e.message}`));
        }
      });
    });
  }

  formatAudioTitle(lang, idx) {
    const names = {
      hin: 'Hindi', eng: 'English', ita: 'Italian', spa: 'Spanish', fre: 'French',
      fra: 'French', ger: 'German', deu: 'German', jpn: 'Japanese', kor: 'Korean',
      chi: 'Chinese', zho: 'Chinese', rus: 'Russian', por: 'Portuguese', tam: 'Tamil', tel: 'Telugu'
    };
    return names[lang] || `Audio_${idx + 1}`;
  }

  formatSubtitleTitle(lang, idx) {
    const names = {
      hin: 'Hindi', eng: 'English', ita: 'Italian', spa: 'Spanish', fre: 'French',
      fra: 'French', ger: 'German', deu: 'German', jpn: 'Japanese', kor: 'Korean',
      chi: 'Chinese', zho: 'Chinese', rus: 'Russian', por: 'Portuguese', tam: 'Tamil', tel: 'Telugu'
    };
    return names[lang] || `Subtitles ${idx + 1}`;
  }

  async extractSubtitles(subs) {
    const textSubCodecs = ['subrip', 'ass', 'ssa', 'webvtt', 'mov_text'];
    for (let i = 0; i < subs.length; i++) {
      const sub = subs[i];
      if (!textSubCodecs.includes((sub.codec || '').toLowerCase())) {
        continue; // Skip bitmap/PGS subtitles that fail ffmpeg webvtt conversion
      }
      const vttOut = path.join(this.dir, `sub_${i}.vtt`);
      const plOut = path.join(this.dir, `sub_${i}.m3u8`);

      try {
        const p = spawn('ffmpeg', [
          '-hide_banner', '-loglevel', 'error',
          '-y',
          '-i', this.diskPath,
          '-map', `0:${sub.index}`,
          '-c:s', 'webvtt',
          vttOut
        ]);

        p.on('close', (c) => {
          if (c === 0 && fs.existsSync(vttOut)) {
            // Write standard HLS subtitle playlist
            const dur = Math.ceil(this.probed?.duration || 7200);
            const plContent = `#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:${dur}\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:${dur}.000,\nsub_${i}.vtt\n#EXT-X-ENDLIST\n`;
            fs.writeFileSync(plOut, plContent, 'utf8');
          }
        });
      } catch (e) {}
    }
  }

  startFfmpeg() {
    const v = this.probed.video || { codec: 'h264' };
    const isCopyable = v.codec === 'h264' || v.codec === 'hevc' || v.codec === 'h265';
    const isHevc = v.codec === 'hevc' || v.codec === 'h265';

    const args = [
      '-hide_banner',
      '-loglevel', 'warning',
      '-y',
      '-i', this.diskPath,
      '-map', `0:${v.index !== undefined ? v.index : 'v:0'}`
    ];

    if (isCopyable) {
      args.push('-c:v', 'copy');
      if (isHevc) {
        args.push('-tag:v', 'hvc1');
      }
    } else {
      args.push('-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23', '-pix_fmt', 'yuv420p');
    }

    const audioList = (this.probed.audio && this.probed.audio.length > 0)
      ? this.probed.audio
      : [{ index: 'a:0', language: 'und', title: 'Default' }];

    for (const a of audioList) {
      args.push('-map', `0:${a.index}`);
    }

    args.push('-c:a', 'aac', '-b:a', '192k', '-ac', '2');

    args.push(
      '-hls_time', '6',
      '-hls_playlist_type', 'event',
      '-hls_segment_type', 'fmp4',
      '-hls_fmp4_init_filename', 'init_%v.mp4',
      '-hls_flags', 'independent_segments',
      '-master_pl_name', 'master.m3u8'
    );

    let varMap = 'v:0,agroup:aud,default:yes';
    audioList.forEach((a, idx) => {
      const isDef = idx === 0 ? 'default:yes' : 'default:no';
      const cleanLang = (a.language || 'und').replace(/[^a-z0-9]/gi, '');
      const cleanName = (a.title || `Audio_${idx + 1}`).replace(/\s+/g, '_').replace(/[^a-z0-9_]/gi, '');
      varMap += ` a:${idx},agroup:aud,language:${cleanLang},name:${cleanName},${isDef}`;
    });

    args.push('-var_stream_map', varMap);
    args.push(path.join(this.dir, 'stream_%v.m3u8'));

    const proc = spawn('ffmpeg', args, { cwd: this.dir });
    this.ffmpegProc = proc;

    proc.stderr.on('data', (d) => {
      const lines = d.toString('utf8').split('\n').filter(Boolean);
      for (const line of lines) {
        this.stderrLog.push(line);
        if (this.stderrLog.length > 25) this.stderrLog.shift();
      }
    });

    proc.on('error', (err) => {
      this.lastError = `ffmpeg process error: ${err.message}`;
      this.status = 'error';
    });

    proc.on('close', (code) => {
      if (code !== 0 && code !== null) {
        const lastStderr = this.stderrLog.slice(-5).join(' ');
        this.lastError = `ffmpeg exited with code ${code}: ${lastStderr}`;
      }
    });
  }

  async waitForFirstSegment(timeoutMs = 40000) {
    const start = Date.now();
    return new Promise((resolve, reject) => {
      const check = () => {
        if (Date.now() - start > timeoutMs) {
          return reject(new Error(`Timeout waiting for HLS segment. ${this.lastError || this.stderrLog.slice(-3).join(' ')}`));
        }

        if (fs.existsSync(this.masterPath)) {
          try {
            const files = fs.readdirSync(this.dir);
            const hasInit = files.some(f => f.startsWith('init_') && f.endsWith('.mp4'));
            const hasM4s = files.some(f => f.endsWith('.m4s'));
            const hasTs = files.some(f => f.endsWith('.ts'));
            const hasSubM3u8 = files.some(f => f.startsWith('stream_') && f.endsWith('.m3u8'));
            if ((hasInit && hasM4s) || hasTs || hasSubM3u8) {
              return resolve();
            }
          } catch (e) {}
        }

        setTimeout(check, 350);
      };
      check();
    });
  }

  getMasterPlaylistContent() {
    if (!fs.existsSync(this.masterPath)) return null;
    let content = fs.readFileSync(this.masterPath, 'utf8');

    // Inject subtitle renditions into master.m3u8 if subtitles were probed
    if (this.probed && this.probed.subtitles && this.probed.subtitles.length > 0 && !content.includes('TYPE=SUBTITLES')) {
      const lines = content.split('\n');
      const subLines = [];
      this.probed.subtitles.forEach((s, i) => {
        const lang = (s.language || 'und').replace(/[^a-z0-9]/gi, '');
        const name = (s.title || `Subtitles ${i + 1}`).replace(/[",:]/g, '');
        subLines.push(`#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="${name}",DEFAULT=NO,AUTOSELECT=YES,LANGUAGE="${lang}",URI="sub_${i}.m3u8"`);
      });

      // Insert subtitle definitions right after audio definitions
      let insertIdx = 1;
      for (let i = 0; i < lines.length; i++) {
        if (lines[i].startsWith('#EXT-X-MEDIA:TYPE=AUDIO')) {
          insertIdx = i + 1;
        }
      }
      lines.splice(insertIdx, 0, ...subLines);

      // Append SUBTITLES="subs" to #EXT-X-STREAM-INF
      for (let i = 0; i < lines.length; i++) {
        if (lines[i].startsWith('#EXT-X-STREAM-INF:') && !lines[i].includes('SUBTITLES=')) {
          lines[i] += ',SUBTITLES="subs"';
        }
      }
      content = lines.join('\n');
    }

    return content;
  }

  startSchedulerSync() {
    if (this.schedulerTimer) clearInterval(this.schedulerTimer);
    this.schedulerTimer = setInterval(() => {
      if (!this.scheduler || !this.probed?.duration || !this.file?.length) return;
      try {
        if (!fs.existsSync(this.dir)) return;
        const files = fs.readdirSync(this.dir);
        let maxSeg = -1;
        for (const f of files) {
          const m = f.match(/stream_0.*?(\d+)\.(m4s|ts|mp4)$/);
          if (m) {
            const segIdx = parseInt(m[1], 10);
            if (segIdx > maxSeg) maxSeg = segIdx;
          }
        }
        if (maxSeg >= 0) {
          const segTime = (maxSeg + 1) * 6; // 6s per segment
          const ratio = Math.min(1, segTime / this.probed.duration);
          const approxByte = Math.floor(ratio * this.file.length);
          this.scheduler.updatePlayhead(approxByte);
        }
      } catch (e) {}
    }, 2000);
  }

  async destroy() {
    if (this.schedulerTimer) {
      clearInterval(this.schedulerTimer);
      this.schedulerTimer = null;
    }

    if (this.ffmpegProc) {
      try {
        this.ffmpegProc.kill('SIGKILL');
      } catch (e) {}
      this.ffmpegProc = null;
    }

    if (fs.existsSync(this.dir)) {
      try {
        fs.rmSync(this.dir, { recursive: true, force: true });
      } catch (e) {}
    }
  }
}
