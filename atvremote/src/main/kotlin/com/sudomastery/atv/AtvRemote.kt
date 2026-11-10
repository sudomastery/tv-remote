package com.sudomastery.atv

import com.sudomastery.atv.proto.RemoteProto.RemoteAppLinkLaunchRequest
import com.sudomastery.atv.proto.RemoteProto.RemoteConfigure
import com.sudomastery.atv.proto.RemoteProto.RemoteDeviceInfo
import com.sudomastery.atv.proto.RemoteProto.RemoteDirection
import com.sudomastery.atv.proto.RemoteProto.RemoteEditInfo
import com.sudomastery.atv.proto.RemoteProto.RemoteImeBatchEdit
import com.sudomastery.atv.proto.RemoteProto.RemoteImeObject
import com.sudomastery.atv.proto.RemoteProto.RemoteKeyCode
import com.sudomastery.atv.proto.RemoteProto.RemoteKeyInject
import com.sudomastery.atv.proto.RemoteProto.RemoteMessage
import com.sudomastery.atv.proto.RemoteProto.RemotePingResponse
import com.sudomastery.atv.proto.RemoteProto.RemoteSetActive
import com.sudomastery.atv.proto.RemoteProto.RemoteVoiceBegin
import com.sudomastery.atv.proto.RemoteProto.RemoteVoiceEnd
import com.sudomastery.atv.proto.RemoteProto.RemoteVoicePayload
import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

data class Volume(val level: Int, val max: Int, val muted: Boolean)

data class DeviceInfo(val manufacturer: String, val model: String, val softwareVersion: String)

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus
    data object Connecting : ConnectionStatus
    data object Connected : ConnectionStatus
    /** The TV does not know our certificate. */
    data object NeedsPairing : ConnectionStatus
    /** The TV presented a different certificate than at pairing time. */
    data object CertificateChanged : ConnectionStatus
    data class Unreachable(val reason: String) : ConnectionStatus
}

/** Feature bits negotiated in remote_configure / remote_set_active. */
object Features {
    const val PING = 1 shl 0
    const val KEY = 1 shl 1
    const val IME = 1 shl 2
    const val VOICE = 1 shl 3
    const val POWER = 1 shl 5
    const val VOLUME = 1 shl 6
    const val APP_LINK = 1 shl 9
    const val DEFAULT = PING or KEY or IME or VOICE or POWER or VOLUME or APP_LINK
}

/**
 * A persistent, self-healing connection to the TV's remote service on port 6466.
 *
 * Commands are queued to a writer coroutine, so every send method is non-blocking and safe to
 * call from the main thread. While disconnected, commands are dropped (a key press replayed
 * seconds later would be worse than a missed one) and a reconnect is kicked off.
 */
