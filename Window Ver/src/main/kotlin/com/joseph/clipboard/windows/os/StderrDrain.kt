package com.joseph.clipboard.windows.os

/**
 * Consume a helper process's stderr on a daemon thread, echoing lines to our own stderr.
 * Helpers launch with `redirectErrorStream(false)` so stdout stays parseable; without a
 * reader on the other end a chatty helper eventually fills the OS pipe buffer and blocks
 * forever inside its next stderr write (hanging the picker/hotspot/BLE helper mid-task).
 */
/** Launch with the stderr drain attached — use this instead of a bare start() for helpers. */
fun ProcessBuilder.startDrained(tag: String): Process = start().also { drainStderr(it, tag) }

fun drainStderr(process: Process, tag: String) {
    Thread {
        try {
            process.errorStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) System.err.println("$tag: $line")
            }
        } catch (_: Exception) {
        }
    }.apply { isDaemon = true; name = "$tag-stderr"; start() }
}
