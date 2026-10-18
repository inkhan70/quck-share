package com.quickshare.app.ui

import android.app.Application
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quickshare.app.BuildConfig
import com.quickshare.app.R
import com.quickshare.app.core.ServiceLocator
import com.quickshare.app.data.DocumentFileSource
import com.quickshare.app.service.ReceiveService
import com.quickshare.app.service.SendService
import com.quickshare.core.data.Settings
import com.quickshare.core.net.DeviceIdentity
import com.quickshare.core.net.Peer
import com.quickshare.core.pairing.PairingPayload
import com.quickshare.core.protocol.Protocol
import com.quickshare.core.transfer.FileSource
import com.quickshare.core.transfer.TransferProgress
import com.quickshare.core.transfer.TransferRecord
import com.quickshare.core.util.ByteFormat
import com.quickshare.core.util.Logs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The single state holder behind the whole UI.
 *
 * An [AndroidViewModel] rather than a plain one because the graph it fronts (sockets, settings, the
 * receive service) is owned by the application, and because it must survive configuration changes: a
 * rotation during a transfer must not tear down the socket, and the discovery multicast lock must not
 * be leaked by a recreated activity.
 *
 * Every piece of UI state is a [StateFlow] so the Compose tree is a pure function of it, and the
 * decision-making helpers it calls into ([SendSelection], [ManualConnect], [PeerOrdering]) are
 * Android-free so they can be unit-tested on the JVM.
 */
