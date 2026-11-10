package com.sudomastery.atv

import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientIdentityTest {
    private val identity = ClientIdentity.generate("unit-test")

    @Test
    fun `certificate is a valid self-signed v3 certificate`() {
        val cert = identity.certificate
        assertEquals(3, cert.version)
        assertEquals("CN=unit-test", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        cert.verify(cert.publicKey) // throws if the signature is wrong
        cert.checkValidity()
        assertEquals(2048, (cert.publicKey as RSAPublicKey).modulus.bitLength())
    }

    @Test
    fun `identity survives an encode and decode round trip`() {
        val restored = ClientIdentity.fromEncoded(identity.privateKeyEncoded, identity.certificateEncoded)
        assertContentEquals(identity.certificateEncoded, restored.certificateEncoded)
        val data = "ping".toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(restored.privateKey); update(data); sign() }
        assertTrue(Signature.getInstance("SHA256withRSA").run { initVerify(identity.certificate); update(data); verify(sig) })
    }

    @Test
    fun `pairing secret embeds its own first byte in the code`() {
        val tv = ClientIdentity.generate("tv").certificate
        val tail = "9F2D"
        val hash = PairingSession.computeSecret(identity.certificate, tv, "00$tail")
        val code = "%02X".format(hash[0]) + tail
        assertContentEquals(hash, PairingSession.computeSecret(identity.certificate, tv, code))
    }
}
