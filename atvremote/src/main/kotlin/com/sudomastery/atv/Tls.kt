package com.sudomastery.atv

import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** Thrown when the TV presents a different certificate than the one recorded at pairing time. */
class CertificateChangedException : CertificateException(
    "The TV's certificate changed since pairing. Pair again to trust it.",
)

/**
 * The TV uses a self-signed certificate, so there is no CA to validate against. Instead the
 * certificate seen during pairing is pinned (trust on first use) and checked on every connection.
 */
internal class PinningTrustManager(private val pinnedSha256: ByteArray?) : X509TrustManager {
    @Volatile var serverCertificate: X509Certificate? = null
        private set

    @Volatile var pinMismatch = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        serverCertificate = leaf
        if (pinnedSha256 != null && !MessageDigest.isEqual(sha256(leaf.encoded), pinnedSha256)) {
            pinMismatch = true
            throw CertificateChangedException()
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) =
        throw CertificateException("Not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Always offers our self-signed certificate, whatever CA list the TV asks for. */
private class FixedKeyManager(private val identity: ClientIdentity) : X509KeyManager {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null

    companion object { const val ALIAS = "atvremote" }
}

internal object Tls {
    private const val CONNECT_TIMEOUT_MS = 4_000

    fun connect(host: String, port: Int, identity: ClientIdentity, trust: PinningTrustManager): SSLSocket {
        val context = SSLContext.getInstance("TLS").apply {
            init(arrayOf(FixedKeyManager(identity)), arrayOf(trust), null)
        }
        val raw = Socket().apply {
            tcpNoDelay = true // key presses are tiny, send them immediately
            keepAlive = true
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        socket.soTimeout = CONNECT_TIMEOUT_MS * 2
        socket.startHandshake()
        return socket
    }
}
