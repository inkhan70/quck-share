package com.quickshare.app.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.quickshare.app.BuildConfig
import com.quickshare.app.data.AndroidSpaceProvider
import com.quickshare.app.data.PrefsHistoryStore
import com.quickshare.app.data.PrefsSettingsStore
import com.quickshare.app.data.SafDocumentRegistry
import com.quickshare.core.data.InMemoryHistoryStore
import com.quickshare.core.data.InMemorySettingsStore
import com.quickshare.core.data.Settings
import com.quickshare.core.data.SettingsRepository
import com.quickshare.core.data.TransferHistoryRepository
import com.quickshare.core.net.DeviceIdentity
import com.quickshare.core.net.DiscoveryService
import com.quickshare.core.net.NetworkUtils
import com.quickshare.core.net.PeerRegistry
import com.quickshare.core.transfer.TransferClient
import com.quickshare.core.transfer.TransferCoordinator
import com.quickshare.core.util.Logs
import java.util.UUID

/**
 * Process-wide singletons.
 *
 * A deliberate manual service locator rather than a DI framework: the graph is small (a dozen
 * objects), everything is constructed once at startup, and a manual locator keeps the APK free of
 * reflection-based injection, which matters because reflection is also what R8 has to keep alive.
 */
object ServiceLocator {

    private const val TAG = "ServiceLocator"
    private const val PREFS_SETTINGS = "quickshare_settings"
    private const val PREFS_HISTORY = "quickshare_history"

    /**
     * The device identity lives in its own file, deliberately NOT in [PREFS_SETTINGS].
     *
     * The settings file is included in Android backups. An id stored there was restored onto a new
     * phone, so two devices ended up with the same id and ignored each other in discovery (a device
     * skips beacons that carry its own id). The identity file is not in the backup rules, so every
     * installation gets its own id. It also keeps the settings file empty on first launch, which the
     * settings store relies on to tell "fresh install" from "has saved settings".
     */
    private const val PREFS_IDENTITY = "quickshare_identity"
    private const val KEY_DEVICE_ID = "generated_device_id"

    @Volatile
    private var initialised = false

    lateinit var appContext: Context
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var historyRepository: TransferHistoryRepository
        private set

    lateinit var peerRegistry: PeerRegistry
        private set

    lateinit var documentRegistry: SafDocumentRegistry
        private set

    lateinit var spaceProvider: AndroidSpaceProvider
        private set

    @Volatile
    private var discoveryService: DiscoveryService? = null

    @Volatile
    private var coordinator: TransferCoordinator? = null

    @Volatile
    private var transferServer: com.quickshare.core.transfer.TransferServer? = null

    /**
     * Initialises the graph. Safe to call repeatedly; the work happens once.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        appContext = context.applicationContext

        // This runs inside Application.onCreate, so an exception here is an immediate crash on
        // launch - before the user can reach any screen. Each construction is therefore isolated and
        // falls back to a working (if non-persistent) implementation, so a broken preference file or
        // an unavailable platform service degrades the app instead of bricking it.
        try {
            settingsRepository = SettingsRepository(
                store = PrefsSettingsStore(appContext, PREFS_SETTINGS),
                onCorrupt = { message -> Logs.w(TAG, "Settings issue: $message") },
            )
            historyRepository = TransferHistoryRepository(
                store = PrefsHistoryStore(appContext, PREFS_HISTORY),
            )
            peerRegistry = PeerRegistry()
            documentRegistry = SafDocumentRegistry(appContext)
            spaceProvider = AndroidSpaceProvider(appContext)
        } catch (e: Exception) {
            Logs.e(TAG, "Service graph construction failed; using safe fallbacks", e)
            if (!::settingsRepository.isInitialized) {
                settingsRepository = SettingsRepository(store = InMemorySettingsStore())
            }
            if (!::historyRepository.isInitialized) {
                historyRepository = TransferHistoryRepository(store = InMemoryHistoryStore())
            }
            if (!::peerRegistry.isInitialized) peerRegistry = PeerRegistry()
            if (!::documentRegistry.isInitialized) documentRegistry = SafDocumentRegistry(appContext)
            if (!::spaceProvider.isInitialized) spaceProvider = AndroidSpaceProvider(appContext)
        }

        // No transfer can be running at process start, so any row still marked in-progress was cut
        // short by the app being killed. Fix it now or it stays "in progress" forever.
        runCatching { historyRepository.markStaleInterrupted() }
            .onFailure { Logs.w(TAG, "Could not repair stale history rows", it) }

        initialised = true
        Logs.i(TAG, "Initialised for device ${deviceId()}")
    }

    /** True once [init] has completed. */
    fun isInitialised(): Boolean = initialised

