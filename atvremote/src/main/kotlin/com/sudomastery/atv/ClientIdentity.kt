package com.sudomastery.atv

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The RSA key and self-signed certificate this remote presents to the TV.
 *
 * The TV remembers the certificate after pairing, so the identity must be generated once and
 * persisted (see [privateKeyEncoded] and [certificateEncoded]).
 */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    val privateKeyEncoded: ByteArray get() = privateKey.encoded
    val certificateEncoded: ByteArray get() = certificate.encoded

    companion object {
        /** Creates a new 2048-bit RSA identity. Takes a few hundred ms on a phone, run it off the main thread. */
        fun generate(commonName: String = "atvremote"): ClientIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val certDer = SelfSignedCertificate.build(commonName, keyPair.public.encoded, keyPair.private)
            return ClientIdentity(keyPair.private, parseCertificate(certDer))
        }

        fun fromEncoded(privateKeyPkcs8: ByteArray, certificateDer: ByteArray): ClientIdentity {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8))
            return ClientIdentity(key, parseCertificate(certificateDer))
        }

        internal fun parseCertificate(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/**
 * Minimal X.509 v3 certificate writer. Avoids pulling in BouncyCastle for the one certificate
 * the app ever needs.
 */
internal object SelfSignedCertificate {
    private const val OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000

    fun build(commonName: String, subjectPublicKeyInfo: ByteArray, signingKey: PrivateKey): ByteArray {
        val algorithm = seq(oid(OID_SHA256_WITH_RSA), byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(oid(OID_COMMON_NAME), tlv(0x0C, commonName.toByteArray()))))
        val now = System.currentTimeMillis()
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val basicConstraints = seq(
            oid(OID_BASIC_CONSTRAINTS),
            tlv(0x04, seq(byteArrayOf(0x01, 0x01, 0xFF.toByte()))),
        )
        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))), // version: v3
            integer(serial),
            algorithm,
            name,
            seq(time(Date(now - 24 * 60 * 60 * 1000)), time(Date(now + TEN_YEARS_MS))),
            name,
            subjectPublicKeyInfo,
            tlv(0xA3, seq(basicConstraints)),
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signingKey)
            update(tbs)
            sign()
        }
        return seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        if (len < 0x80) {
            out.write(len)
        } else {
            val lenBytes = BigInteger.valueOf(len.toLong()).toByteArray().dropWhile { it == 0.toByte() }
            out.write(0x80 or lenBytes.size)
            lenBytes.forEach { out.write(it.toInt()) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.reduce { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.reduce { a, b -> a + b })
    private fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val chunks = ArrayList<Int>()
            var v = arc
            do {
                chunks.add((v and 0x7F).toInt())
                v = v shr 7
            } while (v > 0)
            for (i in chunks.indices.reversed()) out.write(chunks[i] or if (i > 0) 0x80 else 0)
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun time(date: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = date }.get(java.util.Calendar.YEAR)
        // RFC 5280: UTCTime through 2049, GeneralizedTime from 2050.
        val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val text = SimpleDateFormat(pattern, Locale.US).apply { timeZone = utc }.format(date)
        return tlv(tag, text.toByteArray())
    }
}
