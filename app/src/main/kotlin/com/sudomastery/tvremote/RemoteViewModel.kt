package com.sudomastery.tvremote

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sudomastery.atv.AtvRemote
import com.sudomastery.atv.ClientIdentity
import com.sudomastery.atv.ConnectionStatus
import com.sudomastery.atv.PairingSession
import com.sudomastery.atv.Volume
import com.sudomastery.atv.WrongCodeException
import com.sudomastery.atv.proto.RemoteProto.RemoteDirection
import com.sudomastery.tvremote.data.Discovery
import com.sudomastery.tvremote.data.FoundTv
import com.sudomastery.tvremote.data.SavedTv
import com.sudomastery.tvremote.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TvState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val volume: Volume? = null,
    val isOn: Boolean? = null,
    val voiceAvailable: Boolean = false,
)

sealed interface PairingState {
    data object Idle : PairingState
    data class Connecting(val tv: FoundTv) : PairingState
    data class EnterCode(val tv: FoundTv, val error: String? = null, val verifying: Boolean = false) : PairingState
    data class Failed(val tv: FoundTv, val message: String) : PairingState
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val discovery = Discovery(app)

    private var identity: ClientIdentity? = null
    private val identityReady = MutableStateFlow(false)

    private val _tvs = MutableStateFlow(store.tvs)
    val tvs: StateFlow<List<SavedTv>> = _tvs.asStateFlow()

    private val _current = MutableStateFlow(_tvs.value.firstOrNull { it.host == store.selectedHost } ?: _tvs.value.firstOrNull())
    val current: StateFlow<SavedTv?> = _current.asStateFlow()

    private val remote = MutableStateFlow<AtvRemote?>(null)

