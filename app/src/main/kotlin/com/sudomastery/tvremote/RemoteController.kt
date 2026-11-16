package com.sudomastery.tvremote

import android.app.Application
import android.os.SystemClock
import com.sudomastery.atv.AtvRemote
import com.sudomastery.atv.ClientIdentity
import com.sudomastery.atv.ConnectionStatus
import com.sudomastery.atv.Volume
import com.sudomastery.atv.proto.RemoteProto.RemoteDirection
import com.sudomastery.tvremote.data.SavedTv
import com.sudomastery.tvremote.data.Store
import com.sudomastery.tvremote.ir.IrBlaster
import com.sudomastery.tvremote.ir.TclIr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class TvState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val volume: Volume? = null,
    val isOn: Boolean? = null,
    val voiceAvailable: Boolean = false,
)

/**
 * App-wide owner of the TV connection, shared by the remote screen and the notification controls
 * so both drive the same link and the same routing (Wi-Fi or IR).
 *
 * The connection is kept open while anyone holds it (see [hold] / [release]) and closed after a
 * grace period once nobody does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteController(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Declared first: tvState below starts collecting during construction (Main.immediate) and
    // reads wantsConnection, so these must already be initialised.
    private val holders = MutableStateFlow<Set<String>>(emptySet())
    private var releaseJob: Job? = null
    private val wantsConnection: Boolean get() = holders.value.isNotEmpty()

    val store = Store(app)
    private val ir = IrBlaster(app)

    /** Whether this phone has an IR emitter at all. */
    val irAvailable: Boolean = ir.available

    private val _irMode = MutableStateFlow(store.irMode && ir.available)
    /** Send everything over infrared instead of Wi-Fi. */
    val irMode: StateFlow<Boolean> = _irMode.asStateFlow()

    var identity: ClientIdentity? = null
        private set
    val identityReady = MutableStateFlow(false)

    private val _tvs = MutableStateFlow(store.tvs)
    val tvs: StateFlow<List<SavedTv>> = _tvs.asStateFlow()

    private val _current = MutableStateFlow(_tvs.value.firstOrNull { it.host == store.selectedHost } ?: _tvs.value.firstOrNull())
    val current: StateFlow<SavedTv?> = _current.asStateFlow()

    private val remote = MutableStateFlow<AtvRemote?>(null)
    /** The live connection to the current TV, if any. */
    val currentRemote: AtvRemote? get() = remote.value

    val tvState: StateFlow<TvState> = remote.flatMapLatest { r ->
        // Before the identity loads there is no remote yet; show "Connecting" rather than "Not connected".
        if (r == null) {
            // Reactive on holders: the screen calls hold() just after this flow first runs.
            combine(_current, _irMode, holders) { tv, ir, h ->
                TvState(if (tv != null && !ir && h.isNotEmpty()) ConnectionStatus.Connecting else ConnectionStatus.Disconnected)
            }
        } else {
            combine(r.status, r.volume, r.isOn, r.voiceAvailable) { s, v, on, voice -> TvState(s, v, on, voice) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, TvState())

    init {
        scope.launch {
            identity = withContext(Dispatchers.Default) {
                store.loadIdentity() ?: ClientIdentity.generate("TV Remote").also(store::saveIdentity)
            }
            identityReady.value = true
            _current.value?.let { openRemote(it) }
        }
    }

    // region connection holders

    /** Keeps the Wi-Fi link open on behalf of [owner] (the screen, the notification…). */
    fun hold(owner: String) {
        holders.value = holders.value + owner
        releaseJob?.cancel()
        if (!_irMode.value) remote.value?.connect()
    }

    /** Drops [owner]'s claim; the link closes [graceMs] after the last holder lets go. */
    fun release(owner: String, graceMs: Long) {
        // Ignore owners that weren't holding, or they'd reset another owner's grace period.
        if (owner !in holders.value) return
        holders.value = holders.value - owner
        if (holders.value.isNotEmpty()) return
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(graceMs)
            if (holders.value.isEmpty()) remote.value?.disconnect()
        }
    }

    // endregion

    // region keys

    /** Returns false if the command could not be delivered. */
    fun press(key: RemoteKey, direction: RemoteDirection = RemoteDirection.SHORT): Boolean {
        // A power tap in the middle of a hold would arrive as a normal press and sleep the TV.
        if (key == RemoteKey.POWER && powerHoldActive) return false
        if (_irMode.value) {
            // IR has no key-up, so a held OK is just a press.
            if (direction == RemoteDirection.END_LONG) return true
            if (key == RemoteKey.POWER) return ir.sendPattern(TclIr.tapPattern(TclIr.keys.getValue(RemoteKey.POWER)))
            return TclIr.keys[key]?.let(ir::send) ?: false
        }
        val r = remote.value
        if (key == RemoteKey.POWER && r?.isLive != true) return powerWithoutWifi(r)
        return r?.sendKey(key.atv, direction) ?: false
    }

    /**
     * Power when Wi-Fi can't reach the TV. A TV in deep standby is off the network, so the only
     * way to switch it on is infrared. This applies to power alone: every other key needs the TV
     * to be on, at which point it is back on Wi-Fi.
     */
    private fun powerWithoutWifi(r: AtvRemote?): Boolean {
        r?.connect() // start reconnecting so the rest of the remote works once the TV is up
        if (!ir.available) {
            showNotice("Can't reach the TV over Wi-Fi")
            return false
        }
        val sent = ir.sendPattern(TclIr.tapPattern(TclIr.keys.getValue(RemoteKey.POWER)))
        showNotice(if (sent) "TV not on Wi-Fi, sent power over IR" else "Couldn't use the IR blaster")
        return sent
    }

    /** Whether a power tap right now would go out over IR (shown as a badge on the button). */
    val powerUsesIr: StateFlow<Boolean> = combine(_irMode, tvState) { irMode, state ->
        irAvailable && (irMode || state.status != ConnectionStatus.Connected)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Emitter details for the settings screen. */
    val irDescription: String get() = ir.description

    private class PendingKey(val key: RemoteKey, val at: Long = SystemClock.elapsedRealtime())
    private val pendingKeys = ArrayDeque<PendingKey>()
    private var pendingJob: Job? = null

    /**
     * For callers that don't keep the link open (the notification): if the TV isn't connected
     * yet, queue the key, connect, and send the queue in order once connected (or over IR if IR
     * mode is switched on meanwhile). Keys older than [KEY_TTL_MS] are dropped: a press that
     * lands long after the user tapped would be a surprise.
     */
    fun pressWhenReady(key: RemoteKey) {
        if (pendingKeys.isEmpty() && (_irMode.value || remote.value?.isConnected == true)) {
            press(key)
            return
        }
        if (pendingKeys.size < MAX_PENDING) pendingKeys.addLast(PendingKey(key))
        if (pendingJob?.isActive == true) return
        pendingJob = scope.launch {
            try {
                identityReady.first { it }
                val r = remote.value ?: return@launch
                if (!_irMode.value) r.connect()
                withTimeoutOrNull(CONNECT_WAIT_MS) {
                    combine(r.status, _irMode) { status, ir -> ir || status == ConnectionStatus.Connected }.first { it }
                } ?: return@launch
                if (!_irMode.value && remote.value !== r) return@launch
                val now = SystemClock.elapsedRealtime()
                while (pendingKeys.isNotEmpty()) {
                    val pending = pendingKeys.removeFirst()
                    if (now - pending.at <= KEY_TTL_MS) press(pending.key)
                }
            } finally {
                pendingKeys.clear()
            }
        }
    }

    fun launch(app: AppShortcut): Boolean {
        if (_irMode.value) return TclIr.apps[app]?.let(ir::send) ?: false
        return remote.value?.launchApp(app.link) ?: false
    }

    fun sendText(text: String) = remote.value?.sendText(text) ?: false

    fun setIrMode(enabled: Boolean) {
        if (enabled == _irMode.value || blockedByPowerHold()) return
        val on = enabled && ir.available
        _irMode.value = on
        store.irMode = on
        if (on) {
            remote.value?.disconnect()
        } else if (wantsConnection) {
            remote.value?.connect()
        }
    }

    // endregion

    // region power off menu

    private val _powerMenuOpening = MutableStateFlow(false)
    /** True while the app is holding the power key to open the TV's shut down dialog. */
    val powerMenuOpening: StateFlow<Boolean> = _powerMenuOpening.asStateFlow()
    private var powerHoldJob: Job? = null
    /** The connection holding the power key down over Wi-Fi, until it sends the key-up. */
    private var heldRemote: AtvRemote? = null
    private val powerHoldActive: Boolean get() = powerHoldJob?.isActive == true

    /**
     * Holds the power key like a physical remote, which opens the TV's "Power off" dialog
     * (full shut down) instead of the normal sleep. Returns false if it couldn't start.
     *
     * The hold has a fixed length no matter when the finger lifts: on the TCL a 1.5 s hold opens
     * the dialog, while 0.8 s counts as a normal press and puts the TV to sleep.
     */
    fun openPowerMenu(): Boolean {
        if (powerHoldActive) return true
        if (_irMode.value) {
            val held = TclIr.heldPattern(TclIr.keys.getValue(RemoteKey.POWER))
            if (!ir.sendPattern(held)) {
                showNotice("The IR blaster isn't available")
                return false
            }
            powerHoldJob = scope.launch {
                _powerMenuOpening.value = true
                try {
                    delay(TclIr.durationMs(held))
                } finally {
                    _powerMenuOpening.value = false
                }
            }
            return true
        }
        // No IR fallback here, unlike a power tap: if Wi-Fi can't reach the TV it is most likely
        // already off, and holding power over IR would switch it on instead.
        val r = remote.value
        if (r == null || !r.isConnected) {
            r?.connect()
            showNotice(if (ir.available) "Not connected. Wait a moment or switch to IR." else "Not connected to the TV yet")
            return false
        }
        if (!r.sendKey(RemoteKey.POWER.atv, RemoteDirection.START_LONG)) {
            showNotice("Couldn't reach the TV")
            return false
        }
        heldRemote = r
        powerHoldJob = scope.launch {
            _powerMenuOpening.value = true
            try {
                delay(POWER_HOLD_MS)
            } finally {
                releasePowerKey()
                _powerMenuOpening.value = false
            }
        }
        return true
    }

    /** Waits for a running power hold to finish, so its key-up goes out on the old connection. */
    suspend fun awaitPowerHold() {
        powerHoldJob?.join()
    }

    /** Key-up for a Wi-Fi power hold, only on the connection that pressed it. */
    private fun releasePowerKey() {
        val r = heldRemote ?: return
        heldRemote = null
        // sendKey on a dropped connection would reconnect it, so check first.
        if (r.isConnected) r.sendKey(RemoteKey.POWER.atv, RemoteDirection.END_LONG)
    }

    /**
     * Switching mode or TV mid-hold would cut the hold short, and a short hold sleeps the TV.
     * It only lasts 2 seconds, so ask the user to wait instead.
     */
    private fun blockedByPowerHold(): Boolean {
        if (!powerHoldActive) return false
        showNotice("Opening the power off menu, one moment")
        return true
    }

    // endregion

    // region notices

    private val _notice = MutableStateFlow<String?>(null)
    /** A short message shown in the header for a few seconds. */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private var noticeJob: Job? = null

    fun showNotice(text: String) {
        noticeJob?.cancel()
        _notice.value = text
        noticeJob = scope.launch {
            delay(3_000)
            _notice.value = null
        }
    }

    // endregion

    // region devices

    fun select(tv: SavedTv) {
        if (_current.value?.host == tv.host && remote.value != null) return
        if (blockedByPowerHold()) return
        _current.value = tv
        store.selectedHost = tv.host
        openRemote(tv)
    }

    fun forget(tv: SavedTv) {
        if (_current.value?.host == tv.host && blockedByPowerHold()) return
        _tvs.value = _tvs.value.filterNot { it.host == tv.host }
        store.tvs = _tvs.value
        if (_current.value?.host == tv.host) {
            remote.value?.disconnect()
            remote.value = null
            _current.value = _tvs.value.firstOrNull()
            store.selectedHost = _current.value?.host
            _current.value?.let { openRemote(it) }
        }
    }

    /** Saves a freshly paired TV and switches to it with a new connection. */
    suspend fun addPaired(tv: SavedTv) {
        _tvs.value = _tvs.value.filterNot { it.host == tv.host } + tv
        store.tvs = _tvs.value
        awaitPowerHold() // let a running power hold finish on the old connection
        remote.value?.disconnect()
        remote.value = null
        select(tv)
    }

    private fun openRemote(tv: SavedTv) {
        val id = identity ?: return
        remote.value?.disconnect()
        val r = AtvRemote(tv.host, id, tv.pinSha256, scope)
        remote.value = r
        if (wantsConnection && !_irMode.value) r.connect()
    }

    // endregion

    private companion object {
        const val POWER_HOLD_MS = 2_000L
        const val CONNECT_WAIT_MS = 5_000L
        const val MAX_PENDING = 20
        const val KEY_TTL_MS = 2_000L
    }
}