class AtvRemote(
    val host: String,
    private val identity: ClientIdentity,
    private val pinnedServerSha256: ByteArray?,
    private val scope: CoroutineScope,
    private val requestedFeatures: Int = Features.DEFAULT,
    private val port: Int = REMOTE_PORT,
) {
    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _volume = MutableStateFlow<Volume?>(null)
    val volume: StateFlow<Volume?> = _volume.asStateFlow()

    private val _isOn = MutableStateFlow<Boolean?>(null)
    val isOn: StateFlow<Boolean?> = _isOn.asStateFlow()

    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _voiceAvailable = MutableStateFlow(false)
    val voiceAvailable: StateFlow<Boolean> = _voiceAvailable.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var outbox: Channel<RemoteMessage>? = null
    @Volatile private var activeFeatures = requestedFeatures

    private var imeCounter = 0
    private var imeFieldCounter = 0
    private var hasImeCounter = false
    private var hasImeFieldCounter = false
    @Volatile private var voiceBegin: CompletableDeferred<Int>? = null

    val isConnected: Boolean get() = _status.value == ConnectionStatus.Connected

    /** Starts (or keeps) the connection loop. Cheap to call repeatedly. */
    @Synchronized
    fun connect() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { connectionLoop() }
    }

    @Synchronized
    fun disconnect() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        _status.value = ConnectionStatus.Disconnected
    }

    fun sendKey(key: RemoteKeyCode, direction: RemoteDirection = RemoteDirection.SHORT): Boolean =
        send(RemoteMessage.newBuilder().setRemoteKeyInject(RemoteKeyInject.newBuilder().setKeyCode(key).setDirection(direction)))

    /** Inserts text into the focused text field on the TV. */
    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return false
        val edit = RemoteEditInfo.newBuilder()
            .setInsert(1)
            .setTextFieldStatus(RemoteImeObject.newBuilder().setStart(text.length).setEnd(text.length).setValue(text))
        return send(
            RemoteMessage.newBuilder().setRemoteImeBatchEdit(
                RemoteImeBatchEdit.newBuilder().setImeCounter(imeCounter).setFieldCounter(imeFieldCounter).addEditInfo(edit),
            ),
        )
    }

    /** Opens an app by package name (e.g. `com.netflix.ninja`) or deep link URL. */
    fun launchApp(packageOrLink: String): Boolean {
        val link = if ("://" in packageOrLink) packageOrLink else "market://launch?id=$packageOrLink"
        return send(RemoteMessage.newBuilder().setRemoteAppLinkLaunchRequest(RemoteAppLinkLaunchRequest.newBuilder().setAppLink(link)))
    }

    /**
     * Opens a voice session on the TV and returns its id, or null if the TV didn't respond.
     * Stream 16-bit PCM, mono, 8 kHz with [sendVoice], then call [endVoice].
     */
    suspend fun startVoice(): Int? {
        if (!_voiceAvailable.value || !isConnected) return null
        val deferred = CompletableDeferred<Int>().also { voiceBegin = it }
        sendKey(RemoteKeyCode.KEYCODE_SEARCH)
        val id = withTimeoutOrNull(VOICE_START_TIMEOUT_MS) { deferred.await() }
        voiceBegin = null
        if (id != null) send(RemoteMessage.newBuilder().setRemoteVoiceBegin(RemoteVoiceBegin.newBuilder().setSessionId(id)))
        return id
    }

    fun sendVoice(sessionId: Int, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + VOICE_CHUNK_SIZE, pcm.size)
            // The TV drops chunks under 8 KB, so pad the tail with silence.
            val chunk = pcm.copyOfRange(offset, end).let { if (it.size < VOICE_CHUNK_MIN_SIZE) it.copyOf(VOICE_CHUNK_MIN_SIZE) else it }
            send(RemoteMessage.newBuilder().setRemoteVoicePayload(RemoteVoicePayload.newBuilder().setSessionId(sessionId).setSamples(ByteString.copyFrom(chunk))))
            offset = end
        }
    }

    fun endVoice(sessionId: Int) {
        send(RemoteMessage.newBuilder().setRemoteVoiceEnd(RemoteVoiceEnd.newBuilder().setSessionId(sessionId)))
    }

    private fun send(builder: RemoteMessage.Builder): Boolean {
        val box = outbox
        if (box == null || !isConnected) {
            connect()
            return false
        }
        return box.trySend(builder.build()).isSuccess
    }

    private suspend fun connectionLoop() {
        var failures = 0
        while (scope.isActive) {
            if (_status.value != ConnectionStatus.Connected) _status.value = ConnectionStatus.Connecting
            val trust = PinningTrustManager(pinnedServerSha256)
            var receivedAny = false
            try {
                val s = Tls.connect(host, port, identity, trust)
                socket = s
                runSession(s) { receivedAny = true; failures = 0 }
                // Clean EOF before the TV said anything: it dropped us, most likely an unknown certificate.
                if (!receivedAny) {
                    _status.value = ConnectionStatus.NeedsPairing
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    trust.pinMismatch -> {
                        _status.value = ConnectionStatus.CertificateChanged
                        return
                    }
                    // TLS alerts before the first message mean the TV refused our certificate.
                    !receivedAny && e.isAuthFailure() -> {
                        _status.value = ConnectionStatus.NeedsPairing
                        return
                    }
                    else -> _status.value = ConnectionStatus.Unreachable(e.message ?: e.javaClass.simpleName)
                }
            } finally {
                outbox = null
                runCatching { socket?.close() }
                socket = null
            }
            failures++
            delay(minOf(250L shl minOf(failures, 5), 5_000L))
        }
    }

    /** Protobuf wraps socket errors, so look through the whole cause chain. */
    private fun Exception.isAuthFailure(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.any {
            it is SSLException || it is EOFException ||
                (it !is SocketTimeoutException && it.message?.contains("reset", ignoreCase = true) == true)
        }

    private suspend fun runSession(s: SSLSocket, onMessage: () -> Unit) = coroutineScope {
        // The TV pings every 5 s; three missed pings means the link is dead.
        s.soTimeout = IDLE_TIMEOUT_MS
        val out = BufferedOutputStream(s.outputStream)
        val box = Channel<RemoteMessage>(Channel.UNLIMITED)
        outbox = box
        val writer = launch {
            for (msg in box) {
                msg.writeDelimitedTo(out)
                out.flush()
            }
        }
        try {
            val input = BufferedInputStream(s.inputStream)
            while (true) {
                val msg = RemoteMessage.parseDelimitedFrom(input) ?: break
                onMessage()
                handle(msg, box)
            }
        } finally {
            box.close()
            writer.cancel()
            if (_status.value == ConnectionStatus.Connected) _status.value = ConnectionStatus.Connecting
        }
    }

    private fun handle(msg: RemoteMessage, box: Channel<RemoteMessage>) {
        val reply: RemoteMessage? = when {
            msg.hasRemotePingRequest() -> RemoteMessage.newBuilder()
                .setRemotePingResponse(RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1)).build()

            msg.hasRemoteConfigure() -> {
                val info = msg.remoteConfigure.deviceInfo
                _deviceInfo.value = DeviceInfo(info.vendor, info.model, info.appVersion)
                activeFeatures = requestedFeatures and msg.remoteConfigure.code1
                _voiceAvailable.value = activeFeatures and Features.VOICE != 0
                RemoteMessage.newBuilder().setRemoteConfigure(
                    RemoteConfigure.newBuilder().setCode1(activeFeatures).setDeviceInfo(
                        RemoteDeviceInfo.newBuilder().setUnknown1(1).setUnknown2("1").setPackageName("atvremote").setAppVersion("1.0.0"),
                    ),
                ).build()
            }

            msg.hasRemoteSetActive() -> RemoteMessage.newBuilder()
                .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(activeFeatures)).build()

            msg.hasRemoteStart() -> {
                _isOn.value = msg.remoteStart.started
                _status.value = ConnectionStatus.Connected
                null
            }

            msg.hasRemoteSetVolumeLevel() -> {
                val v = msg.remoteSetVolumeLevel
                _volume.value = Volume(v.volumeLevel, v.volumeMax, v.volumeMuted)
                null
            }

            msg.hasRemoteImeKeyInject() -> {
                val inject = msg.remoteImeKeyInject
                _currentApp.value = inject.appInfo.appPackage
                imeCounter = inject.appInfo.counter
                imeFieldCounter = inject.textFieldStatus.counterField
                hasImeCounter = imeCounter != 0
                hasImeFieldCounter = imeFieldCounter != 0
                null
            }

            msg.hasRemoteImeShowRequest() -> {
                if (msg.remoteImeShowRequest.hasRemoteTextFieldStatus()) {
                    imeFieldCounter = msg.remoteImeShowRequest.remoteTextFieldStatus.counterField
                    hasImeFieldCounter = true
                }
                null
            }

            msg.hasRemoteImeBatchEdit() -> {
                if (!hasImeCounter) imeCounter = msg.remoteImeBatchEdit.imeCounter
                if (!hasImeFieldCounter) imeFieldCounter = msg.remoteImeBatchEdit.fieldCounter
                null
            }

            msg.hasRemoteVoiceBegin() -> {
                voiceBegin?.complete(msg.remoteVoiceBegin.sessionId)
                null
            }

            else -> null
        }
        if (reply != null) box.trySend(reply)
    }

    companion object {
        const val REMOTE_PORT = 6466
        private const val IDLE_TIMEOUT_MS = 16_000
        private const val VOICE_START_TIMEOUT_MS = 2_000L
        private const val VOICE_CHUNK_SIZE = 20 * 1024
        private const val VOICE_CHUNK_MIN_SIZE = 8 * 1024
    }
}
