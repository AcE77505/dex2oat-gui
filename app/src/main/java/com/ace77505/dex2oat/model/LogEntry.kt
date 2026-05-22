package com.ace77505.dex2oat.model

import java.time.Instant

data class LogEntry(
    val message: String,
    val type: LogType,
    val timestamp: Instant = Instant.now()
)

enum class LogType {
    Info,
    Command,
    Error
}
