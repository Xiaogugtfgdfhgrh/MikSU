package io.milksu.manager

import android.content.pm.ApplicationInfo
import io.milksu.manager.data.system.ManagerNativeLibraries
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

object NativeLibraryLoader {
    private val mappings = ConcurrentHashMap<String, Map<String, String>>()

    @JvmStatic
    fun file(application: ApplicationInfo, original: String): File {
        require(original in ManagerNativeLibraries.originalNames)
        val names = mappings.getOrPut(application.sourceDir) {
            ZipFile(application.sourceDir).use(ManagerNativeLibraries::read)
        }
        return File(application.nativeLibraryDir, names.getValue(original))
    }

    @JvmStatic
    fun loadBridge(application: ApplicationInfo?) {
        try {
            if (application == null) {
                System.loadLibrary("kernelsu")
            } else {
                System.load(file(application, ManagerNativeLibraries.BRIDGE).absolutePath)
            }
        } catch (error: Exception) {
            throw UnsatisfiedLinkError("The native library mapping could not be loaded").apply { initCause(error) }
        }
    }
}