    val tvState: StateFlow<TvState> = remote.flatMapLatest { r ->
        if (r == null) flowOf(TvState()) else combine(r.status, r.volume, r.isOn, r.voiceAvailable) { s, v, on, voice ->
            TvState(s, v, on, voice)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvState())

    private val _pairing = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()
    private var pairingSession: PairingSession? = null

    private val _haptics = MutableStateFlow(store.haptics)
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private var voiceJob: Job? = null

    private var foreground = false
    private var backgroundJob: Job? = null

    init {
        viewModelScope.launch {
            identity = withContext(Dispatchers.Default) {
                store.loadIdentity() ?: ClientIdentity.generate("TV Remote").also(store::saveIdentity)
            }
            identityReady.value = true
            _current.value?.let { openRemote(it) }
        }
    }

    fun scan(): Flow<List<FoundTv>> = discovery.scan()

    // region lifecycle

    fun onForeground() {
        foreground = true
        backgroundJob?.cancel()
        remote.value?.connect()
    }

    /** Keep the link warm for a short while so quick app switches reconnect instantly. */
    fun onBackground() {
        foreground = false
        stopVoice()
        backgroundJob?.cancel()
        backgroundJob = viewModelScope.launch {
            delay(30_000)
            remote.value?.disconnect()
        }
    }

    override fun onCleared() {
        remote.value?.disconnect()
        pairingSession?.close()
    }

    // endregion

    // region keys

    /** Returns false if the command could not be delivered. */
    fun press(key: RemoteKey, direction: RemoteDirection = RemoteDirection.SHORT): Boolean {
        val r = remote.value ?: return false
        return r.sendKey(key.atv, direction)
    }

    fun launch(app: AppShortcut) = remote.value?.launchApp(app.link) ?: false

    fun sendText(text: String) = remote.value?.sendText(text) ?: false

    fun setHaptics(enabled: Boolean) {
        store.haptics = enabled
        _haptics.value = enabled
    }

    // endregion

    // region devices and pairing

    fun select(tv: SavedTv) {
        if (_current.value?.host == tv.host && remote.value != null) return
        _current.value = tv
        store.selectedHost = tv.host
        openRemote(tv)
    }

    fun forget(tv: SavedTv) {
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

    /** Called when the user picks a TV. Paired ones connect directly, new ones start pairing. */
    fun choose(found: FoundTv) {
        val saved = _tvs.value.firstOrNull { it.host == found.host }
        if (saved?.pinSha256 != null) select(saved) else startPairing(found)
    }

    fun repairCurrent() {
        _current.value?.let { startPairing(FoundTv(it.name, it.host)) }
    }

    fun startPairing(tv: FoundTv) {
        pairingSession?.close()
        _pairing.value = PairingState.Connecting(tv)
        viewModelScope.launch {
            identityReady.first { it }
            val session = PairingSession(tv.host, identity!!, "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            pairingSession = session
            _pairing.value = try {
                session.start()
                PairingState.EnterCode(tv)
            } catch (e: Exception) {
                session.close()
                PairingState.Failed(tv, e.friendlyMessage())
            }
        }
    }

    fun submitCode(code: String) {
        val state = _pairing.value as? PairingState.EnterCode ?: return
        val session = pairingSession ?: return
        _pairing.value = state.copy(verifying = true, error = null)
        viewModelScope.launch {
            try {
                val pin = session.finish(code)
                session.close()
                pairingSession = null
                val tv = SavedTv(state.tv.name, state.tv.host, pin)
                _tvs.value = _tvs.value.filterNot { it.host == tv.host } + tv
                store.tvs = _tvs.value
                _pairing.value = PairingState.Idle
                remote.value?.disconnect()
                remote.value = null
                select(tv)
            } catch (e: WrongCodeException) {
                _pairing.value = state.copy(verifying = false, error = e.message)
            } catch (e: IllegalArgumentException) {
                _pairing.value = state.copy(verifying = false, error = e.message)
            } catch (e: Exception) {
                session.close()
                _pairing.value = PairingState.Failed(state.tv, e.friendlyMessage())
            }
        }
    }

    fun cancelPairing() {
        pairingSession?.close()
        pairingSession = null
        _pairing.value = PairingState.Idle
    }

    private fun openRemote(tv: SavedTv) {
        val id = identity ?: return
        remote.value?.disconnect()
        val r = AtvRemote(tv.host, id, tv.pinSha256, viewModelScope)
        remote.value = r
        if (foreground) r.connect()
    }

    // endregion

    // region voice

    /** Hold-to-talk. Streams 8 kHz mono PCM to the TV's voice search. */
    @SuppressLint("MissingPermission") // checked by the UI before calling
    fun startVoice() {
        val r = remote.value ?: return
        if (!tvState.value.voiceAvailable) {
            r.sendKey(RemoteKey.SEARCH.atv)
            return
        }
        if (voiceJob?.isActive == true) return
        _listening.value = true
        voiceJob = viewModelScope.launch(Dispatchers.IO) {
            val recorder = openRecorder()
            val session = if (recorder != null) r.startVoice() else null
            if (recorder == null || session == null) {
                recorder?.first?.release()
                _listening.value = false
                return@launch
            }
            val (record, decimate) = recorder
            // About 0.25 s of audio per read keeps latency low; AtvRemote pads chunks to the TV's minimum.
            val buffer = ByteArray(if (decimate) 8192 else 4096)
            try {
                record.startRecording()
                while (isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    r.sendVoice(session, if (decimate) downsample(buffer, n) else buffer.copyOf(n))
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                r.endVoice(session)
                _listening.value = false
            }
        }
    }

    fun stopVoice() {
        voiceJob?.cancel()
        voiceJob = null
    }

    /** 8 kHz if the mic supports it, otherwise 16 kHz with every other sample dropped. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, Boolean>? {
        for ((rate, decimate) in listOf(8_000 to false, 16_000 to true)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16_384))
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec to decimate
            rec.release()
        }
        return null
    }

    private fun downsample(pcm: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length / 4 * 2)
        var o = 0
        var i = 0
        while (i + 3 < length) {
            out[o++] = pcm[i]
            out[o++] = pcm[i + 1]
            i += 4
        }
        return out
    }

    // endregion
}

private fun Exception.friendlyMessage(): String = when (this) {
    is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
        "Can't reach the TV. Check it's on and on the same Wi-Fi."
    else -> message ?: "Pairing failed"
}