class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "MainViewModel"
    }

    // Runs before every property initialiser below, so the service graph is guaranteed to exist by
    // the time the fields that read it are constructed. init() is idempotent, so calling it again
    // here (it already ran in Application.onCreate) costs nothing and removes any ordering
    // assumption between the two entry points.
    init {
        ServiceLocator.init(app)
    }

    private val settingsRepo = ServiceLocator.settingsRepository
    private val historyRepo = ServiceLocator.historyRepository
    private val coordinator = ServiceLocator.coordinator()
    private val discovery = ServiceLocator.discovery()

    // ---- Settings ---------------------------------------------------------------------------

    private val _settings = MutableStateFlow(loadSettingsSafely())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    // ---- Discovery --------------------------------------------------------------------------

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _onLocalNetwork = MutableStateFlow(ServiceLocator.isOnLocalNetwork())
    val onLocalNetwork: StateFlow<Boolean> = _onLocalNetwork.asStateFlow()

    // ---- History ----------------------------------------------------------------------------

    private val _history = MutableStateFlow(coordinator.history())
    val history: StateFlow<List<TransferRecord>> = _history.asStateFlow()

    // ---- Navigation -------------------------------------------------------------------------

    private val _tab = MutableStateFlow(AppTab.NEARBY)
    val tab: StateFlow<AppTab> = _tab.asStateFlow()

    private val _sendTarget = MutableStateFlow<Peer?>(null)
    val sendTarget: StateFlow<Peer?> = _sendTarget.asStateFlow()

    private val _infoPage = MutableStateFlow<InfoPage?>(null)
    val infoPage: StateFlow<InfoPage?> = _infoPage.asStateFlow()

    // ---- Prompts (driven by the receive service) --------------------------------------------

    val prompt: StateFlow<ReceivePrompt?> = ReceivePromptController.prompt

    // ---- Transfers --------------------------------------------------------------------------

    val activeSends: StateFlow<Map<String, TransferProgress>> = coordinator.activeSends
    val activeReceives: StateFlow<Map<String, TransferProgress>> = coordinator.activeReceives

    private val _selection = MutableStateFlow(SendSelection())
    val selection: StateFlow<SendSelection> = _selection.asStateFlow()

    private val _pairingCode = MutableStateFlow("")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    /**
     * The peer and files for transfers started in this session.
     *
     * Kept so Retry can re-send without asking the user to pick everything again. Deliberately
     * in-memory only: persisting content URIs would require holding a persistable grant for every
     * picked file, which is a much broader permission than this feature is worth.
     */
    private val sendableSessions = mutableMapOf<String, Pair<Peer, List<StagedFile>>>()

    // ---- Dialogs ----------------------------------------------------------------------------

    private val _showQr = MutableStateFlow(false)
    val showQr: StateFlow<Boolean> = _showQr.asStateFlow()

    private val _qrPayload = MutableStateFlow<String?>(null)
    val qrPayload: StateFlow<String?> = _qrPayload.asStateFlow()

    private val _showManualConnect = MutableStateFlow(false)
    val showManualConnect: StateFlow<Boolean> = _showManualConnect.asStateFlow()

    private val _manualHost = MutableStateFlow("")
    val manualHost: StateFlow<String> = _manualHost.asStateFlow()

    /** User-facing error text, or null. Rendered as a snackbar and cleared by the UI. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryRunning = false

    init {
        // A finished transfer refreshes the history list and (when it went wrong) surfaces the reason.
        viewModelScope.launch {
            coordinator.completed.collect { record ->
                _history.value = coordinator.history()
                if (!record.status.isSuccess && record.errorMessage != null) {
                    _error.value = record.errorMessage
                } else if (record.status.isSuccess) {
                    // Without this a finished send just made its progress card disappear, with no
                    // sign that anything had happened.
                    _notice.value = app.getString(R.string.transfer_done)
                }
            }
        }
        // A refused send never reaches a socket, so it is reported separately from a failure.
        viewModelScope.launch {
            coordinator.rejections.collect { _error.value = it.reason }
        }
        // Starting the receiver touches the network and the foreground-service API, either of which
        // the platform can refuse. A refusal must leave the UI usable rather than crash it.
        runCatching { syncReceiver() }
            .onFailure { Logs.w(TAG, "Could not start the receiver at launch", it) }
    }

    /**
     * Reads the stored settings, falling back to defaults.
     *
     * Called from a field initialiser, i.e. while the activity is being created, so it must not be
     * able to throw: a corrupt preference file would otherwise crash the app before the first frame
     * is ever drawn.
     */
    private fun loadSettingsSafely(): Settings = runCatching {
        var loaded = ServiceLocator.settings()
        // The settings file is restored by Android backup but the folder permission is not, so a
        // restored install showed a chosen save folder it had no access to. Forget the stale choice so
        // the app asks again.
        if (loaded.saveLocation != null && ServiceLocator.documentRegistry.treeUri() == null) {
            loaded = loaded.copy(saveLocation = null, saveLocationLabel = null)
            ServiceLocator.saveSettings(loaded)
        }
        loaded
    }.getOrElse { e ->
        Logs.w(TAG, "Could not load settings; falling back to defaults", e)
        Settings.defaults("", "QuickShare device")
    }

    // =========================================================================================
    // Receiver / discovery lifecycle
    // =========================================================================================

    /**
     * Brings the discovery engine and the receive service in line with the setting.
     *
     * Called at startup and after the switch is toggled, so the two can never disagree: the app is
     * either advertising and listening, or it is not.
     */
    private fun syncReceiver() {
        val enabled = _settings.value.receiveEnabled != false
        if (enabled) {
            startDiscovery()
            ReceiveService.start(app)
        } else {
            ReceiveService.stop(app)
            stopDiscovery()
        }
        refreshPeers()
    }

    private fun startDiscovery() {
        if (discoveryRunning) return
        // Several Wi-Fi drivers drop broadcast frames from the app's own socket unless a multicast
        // lock is held, even though discovery itself uses broadcast.
        multicastLock = ServiceLocator.acquireWifiLock()
        discovery.start(
            onPeerDiscovered = { refreshPeers() },
            onPeerLost = { refreshPeers() },
        )
        discoveryRunning = true
        Logs.i(TAG, "Discovery started")
    }

    private fun stopDiscovery() {
        if (!discoveryRunning) return
        try {
            discovery.stop()
        } catch (e: Exception) {
            Logs.w(TAG, "Could not stop discovery cleanly", e)
        }
        try {
            multicastLock?.release()
        } catch (e: Exception) {
            Logs.d(TAG, "Multicast lock release failed: ${e.message}")
        }
        multicastLock = null
        discoveryRunning = false
    }

    fun refreshPeers() {
        _peers.value = PeerOrdering.sorted(discovery.peers())
        _onLocalNetwork.value = ServiceLocator.isOnLocalNetwork()
    }

    fun setReceiveEnabled(enabled: Boolean) {
        updateSettings { it.copy(receiveEnabled = enabled) }
        syncReceiver()
    }

    /**
     * Discovery is intentionally left running when the screen goes away.
     *
     * Receiving is a foreground service precisely so a transfer survives the user switching apps, and
     * a device that stops advertising would also stop being discoverable - so the lock and the beacon
     * loop stay up. They are released only when the user turns receiving off, or the process dies.
     */
    override fun onCleared() {
        super.onCleared()
        ReceivePromptController.cancelAll()
        Logs.i(TAG, "ViewModel cleared")
    }

    // =========================================================================================
    // Settings
    // =========================================================================================

    private fun updateSettings(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        settingsRepo.save(updated)
    }

    fun setTab(tab: AppTab) {
        _tab.value = tab
    }

    fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        updateSettings { it.copy(deviceName = trimmed.take(Protocol.MAX_DEVICE_NAME_LENGTH)) }
    }

    fun setTheme(option: ThemeOption) {
        updateSettings { it.copy(darkMode = option.storageValue) }
    }

    fun setRequirePairing(enabled: Boolean) = updateSettings { it.copy(requirePairingCode = enabled) }

    fun setAutoAccept(enabled: Boolean) = updateSettings { it.copy(autoAcceptFromKnownDevices = enabled) }

    fun setSaveImagesToGallery(enabled: Boolean) = updateSettings { it.copy(saveImagesToGallery = enabled) }

    fun setKeepScreenOn(enabled: Boolean) = updateSettings { it.copy(keepScreenOnDuringTransfer = enabled) }

    fun setNotifyOnTransfer(enabled: Boolean) = updateSettings { it.copy(notifyOnTransfer = enabled) }

    /** Records the folder the user granted through the Storage Access Framework. */
    fun setSaveLocation(treeUri: String, label: String) {
        updateSettings { it.copy(saveLocation = treeUri, saveLocationLabel = label) }
        Logs.i(TAG, "Save location is now '$label'")
    }

    fun resetSettings() {
        // Reset to the hardware-derived name; defaultDeviceName() returns the CURRENT stored name, so
        // using it here made "reset" keep whatever name the user had typed.
        val defaults = Settings.defaults(
            ServiceLocator.deviceId(),
            DeviceIdentity.defaultDeviceName(android.os.Build.MODEL),
        )
        // The save location is kept: it is a permission the user granted through the system picker,
        // and silently dropping it would break receiving with no explanation.
        val merged = defaults.copy(
            saveLocation = _settings.value.saveLocation,
            saveLocationLabel = _settings.value.saveLocationLabel,
            darkMode = _settings.value.darkMode,
        )
        _settings.value = merged
        settingsRepo.save(merged)
        // Receiving defaults to on again; bring discovery and the service in line with that.
        syncReceiver()
    }

    fun deviceId(): String = ServiceLocator.deviceId()

    val versionName: String get() = BuildConfig.VERSION_NAME

    // =========================================================================================
    // Manual connection & QR pairing
    // =========================================================================================

    fun openManualConnect() {
        _manualHost.value = ""
        _showManualConnect.value = true
    }

    fun closeManualConnect() {
        _showManualConnect.value = false
    }

    fun setManualHost(value: String) {
        _manualHost.value = value
    }

    /**
     * Adds the address the user typed as a peer.
     *
     * The peer gets a synthetic id because nothing has performed a handshake yet; the real id arrives
     * in the HelloAck during the transfer, which is also why this path is only reachable manually.
     */
    fun connectManually() {
        when (val result = ManualConnect.parse(_manualHost.value, DeviceIdentity.controlPort())) {
            ManualConnect.Result.Blank -> _error.value = app.getString(R.string.error_invalid_ip)
            ManualConnect.Result.NotAnAddress -> _error.value = app.getString(R.string.error_invalid_ip)
            ManualConnect.Result.NotDialable -> _error.value = app.getString(R.string.error_invalid_ip)
            is ManualConnect.Result.Ok -> {
                ServiceLocator.peerRegistry.addManual(
                    address = result.host,
                    port = result.port,
                    deviceName = result.host,
                    deviceId = "manual-${result.host}-${result.port}",
                    protocolVersion = Protocol.VERSION,
                    appVersion = "",
                    groupCode = "",
                )
                _showManualConnect.value = false
                refreshPeers()
                // This previously reported the *save location* label, which was a copy-paste bug: the
                // user had just added a device by address, not chosen a folder, so the feedback named
                // something they had not touched.
                _notice.value = app.getString(R.string.connect_added, result.host)
            }
        }
    }

    fun openQr() {
        val host = ServiceLocator.localAddress()
        if (host == null) {
            _error.value = app.getString(R.string.error_no_wifi)
            return
        }
        val code = coordinator.newPairingCode()
        _pairingCode.value = code
        val payload = QrCode.payloadFor(host, DeviceIdentity.controlPort(), code, deviceName())
        if (payload == null) {
            _error.value = app.getString(R.string.error_no_wifi)
            return
        }
        _qrPayload.value = payload
        _showQr.value = true
    }

    fun closeQr() {
        _showQr.value = false
        _qrPayload.value = null
    }

    /** Handles a scanned (or deep-linked) pairing code by adding the peer it describes. */
    fun applyPairingPayload(payload: PairingPayload) {
        val peer = ServiceLocator.peerRegistry.addManual(
            address = payload.host,
            port = payload.port,
            deviceName = payload.deviceName,
            deviceId = "qr-${payload.host}-${payload.port}",
            protocolVersion = payload.protocolVersion,
            appVersion = "",
            groupCode = "",
        )
        refreshPeers()
        // Open the send screen straight away with the scanned code. Previously the code was stored
        // and then replaced by a fresh one the moment the user tapped the device, so the QR code the
        // other phone was showing could never match what was sent.
        _selection.value = SendSelection()
        _pairingCode.value = payload.code
        _sendTarget.value = peer
    }

    /** Tells the user a scanned QR code was not a QuickShare pairing code. */
    fun showInvalidQrNotice() {
        _error.value = app.getString(R.string.error_invalid_qr)
    }

    fun openInfo(page: InfoPage) {
        _infoPage.value = page
    }

    fun closeInfo() {
        _infoPage.value = null
    }

    fun regeneratePairingCode(): String {
        val code = coordinator.newPairingCode()
        _pairingCode.value = code
        return code
    }

    fun deviceName(): String = _settings.value.deviceName ?: ServiceLocator.defaultDeviceName()

    // =========================================================================================
    // Sending
    // =========================================================================================

    /** Opens the send screen for [peer]. */
    fun selectTarget(peer: Peer) {
        _sendTarget.value = peer
        _selection.value = SendSelection()
        _pairingCode.value = coordinator.newPairingCode()
    }

    fun clearTarget() {
        _sendTarget.value = null
        _selection.value = SendSelection()
    }

    fun addPickedUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val context = app
        val staged = uris.mapNotNull { uri ->
            DocumentFileSource.from(context, uri)?.let { source ->
                StagedFile(uri = uri.toString(), name = source.name, size = source.size, mime = source.mime)
            }
        }
        if (staged.isEmpty()) {
            _error.value = app.getString(R.string.send_no_files)
            return
        }
        _selection.value = _selection.value.addAll(staged)
    }

    fun removeStaged(uri: String) {
        _selection.value = _selection.value.remove(uri)
    }

    fun clearSelection() {
        _selection.value = _selection.value.clear()
    }

    /**
     * Starts the transfer.
     *
     * The socket work runs here (process-bound, so it survives a rotation) while [SendService] holds
     * the foreground notification; the service observes the coordinator rather than being handed the
     * files, so no file handle ever crosses a Binder boundary.
     */
    fun startSend() {
        val peer = _sendTarget.value ?: return
        val selection = _selection.value
        if (!selection.canSend) {
            _error.value = app.getString(R.string.send_no_files)
            return
        }
        val sources: List<FileSource> = selection.items.mapNotNull { staged ->
            runCatching { android.net.Uri.parse(staged.uri) }.getOrNull()?.let { uri ->
                DocumentFileSource(app, uri, staged.name, staged.size, staged.mime)
            }
        }
        if (sources.isEmpty()) {
            _error.value = app.getString(R.string.send_no_files)
            return
        }

        val code = _pairingCode.value.ifBlank { coordinator.newPairingCode() }
        _pairingCode.value = code
        val id = coordinator.startSend(peer = peer, files = sources, pairingCode = code)
        sendableSessions[id] = peer to selection.items
        SendService.start(app)
        Logs.i(TAG, "Send $id started to ${peer.deviceName}")
    }

    fun cancelTransfer(transferId: String) = coordinator.cancel(transferId)

    /**
     * Re-sends a transfer that failed.
     *
     * Only possible for a transfer started in this session: the content URIs are not persisted, so a
     * record restored from history cannot be retried without the user picking the files again. That is
     * stated plainly rather than failing obscurely.
     */
    fun retry(record: TransferRecord) {
        val session = sendableSessions[record.transferId]
        if (session == null) {
            _error.value = app.getString(R.string.send_no_files_hint)
            return
        }
        val (peer, staged) = session
        val sources = staged.mapNotNull { item ->
            runCatching { android.net.Uri.parse(item.uri) }.getOrNull()?.let { uri ->
                DocumentFileSource(app, uri, item.name, item.size, item.mime)
            }
        }
        if (sources.isEmpty()) {
            _error.value = app.getString(R.string.send_no_files_hint)
            return
        }
        val id = coordinator.startSend(peer = peer, files = sources, pairingCode = coordinator.newPairingCode())
        sendableSessions[id] = peer to staged
        SendService.start(app)
    }

    // =========================================================================================
    // History
    // =========================================================================================

    fun clearHistory() {
        _history.value = coordinator.clearHistory()
    }

    fun removeHistory(transferId: String) {
        _history.value = coordinator.removeHistory(transferId)
    }

    // =========================================================================================
    // Transient messages
    // =========================================================================================

    fun clearError() {
        _error.value = null
    }

    fun clearNotice() {
        _notice.value = null
    }

    /**
     * Tells the user that notifications were declined.
     *
     * Worth saying out loud: transfers still work, but the persistent progress notification - the one
     * thing that lets a transfer be watched from outside the app - will not appear.
     */
    fun showNotificationsDisabledNotice() {
        _notice.value = app.getString(R.string.error_notifications_denied)
    }

    /** Formats a progress line: speed and remaining time, exactly as the notification does. */
    fun progressSubtitle(progress: TransferProgress): String {
        val eta = progress.etaSeconds
        val etaText = if (eta > 0) ByteFormat.duration(eta) else "--"
        return app.getString(R.string.transfer_speed_eta, ByteFormat.speed(progress.bytesPerSecond), etaText)
    }

    fun transferOf(progress: TransferProgress): String =
        app.getString(R.string.transfer_file_of, progress.fileIndex + 1, progress.fileCount)
}
