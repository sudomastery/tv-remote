package com.sudomastery.atv

import com.google.polo.wire.protobuf.PoloProto.OuterMessage
import com.google.polo.wire.protobuf.PoloProto.Options
import com.google.polo.wire.protobuf.PoloProto.PairingRequestAck
import com.google.polo.wire.protobuf.PoloProto.SecretAck
import com.google.polo.wire.protobuf.PoloProto.ConfigurationAck
import com.sudomastery.atv.proto.RemoteProto.RemoteConfigure
import com.sudomastery.atv.proto.RemoteProto.RemoteDeviceInfo
import com.sudomastery.atv.proto.RemoteProto.RemoteMessage
import com.sudomastery.atv.proto.RemoteProto.RemotePingRequest
import com.sudomastery.atv.proto.RemoteProto.RemoteSetActive
import com.sudomastery.atv.proto.RemoteProto.RemoteSetVolumeLevel
import com.sudomastery.atv.proto.RemoteProto.RemoteStart
import java.io.BufferedInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * A stand-in Android TV for testing the app without a real TV: answers pairing on 6467 (and
 * prints the code to type), accepts remote connections on 6466 and logs every key it receives.
 *
 *   ./gradlew -q :atvremote:fakeTv
 *
 * From the Android emulator the PC is reachable at 10.0.2.2.
 */
fun main() {
    // Keep one identity across restarts: the app pins the TV certificate at pairing time.
    val dir = java.io.File(".atv").apply { mkdirs() }
    val keyFile = java.io.File(dir, "faketv.key")
    val certFile = java.io.File(dir, "faketv.crt")
    val identity = if (keyFile.exists() && certFile.exists()) {
        ClientIdentity.fromEncoded(keyFile.readBytes(), certFile.readBytes())
    } else {
        ClientIdentity.generate("FakeTV").also {
            keyFile.writeBytes(it.privateKeyEncoded)
            certFile.writeBytes(it.certificateEncoded)
        }
    }
    val context = serverContext(identity)
    thread(name = "pairing") { serve(context, AtvRemote.REMOTE_PORT + 1, ::pairing) }
    thread(name = "remote") { serve(context, AtvRemote.REMOTE_PORT, ::remote) }
    log("Fake TV listening on 6466 (remote) and 6467 (pairing)")
    Thread.currentThread().join()
}

private fun log(msg: String) = println("[fake-tv ${System.currentTimeMillis() % 100_000}] $msg").also { System.out.flush() }

private fun serverContext(identity: ClientIdentity): SSLContext {
    val ks = KeyStore.getInstance("PKCS12").apply {
        load(null)
        setKeyEntry("tv", identity.privateKey, CharArray(0), arrayOf(identity.certificate))
    }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll), SecureRandom()) }
}

private fun serve(context: SSLContext, port: Int, handler: (SSLSocket) -> Unit) {
    val server = context.serverSocketFactory.createServerSocket(port) as SSLServerSocket
    server.needClientAuth = true
    while (true) {
        val s = server.accept() as SSLSocket
        thread { runCatching { s.use(handler) }.onFailure { log("port $port: ${it.message}") } }
    }
}

private fun pairing(s: SSLSocket) {
    s.startHandshake()
    val input = BufferedInputStream(s.inputStream)
    val out = s.outputStream
    fun send(b: OuterMessage.Builder) = b.setProtocolVersion(2).setStatus(OuterMessage.Status.STATUS_OK).build().writeDelimitedTo(out)
    val client = s.session.peerCertificates[0] as X509Certificate
    val server = s.session.localCertificates[0] as X509Certificate
    var tail = ""
    while (true) {
        val msg = OuterMessage.parseDelimitedFrom(input) ?: return
        when {
            msg.hasPairingRequest() -> {
                log("pairing request from '${msg.pairingRequest.clientName}'")
                send(OuterMessage.newBuilder().setPairingRequestAck(PairingRequestAck.newBuilder().setServerName("FakeTV")))
            }
            msg.hasOptions() -> send(
                OuterMessage.newBuilder().setOptions(
                    Options.newBuilder().addInputEncodings(
                        Options.Encoding.newBuilder().setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL).setSymbolLength(6),
                    ).setPreferredRole(Options.RoleType.ROLE_TYPE_INPUT),
                ),
            )
            msg.hasConfiguration() -> {
                tail = "%04X".format(SecureRandom().nextInt(0x10000))
                val first = PairingSession.computeSecret(client, server, "00$tail")[0]
                log("PAIRING CODE: ${"%02X".format(first)}$tail")
                send(OuterMessage.newBuilder().setConfigurationAck(ConfigurationAck.getDefaultInstance()))
            }
            msg.hasSecret() -> {
                log("secret received, paired")
                send(OuterMessage.newBuilder().setSecretAck(SecretAck.newBuilder().setSecret(msg.secret.secret)))
            }
        }
    }
}

private fun remote(s: SSLSocket) {
    s.startHandshake()
    log("remote connected")
    val input = BufferedInputStream(s.inputStream)
    val out = s.outputStream
    val lock = Any()
    fun send(m: RemoteMessage.Builder) = synchronized(lock) { m.build().writeDelimitedTo(out); out.flush() }
    send(
        RemoteMessage.newBuilder().setRemoteConfigure(
            RemoteConfigure.newBuilder().setCode1(Features.DEFAULT).setDeviceInfo(RemoteDeviceInfo.newBuilder().setVendor("Fake").setModel("TV")),
        ),
    )
    // Ping like the real TV so the client's idle timeout doesn't fire.
    val pinger = thread(isDaemon = true) {
        runCatching {
            var n = 0
            while (true) {
                Thread.sleep(5_000)
                send(RemoteMessage.newBuilder().setRemotePingRequest(RemotePingRequest.newBuilder().setVal1(++n)))
            }
        }
    }
    try {
        while (true) {
            val msg = RemoteMessage.parseDelimitedFrom(input) ?: break
            when {
                msg.hasRemoteConfigure() -> send(RemoteMessage.newBuilder().setRemoteSetActive(RemoteSetActive.newBuilder().setActive(Features.DEFAULT)))
                msg.hasRemoteSetActive() -> {
                    send(RemoteMessage.newBuilder().setRemoteStart(RemoteStart.newBuilder().setStarted(true)))
                    send(RemoteMessage.newBuilder().setRemoteSetVolumeLevel(RemoteSetVolumeLevel.newBuilder().setVolumeLevel(7).setVolumeMax(100)))
                }
                msg.hasRemoteKeyInject() -> log("KEY ${msg.remoteKeyInject.keyCode} ${msg.remoteKeyInject.direction}")
                msg.hasRemoteAppLinkLaunchRequest() -> log("APP ${msg.remoteAppLinkLaunchRequest.appLink}")
                msg.hasRemoteImeBatchEdit() -> log("TEXT ${msg.remoteImeBatchEdit.editInfoList.joinToString { it.textFieldStatus.value }}")
                msg.hasRemotePingResponse() -> Unit
                else -> log("other: ${msg.toString().trim().take(80)}")
            }
        }
    } finally {
        pinger.interrupt()
        log("remote disconnected")
    }
}
