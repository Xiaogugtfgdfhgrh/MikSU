package io.milksu.manager.data.system

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.security.SecureRandom
import java.util.Properties
import java.util.zip.ZipFile

object ManagerNativeLibraries {
    const val BRIDGE = "libkernelsu.so"
    const val DAEMON = "libksud.so"
    const val ADB_ROOT = "libadbroot.so"
    const val METADATA = "assets/.native-libraries"
    private val roles = linkedMapOf("bridge" to BRIDGE, "daemon" to DAEMON, "adb" to ADB_ROOT)
    val originalNames: Set<String> = roles.values.toSet()

    fun randomNames(random: SecureRandom = SecureRandom(), reserved: Set<String> = emptySet()): Map<String, String> {
        val used = (reserved + originalNames).toMutableSet()
        return roles.values.associateWith { original ->
            // Equal lengths let the ELF string table retain all offsets.
            generateSequence {
                "lib" + CharArray(original.length - 6) { ('a'.code + random.nextInt(26)).toChar() }.concatToString() + ".so"
            }.first { ManagerAppearance.isHiddenTextAllowed(it) && used.add(it) }
        }
    }

    fun read(apk: ZipFile): Map<String, String> {
        val entry = apk.getEntry(METADATA) ?: return originalNames.associateWith { it }
        require(entry.size in 1..1024) { "Invalid native library mapping size" }
        val properties = Properties()
        DataInputStream(apk.getInputStream(entry)).use { stream ->
            val data = ByteArray(entry.size.toInt())
            stream.readFully(data)
            require(stream.read() == -1)
            properties.load(data.inputStream())
        }
        require(properties.stringPropertyNames() == roles.keys + "version" &&
            properties.getProperty("version") == "1") { "Invalid native library mapping" }
        return roles.map { (role, original) -> original to properties.getProperty(role) }.toMap().also(::validate)
    }

    fun encode(names: Map<String, String>): ByteArray {
        validate(names)
        val properties = Properties().apply {
            setProperty("version", "1")
            roles.forEach { (role, original) -> setProperty(role, names.getValue(original)) }
        }
        return ByteArrayOutputStream().apply { properties.store(this, null) }.toByteArray()
    }

    private fun validate(names: Map<String, String>) {
        require(names.keys == originalNames && names.values.toSet().size == roles.size)
        names.forEach { (original, name) ->
            require(name.length == original.length && Regex("lib[a-z]+\\.so").matches(name) &&
                name !in originalNames && ManagerAppearance.isHiddenTextAllowed(name)) {
                "Invalid native library name"
            }
        }
    }
}
