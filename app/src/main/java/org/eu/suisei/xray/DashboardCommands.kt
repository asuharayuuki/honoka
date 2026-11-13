package org.eu.suisei.xray

internal object DashboardCommands {
    fun check(command: String): Int = try {
        CommandRunner.run(shell(command), 3000, 0).exitCode
    } catch (_: Exception) {
        -1
    }

    fun summary(command: String): String = try {
        val result = CommandRunner.run(shell(command), 3000, 200)
        if (result.timedOut) "" else result.output.trim().replace('\n', ' ')
    } catch (_: Exception) {
        ""
    }

    @Synchronized
    fun runAction(command: String): CommandRunner.Result =
        CommandRunner.run(shell(command), 10000, 2000)

    private fun shell(command: String): ProcessBuilder {
        val trimmed = command.trim()
        return if (trimmed.startsWith("su ") || trimmed == "su") {
            ProcessBuilder("/system/bin/sh", "-c", trimmed)
        } else {
            ProcessBuilder("su", "-c", trimmed)
        }
    }
}
