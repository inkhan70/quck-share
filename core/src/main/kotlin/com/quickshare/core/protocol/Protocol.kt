package com.quickshare.core.protocol

/** Wire-level constants shared by both endpoints and by the discovery layer. */
object Protocol {

    /** Bumped whenever the frame format or the message set changes incompatibly. */
    const val VERSION = 1

    /** Magic string used by the UDP discovery beacon so foreign broadcast traffic is ignored. */
    const val DISCOVERY_MAGIC = "QSH-DISCOVER/1"

    /** Magic string sent first on every control connection, before any frame. */
    const val HANDSHAKE_MAGIC = "QSH1"

    /** UDP port used for discovery beacons. */
    const val DISCOVERY_PORT = 8989

    /** Default TCP port for the control channel; the actual port is always announced in HELLO. */
    const val DEFAULT_CONTROL_PORT = 8988

    /** First port tried for the data channel when a fixed one is needed. */
    const val DATA_PORT_BASE = 8990

    /**
     * Largest control frame accepted, in bytes. Control frames only ever carry JSON metadata, so
     * a generous-but-finite cap stops a hostile peer from forcing a huge allocation. The cap is
     * what makes the length prefix in [FrameCodec] safe to trust.
     */
    const val MAX_FRAME_BYTES = 256 * 1024

    /** Maximum number of files in a single offer. Bounds the metadata allocation. */
    const val MAX_FILES_PER_TRANSFER = 5000

    /** Longest accepted filename, after sanitization. */
    const val MAX_FILE_NAME_LENGTH = 255

    /** Longest accepted display name for a device. */
    const val MAX_DEVICE_NAME_LENGTH = 64

    /** Longest accepted free-text message attached to an offer. */
    const val MAX_MESSAGE_LENGTH = 512

    /** Buffer size for both file streaming and socket copies. */
    const val IO_BUFFER_SIZE = 64 * 1024

    /** How long a socket read may block before the connection is considered dead. */
    const val SOCKET_READ_TIMEOUT_MS = 30_000

    /** How long a peer stays listed after its last discovery beacon. */
    const val PEER_TTL_MS = 12_000L

    /** Interval between discovery beacons. */
    const val DISCOVERY_INTERVAL_MS = 3_000L

    /** Digits in a pairing code. */
    const val PAIRING_CODE_LENGTH = 6

    /** Port range scanned by the manual-IP fallback. */
    val MANUAL_PORT_CANDIDATES = intArrayOf(DEFAULT_CONTROL_PORT, 9000, 9100)
}
