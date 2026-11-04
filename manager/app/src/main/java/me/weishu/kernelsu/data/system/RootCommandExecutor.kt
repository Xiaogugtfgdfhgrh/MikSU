package me.weishu.kernelsu.data.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.script.ScriptCommand
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File

class RootCommandExecutor : CommandExecutor {
    override suspend fun execute(arguments: List<String>): CommandOutput = withContext(Dispatchers.IO) {
        val shell = getRootShell(true)
        if (!shell.isRoot) throw BackendException("root_unavailable", "Root access is required")
        val daemon = File(ksuApp.applicationInfo.nativeLibraryDir, "libksud.so")
        val command = (listOf(daemon.absolutePath) + arguments).joinToString(" ", transform = ScriptCommand::quote)
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val result = shell.newJob().add(command).to(stdout, stderr).exec()
        CommandOutput(result.code, stdout.joinToString("\n"), stderr.joinToString("\n"))
    }
}

object SystemBackends {
    val executor: CommandExecutor by lazy { RootCommandExecutor() }
    val kpm: KpmRepository by lazy { KpmRepository(executor) }
}
