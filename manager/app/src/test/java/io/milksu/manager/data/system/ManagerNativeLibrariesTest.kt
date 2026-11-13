package io.milksu.manager.data.system

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ManagerNativeLibrariesTest {
    @Test
    fun randomLibrarySetsAreCompleteDistinctAndHaveNoReservedFragments() {
        val sets = List(256) { ManagerNativeLibraries.randomNames() }
        assertEquals(sets.size, sets.toSet().size)
        for (names in sets) {
            assertEquals(ManagerNativeLibraries.originalNames, names.keys)
            assertEquals(3, names.values.toSet().size)
            names.forEach { (original, hidden) ->
                assertEquals(original.length, hidden.length)
                assertTrue(Regex("lib[a-z]+\\.so").matches(hidden))
                assertTrue(ManagerAppearance.isHiddenTextAllowed(hidden))
                assertFalse(hidden in ManagerNativeLibraries.originalNames)
            }
        }
    }

    @Test
    fun mappingRoundTripsAndLegacyApksKeepTheirOriginalNames() {
        val names = ManagerNativeLibraries.randomNames()
        withApk(ManagerNativeLibraries.encode(names)) { assertEquals(names, ManagerNativeLibraries.read(it)) }
        withApk(null) { apk ->
            val original = ManagerNativeLibraries.originalNames.associateWith { it }
            assertEquals(original, ManagerNativeLibraries.read(apk))
        }
    }

    @Test
    fun unsafeIncompleteAndUnknownMappingsAreRejected() {
        for (data in listOf(
            "version=2\nbridge=libabcdefgh.so\ndaemon=libabcd.so\nadb=libabcdefg.so\n",
            "version=1\nbridge=libabcdefgh.so\ndaemon=../../file\nadb=libabcdefg.so\n",
            "version=1\nbridge=libabcdefgh.so\ndaemon=libabcd.so\n",
            "version=1\nbridge=libabcdefgh.so\ndaemon=libabcd.so\nadb=libabcdefg.so\nextra=value\n",
            "version=1\nbridge=libkernelsu.so\ndaemon=libabcd.so\nadb=libabcdefg.so\n",
        )) {
            withApk(data.toByteArray()) { apk ->
                assertThrows(IllegalArgumentException::class.java) { ManagerNativeLibraries.read(apk) }
            }
        }
    }

    @Test
    fun elfSonameAndDependenciesChangeWithoutTouchingCodeOrOffsets() {
        for (wide in listOf(false, true)) {
            val input = elf(wide)
            val replacements = mapOf("libkernelsu.so" to "libabcdefgh.so", "libadbroot.so" to "libabcdefg.so")
            val renamed = ManagerElfLibraries.rename(input, replacements)
            val expected = input.copyOf()
            replacements.forEach { (old, replacement) ->
                val at = input.indexOfBytes(old.toByteArray())
                replacement.toByteArray().copyInto(expected, at)
            }
            assertArrayEquals(expected, renamed)
            assertArrayEquals(input, ManagerElfLibraries.rename(renamed, replacements.entries.associate { it.value to it.key }))
            assertEquals(input.size, renamed.size)
        }
    }

    @Test
    fun malformedElfRangesAndDifferentLengthReplacementsAreRejected() {
        val input = elf(true)
        assertThrows(IllegalArgumentException::class.java) {
            ManagerElfLibraries.rename(input.copyOf(170), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManagerElfLibraries.rename(input, mapOf("libkernelsu.so" to "libshort.so"))
        }
        val badStrings = input.copyOf()
        ByteBuffer.wrap(badStrings).order(ByteOrder.LITTLE_ENDIAN).putLong(192, Long.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) {
            ManagerElfLibraries.rename(badStrings, emptyMap())
        }
        val unterminated = input.copyOf().also { it[it.lastIndex] = 1 }
        ByteBuffer.wrap(unterminated).order(ByteOrder.LITTLE_ENDIAN).putLong(240, (input.size - 257).toLong())
        assertThrows(IllegalArgumentException::class.java) {
            ManagerElfLibraries.rename(unterminated, emptyMap())
        }
    }

    private fun withApk(metadata: ByteArray?, check: (ZipFile) -> Unit) {
        val directory = Files.createTempDirectory("native-library-map").toFile()
        try {
            val path = File(directory, "input.apk")
            ZipOutputStream(path.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(metadata?.let { ManagerNativeLibraries.METADATA } ?: "placeholder"))
                zip.write(metadata ?: byteArrayOf(0))
                zip.closeEntry()
            }
            ZipFile(path).use(check)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun ByteArray.indexOfBytes(value: ByteArray): Int =
        (0..size - value.size).first { index -> value.indices.all { this[index + it] == value[it] } }

    private fun elf(wide: Boolean): ByteArray {
        val strings = "\u0000libkernelsu.so\u0000libadbroot.so\u0000libc.so\u0000".toByteArray()
        val bytes = ByteArray(256 + strings.size)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put(byteArrayOf(0x7f, 69, 76, 70, if (wide) 2 else 1, 1, 1))
        val entrySize = if (wide) 16 else 8
        if (wide) {
            data.putLong(32, 64); data.putShort(54, 56); data.putShort(56, 2)
            data.putInt(64, 1); data.putLong(72, 0); data.putLong(80, 0x4000); data.putLong(96, bytes.size.toLong())
            data.putInt(120, 2); data.putLong(128, 176); data.putLong(152, (entrySize * 5).toLong())
        } else {
            data.putInt(28, 52); data.putShort(42, 32); data.putShort(44, 2)
            data.putInt(52, 1); data.putInt(56, 0); data.putInt(60, 0x4000); data.putInt(68, bytes.size)
            data.putInt(84, 2); data.putInt(88, 176); data.putInt(100, entrySize * 5)
        }
        data.position(176)
        for ((tag, value) in listOf(5 to 0x4100, 10 to strings.size, 14 to 1, 1 to 16, 0 to 0)) {
            if (wide) { data.putLong(tag.toLong()); data.putLong(value.toLong()) }
            else { data.putInt(tag); data.putInt(value) }
        }
        strings.copyInto(bytes, 256)
        return bytes
    }
}
