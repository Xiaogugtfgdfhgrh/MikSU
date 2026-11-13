package io.milksu.manager.data.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.SecureRandom
import java.util.Random

class ManagerApkTest {
    @Test
    fun generatedIdentityUsesThePlatformRsaImplementationAndPersists() {
        val directory = Files.createTempDirectory("manager-identity-test").toFile()
        try {
            val file = File(directory, "identity.p12")
            ManagerApk.createKey(file)
            val certificate = ManagerApk.certificate(file)
            certificate.checkValidity()
            certificate.verify(certificate.publicKey)
            assertTrue(certificate.encoded.size in 1..1024)
            val store = KeyStore.getInstance("PKCS12").apply {
                file.inputStream().use { load(it, charArrayOf()) }
            }
            val data = "manager-transfer-test".toByteArray()
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(store.getKey("manager", charArrayOf()) as PrivateKey)
                update(data)
            }.sign()
            assertTrue(Signature.getInstance("SHA256withRSA").run {
                initVerify(certificate)
                update(data)
                verify(signature)
            })
            assertEquals(ManagerApk.certificateHash(certificate),
                ManagerApk.certificateHash(ManagerApk.certificate(file)))
            assertThrows(IllegalArgumentException::class.java) { ManagerApk.createKey(file) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun randomPackagesUseReadableReverseDomainNamesWithHighDiversity() {
        val seed = Random(0x4d534849L)
        val random = object : SecureRandom() {
            override fun nextInt(bound: Int): Int = seed.nextInt(bound)
        }
        val packages = List(4096) { ManagerApk.randomPackage(random) }
        assertTrue(packages.toSet().size > 4000)
        assertTrue(packages.all(ManagerManifest::validPackage))
        assertTrue(packages.all(ManagerAppearance::isHiddenTextAllowed))
        assertTrue(packages.all { Regex("com\\.[a-z]+\\.[a-z]+").matches(it) })
        assertTrue(packages.map { it.split('.')[1] }.toSet().size > 900)
        assertTrue(packages.map { it.substringAfterLast('.') }.toSet().size > 900)
    }

    @Test
    fun readableWordsAreCombinedWithoutRandomCharacterSuffixes() {
        val draws = listOf(4, 2, 16, 16)
        val scripted = object : SecureRandom() {
            var consumed = 0
            override fun nextInt(bound: Int): Int =
                draws[consumed++].also { require(it in 0 until bound) }
        }
        assertEquals("com.cedarbrook.quicknotes", ManagerApk.randomPackage(scripted))
        assertEquals(draws.size, scripted.consumed)
    }

    @Test
    fun forbiddenFragmentsAcrossWordBoundariesAreRetried() {
        assertTrue(ManagerAppearance.isHiddenTextAllowed("atlas"))
        assertTrue(ManagerAppearance.isHiddenTextAllowed("upland"))
        val draws = listOf(1, 31, 0, 16, 4, 2, 16, 16)
        val scripted = object : SecureRandom() {
            var consumed = 0
            override fun nextInt(bound: Int): Int =
                draws[consumed++].also { require(it in 0 until bound) }
        }
        assertEquals("com.cedarbrook.quicknotes", ManagerApk.randomPackage(scripted))
        assertEquals(draws.size, scripted.consumed)
    }
}