    /**
     * A stable, private device identifier.
     *
     * Generated once and stored. If the platform value cannot be read at all, the in-memory value
     * still works for this session (discovery will find peers; only restart stability is lost), which
     * is preferable to crashing on a device with an odd broken settings provider.
     */
    fun deviceId(): String {
        // Guarded because this is reachable from a field initialiser during activity creation: if the
        // graph was never initialised, a session-only id is far better than an
        // UninitializedPropertyAccessException on the launch path.
        if (!initialised) return sessionDeviceId()

        val prefs = appContext.getSharedPreferences(PREFS_IDENTITY, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        val entropy = buildString {
            append(Build.MODEL).append('|')
            append(Build.MANUFACTURER).append('|')
            append(runCatching { AndroidSettings.Secure.getString(appContext.contentResolver, AndroidSettings.Secure.ANDROID_ID) }.getOrNull())
            append('|')
            append(UUID.randomUUID())
        }
        val id = "qs-" + com.quickshare.core.util.Checksums.sha256Hex(entropy.toByteArray()).take(24)
        return try {
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        } catch (e: Exception) {
            Logs.w(TAG, "Could not persist the device id", e)
            id
        }
    }

    /** Default name for a fresh install, derived from the hardware model. */
    fun defaultDeviceName(): String {
        val fallback = DeviceIdentity.defaultDeviceName(Build.MODEL)
        if (!initialised) return fallback
        // The hardware-derived name is passed as the default. Passing "" here made the repository
        // normalise a blank name to the generic "QuickShare device" and PERSIST it, so every fresh
        // install ended up with the same name instead of one based on the phone model.
        val stored = settingsRepository.load(deviceId(), fallback).deviceName
        return if (stored.isNullOrBlank()) fallback else stored
    }

    /** A per-process identifier used only when the graph could not be initialised. */
    @Volatile
    private var fallbackDeviceId: String? = null

    private fun sessionDeviceId(): String = fallbackDeviceId ?: synchronized(this) {
        fallbackDeviceId ?: ("qs-session-" + UUID.randomUUID().toString().take(12)).also { fallbackDeviceId = it }
    }

    fun settings(): Settings = settingsRepository.load(deviceId(), defaultDeviceName())

    fun saveSettings(settings: Settings) = settingsRepository.save(settings)

    fun coordinator(): TransferCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: TransferCoordinator(
            client = TransferClient(
                deviceId = deviceId(),
                deviceName = defaultDeviceName(),
                localControlPort = DeviceIdentity.controlPort(),
                appVersion = BuildConfig.VERSION_NAME,
                // Read at send time, so renaming the device in Settings applies immediately.
                deviceNameProvider = { defaultDeviceName() },
            ),
            history = historyRepository,
        ).also { coordinator = it }
    }

    fun discovery(): DiscoveryService = discoveryService ?: synchronized(this) {
        discoveryService ?: DiscoveryService(
            deviceId = deviceId(),
            deviceName = { defaultDeviceName() },
            controlPort = { DeviceIdentity.controlPort() },
            appVersion = BuildConfig.VERSION_NAME,
            registry = peerRegistry,
        ).also { discoveryService = it }
    }

    /**
     * The receive-side server.
     *
     * Constructed lazily with the listener supplied by the receive service, and cached so the socket
     * is bound once rather than per start.
     */
    @Synchronized
    fun transferServer(
        sinkFactory: com.quickshare.core.transfer.SinkFactory,
        listener: com.quickshare.core.transfer.TransferListener,
    ): com.quickshare.core.transfer.TransferServer {
        transferServer?.let { return it }
        return com.quickshare.core.transfer.TransferServer(
            deviceId = deviceId(),
            deviceName = defaultDeviceName(),
            appVersion = BuildConfig.VERSION_NAME,
            sinkFactory = sinkFactory,
            spaceProvider = spaceProvider,
            listener = listener,
            deviceNameProvider = { defaultDeviceName() },
        ).also { transferServer = it }
    }

    /**
     * Drops the cached server so the next receive service builds a fresh one.
     *
     * The cached server holds the listener (and so the Service instance) it was created with. Once
     * that service is destroyed, reusing the server would keep calling into a dead service and leak
     * its Context. Called when the receive service stops.
     */
    @Synchronized
    fun discardTransferServer() {
        try {
            transferServer?.shutdown()
        } catch (e: Exception) {
            Logs.d(TAG, "Server shutdown failed: ${e.message}")
        }
        transferServer = null
    }

    fun shutdown() {
        try {
            discoveryService?.shutdown()
        } catch (e: Exception) {
            Logs.d(TAG, "Discovery shutdown failed: ${e.message}")
        }
        try {
            transferServer?.shutdown()
        } catch (e: Exception) {
            Logs.d(TAG, "Server shutdown failed: ${e.message}")
        }
        try {
            coordinator?.shutdown()
        } catch (e: Exception) {
            Logs.d(TAG, "Coordinator shutdown failed: ${e.message}")
        }
        discoveryService = null
        transferServer = null
        coordinator = null
    }

    /** Local address for the QR code, or null when the device has no usable Wi-Fi address. */
    fun localAddress(): String? = NetworkUtils.primaryLocalAddress()

    fun isOnLocalNetwork(): Boolean = NetworkUtils.activeIpv4Interfaces().isNotEmpty()

    /**
     * Acquires a high-performance multicast lock.
     *
     * Even though discovery uses broadcast, several Android Wi-Fi drivers drop broadcast frames from
     * the app's own socket unless a multicast lock is held. The lock is returned so the caller can
     * release it when discovery stops.
     */
    fun acquireWifiLock(): WifiManager.MulticastLock? = try {
        val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock("quickshare:discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (e: Exception) {
        Logs.w(TAG, "Could not acquire the multicast lock", e)
        null
    }
}
