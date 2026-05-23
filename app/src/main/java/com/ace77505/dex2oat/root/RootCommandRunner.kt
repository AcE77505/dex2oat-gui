package com.ace77505.dex2oat.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CommandResult(
    val code: Int,
    val stdout: List<String>,
    val stderr: List<String>
) {
    val isSuccess: Boolean = code == 0
    val output: String = (stdout + stderr).joinToString("\n")
}

class RootCommandRunner {
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        Shell.getShell().isRoot
    }

    suspend fun run(command: String): CommandResult = withContext(Dispatchers.IO) {
        val result = Shell.cmd(command).exec()
        CommandResult(result.code, result.out, result.err)
    }

    suspend fun run(commands: List<String>): CommandResult = withContext(Dispatchers.IO) {
        val result = Shell.cmd(*commands.toTypedArray()).exec()
        CommandResult(result.code, result.out, result.err)
    }
}
