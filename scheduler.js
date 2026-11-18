/**
 * StreamEntity - Sequential Piece Scheduler
 * Reverse-Engineered from Tiramisu's Deadline-First Streaming Engine
 * 
 * Provides:
 * 1. Head Prioritization (first 8-16 pieces for container header instant start <2s)
 * 2. Tail Prioritization (last 2-4 pieces for MP4 moov atom & MKV seek cues)
 * 3. Playhead Dynamic Sliding Window (immediate pieces around active stream cursor)
 * 4. Swarm Piece State Mapping (unrequested, in-flight, downloaded, priority)
 */

export class PieceScheduler {
  constructor(torrent, file) {
    this.torrent = torrent;
    this.file = file;

    this.pieceLength = torrent.pieceLength;
    this.totalPieces = torrent.pieces ? torrent.pieces.length : 0;
    
    // Calculate file's piece range within the torrent
    this.fileStartByte = file.offset || 0;
    this.fileEndByte = this.fileStartByte + file.length;
    
    this.fileStartPiece = Math.floor(this.fileStartByte / this.pieceLength);
    this.fileEndPiece = Math.floor((this.fileEndByte - 1) / this.pieceLength);
    this.numFilePieces = (this.fileEndPiece - this.fileStartPiece) + 1;

    // Configurable parameters (tuned for ultra-low startup latency <2s)
    this.headPieceCount = Math.min(12, Math.max(4, Math.floor(this.numFilePieces * 0.05)));
    this.tailPieceCount = Math.min(4, Math.max(2, Math.floor(this.numFilePieces * 0.02)));
    this.criticalWindow = 6;    // Deadline-first immediate pieces
    this.lookaheadWindow = 24;  // Sequential buffer ahead of playhead

    this.activeCursorByte = 0;
    this.activePiece = this.fileStartPiece;

    // Schedule initial head and tail pieces immediately
    this.scheduleInitialPieces();
  }

  /**
   * Schedule head (metadata) and tail (seek cues/atoms) pieces immediately
   */
  scheduleInitialPieces() {
    if (!this.torrent || !this.torrent.pieces) return;

    // 1. Prioritize Head Pieces (first 8-12 pieces)
    const headEnd = Math.min(this.fileEndPiece, this.fileStartPiece + this.headPieceCount);
    for (let p = this.fileStartPiece; p <= headEnd; p++) {
      if (!this.isPieceDone(p)) {
        this.torrent.critical(p, p);
      }
    }

    // 2. Prioritize Tail Pieces (last 2-4 pieces)
    const tailStart = Math.max(this.fileStartPiece, this.fileEndPiece - this.tailPieceCount + 1);
    for (let p = tailStart; p <= this.fileEndPiece; p++) {
      if (!this.isPieceDone(p)) {
        this.torrent.select(p, p, 1);
      }
    }
  }

  /**
   * Update active streaming playhead position (called on HTTP Range stream chunks)
   */
  updatePlayhead(byteOffset) {
    this.activeCursorByte = byteOffset;
    const globalOffset = this.fileStartByte + byteOffset;
    const currentPiece = Math.min(this.fileEndPiece, Math.floor(globalOffset / this.pieceLength));
    
    if (currentPiece === this.activePiece) return;
    this.activePiece = currentPiece;

    // Reprioritize sliding window around current playhead
    const critEnd = Math.min(this.fileEndPiece, currentPiece + this.criticalWindow);
    const lookaheadEnd = Math.min(this.fileEndPiece, currentPiece + this.lookaheadWindow);

    // Set immediate critical priority
    for (let p = currentPiece; p <= critEnd; p++) {
      if (!this.isPieceDone(p)) {
        this.torrent.critical(p, p);
      }
    }

    // Set sequential lookahead priority
    for (let p = critEnd + 1; p <= lookaheadEnd; p++) {
      if (!this.isPieceDone(p)) {
        this.torrent.select(p, p, 1);
      }
    }
  }

  /**
   * Check if a specific piece is verified and completed
   */
  isPieceDone(pieceIndex) {
    if (!this.torrent) return false;
    if (this.torrent.bitfield && typeof this.torrent.bitfield.get === 'function') {
      return Boolean(this.torrent.bitfield.get(pieceIndex));
    }
    const piece = this.torrent.pieces ? this.torrent.pieces[pieceIndex] : null;
    return Boolean(piece && (piece.length === 0 || piece.missing === 0));
  }

  /**
   * Get telemetry bitfield representation for the UI Piece Map
   * States:
   * 0: unrequested
   * 1: in-flight (active wires requesting chunks of this piece)
   * 2: downloaded & verified
   * 3: priority frontier (head, tail, or current playhead window)
   */
  getPieceMap() {
    if (!this.torrent || !this.torrent.pieces) {
      return { total: 0, pieces: [] };
    }

    const total = this.numFilePieces;
    const states = new Uint8Array(total);

    // Identify in-flight pieces across all active peer wires
    const inFlightSet = new Set();
    if (this.torrent.wires) {
      for (const wire of this.torrent.wires) {
        if (wire.requests) {
          for (const req of wire.requests) {
            inFlightSet.add(req.piece);
          }
        }
      }
    }

    const headEnd = this.fileStartPiece + this.headPieceCount;
    const tailStart = this.fileEndPiece - this.tailPieceCount + 1;
    const critEnd = this.activePiece + this.criticalWindow;

    for (let i = 0; i < total; i++) {
      const globalIndex = this.fileStartPiece + i;
      if (this.isPieceDone(globalIndex)) {
        states[i] = 2; // Downloaded & Cached
      } else if (inFlightSet.has(globalIndex)) {
        states[i] = 1; // In-Flight
      } else if (
        (globalIndex <= headEnd) ||
        (globalIndex >= tailStart) ||
        (globalIndex >= this.activePiece && globalIndex <= critEnd)
      ) {
        states[i] = 3; // Priority Frontier
      } else {
        states[i] = 0; // Unrequested
      }
    }

    return {
      total,
      fileStartPiece: this.fileStartPiece,
      fileEndPiece: this.fileEndPiece,
      activePiece: Math.max(0, this.activePiece - this.fileStartPiece),
      states: Array.from(states)
    };
  }
}
