package com.sudomastery.atv

import com.google.polo.wire.protobuf.PoloProto.Configuration
import com.google.polo.wire.protobuf.PoloProto.Options
import com.google.polo.wire.protobuf.PoloProto.OuterMessage
import com.google.polo.wire.protobuf.PoloProto.PairingRequest
import com.google.polo.wire.protobuf.PoloProto.Secret
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

class PairingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Code entered does not match the one on the TV screen. */
class WrongCodeException : IOException("That code doesn't match the one on the TV")

/**
 * Pairing handshake (the "polo" protocol) on port 6467.
 *
 * 1. [start] connects and negotiates; the TV then shows a 6 character hex code.
 * 2. [finish] proves we saw the code. On success the TV trusts our client certificate from then on.
 */
class PairingSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val clientName: String,
    private val port: Int = PAIRING_PORT,
) : Closeable {
    private var socket: SSLSocket? = null
    private val trust = PinningTrustManager(pinnedSha256 = null)

    suspend fun start() = withContext(Dispatchers.IO) {
        val s = Tls.connect(host, port, identity, trust).also { socket = it }
        s.soTimeout = RESPONSE_TIMEOUT_MS

        send(base().setPairingRequest(PairingRequest.newBuilder().setServiceName("atvremote").setClientName(clientName)))
        expect(OuterMessage::hasPairingRequestAck)

        val hex = Options.Encoding.newBuilder()
            .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(6)
        send(
            base().setOptions(
                Options.newBuilder().addInputEncodings(hex).setPreferredRole(Options.RoleType.ROLE_TYPE_INPUT),
            ),
        )
        expect(OuterMessage::hasOptions)

        send(base().setConfiguration(Configuration.newBuilder().setEncoding(hex).setClientRole(Options.RoleType.ROLE_TYPE_INPUT)))
        expect(OuterMessage::hasConfigurationAck)
        // The TV is now showing the code. Don't time out while the user reads it.
        s.soTimeout = 0
    }

    /** Returns the SHA-256 of the TV's certificate, to pin for later connections. */
    suspend fun finish(code: String): ByteArray = withContext(Dispatchers.IO) {
        val pin = code.trim().uppercase()
        require(pin.length == 6 && pin.all { it in '0'..'9' || it in 'A'..'F' }) { "The code is 6 characters, 0-9 and A-F" }
        val s = socket ?: throw PairingException("Pairing was not started")
        val serverCert = trust.serverCertificate ?: throw PairingException("No TV certificate")

        val secret = computeSecret(identity.certificate, serverCert, pin)
        // The first byte of the hash is encoded in the code itself, so a typo is caught locally.
        if (secret[0] != pin.substring(0, 2).toInt(16).toByte()) throw WrongCodeException()

        s.soTimeout = RESPONSE_TIMEOUT_MS
        send(base().setSecret(Secret.newBuilder().setSecret(ByteString.copyFrom(secret))))
        expect(OuterMessage::hasSecretAck)
        sha256(serverCert.encoded)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun base() = OuterMessage.newBuilder().setProtocolVersion(2).setStatus(OuterMessage.Status.STATUS_OK)

    private fun send(msg: OuterMessage.Builder) {
        val out = socket!!.outputStream
        msg.build().writeDelimitedTo(out)
        out.flush()
    }

    private fun expect(check: (OuterMessage) -> Boolean) {
        val msg = OuterMessage.parseDelimitedFrom(socket!!.inputStream)
            ?: throw PairingException("The TV closed the connection")
        when (msg.status) {
            OuterMessage.Status.STATUS_OK -> Unit
            OuterMessage.Status.STATUS_BAD_SECRET -> throw WrongCodeException()
            else -> throw PairingException("The TV rejected pairing (${msg.status})")
        }
        if (!check(msg)) throw PairingException("Unexpected reply from the TV")
    }

    companion object {
        const val PAIRING_PORT = 6467
        private const val RESPONSE_TIMEOUT_MS = 10_000

        internal fun computeSecret(client: X509Certificate, server: X509Certificate, pin: String): ByteArray {
            val c = client.publicKey as RSAPublicKey
            val s = server.publicKey as RSAPublicKey
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(c.modulus.unsignedBytes())
            digest.update(c.publicExponent.unsignedBytes())
            digest.update(s.modulus.unsignedBytes())
            digest.update(s.publicExponent.unsignedBytes())
            digest.update(pin.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            return digest.digest()
        }

        /** Big-endian magnitude without the sign byte Java adds. */
        private fun BigInteger.unsignedBytes(): ByteArray {
            val b = toByteArray()
            return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }
    }
}
