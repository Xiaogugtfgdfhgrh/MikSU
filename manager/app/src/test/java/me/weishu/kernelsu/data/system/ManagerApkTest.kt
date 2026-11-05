package me.weishu.kernelsu.data.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

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
    fun randomPackagesAreValidDistinctAndHaveNoFixedNamespace() {
        val packages = List(128) { ManagerApk.randomPackage() }
        assertEquals(packages.size, packages.toSet().size)
        assertTrue(packages.all(ManagerManifest::validPackage))
        assertTrue(packages.map { it.substringBefore('.') }.toSet().size > 120)
    }
}
