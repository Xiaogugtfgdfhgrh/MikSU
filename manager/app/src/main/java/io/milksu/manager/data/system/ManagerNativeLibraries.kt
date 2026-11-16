package io.milksu.manager.data.system

import java.io.DataInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.zip.ZipFile

object ManagerNativeLibraries {
    const val BRIDGE = "libkernelsu.so"
    const val DAEMON = "libksud.so"
    const val ADB_ROOT = "libadbroot.so"
    const val LEGACY_METADATA = "assets/.native-libraries"
    private val roles = linkedMapOf("bridge" to BRIDGE, "daemon" to DAEMON, "adb" to ADB_ROOT)
    private val seedPattern = Regex("[0-9a-f]{64}")
    val originalNames: Set<String> = roles.values.toSet()

    data class Layout(val seed: String, val names: Map<String, String>)

    fun randomLayout(random: SecureRandom = SecureRandom(), reserved: Set<String> = emptySet()): Layout =
        generateSequence {
            val seed = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            Layout(seed, namesFromSeed(seed))
        }.first { layout ->
            layout.names.values.all {
                it !in reserved && it !in originalNames && ManagerAppearance.isHiddenTextAllowed(it)
            } && layout.names.values.toSet().size == roles.size
        }

    private fun namesFromSeed(seed: String): Map<String, String> {
        require(seedPattern.matches(seed)) { "Invalid native library seed" }
        return originalNames.associateWith { original ->
            val hash = MessageDigest.getInstance("SHA-256")
                .digest("$seed\u0000$original".toByteArray(Charsets.US_ASCII))
            // Equal lengths retain the ELF string table's existing offsets.
            "lib" + hash.take(original.length - 6).map {
                ('a'.code + (it.toInt() and 0xff) % 26).toChar()
            }.joinToString("") + ".so"
        }
    }

    fun read(apk: ZipFile): Map<String, String> {
        // Older hidden installations and updates prepared by an older Manager use v1.
        val entry = apk.getEntry(LEGACY_METADATA) ?: return apk.comment
            ?.takeIf(seedPattern::matches)?.let(::namesFromSeed)?.also(::validate)
            ?: originalNames.associateWith { it }
        require(!entry.isDirectory && entry.size in 1..1024) { "Invalid native library mapping size" }
        val properties = object : Properties() {
            override fun put(key: Any, value: Any): Any? {
                require(!containsKey(key)) { "Duplicate native library mapping key" }
                return super.put(key, value)
            }
        }
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

    fun verifyArchive(apk: ZipFile, names: Map<String, String>) {
        val libraries = apk.entries().asSequence().filter { !it.isDirectory }.map { entry ->
            entry.name.split('/').let { parts ->
                if (parts.size == 3 && parts[0] == "lib" && parts[2].endsWith(".so")) parts[1] to entry else null
            }
        }.filterNotNull().groupBy({ it.first }, { it.second })
        require(libraries.isNotEmpty()) { "The Manager native library set is missing" }
        libraries.forEach { (_, entries) ->
            names.values.forEach { name ->
                require(entries.count { it.name.substringAfterLast('/') == name && it.size > 0 } == 1) {
                    "The Manager native library set is incomplete"
                }
            }
        }
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
