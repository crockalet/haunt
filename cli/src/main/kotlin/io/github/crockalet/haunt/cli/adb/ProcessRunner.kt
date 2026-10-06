package io.github.crockalet.haunt.cli.adb

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Runs external commands. Abstracted so adb handling can be unit-tested without a device. */
fun interface ProcessRunner {
    /** @throws IOException if the executable can't be started (e.g. not found) or times out. */
    fun run(command: List<String>, timeout: Duration): ProcessResult
}

/** Real implementation backed by [ProcessBuilder]. */
object SystemProcessRunner : ProcessRunner {
    override fun run(command: List<String>, timeout: Duration): ProcessResult {
        val process = ProcessBuilder(command).start()
        process.outputStream.close()
        // Read incrementally: when `adb` spawns its server daemon, the daemon may inherit our pipes and
        // keep them open, so we can't wait for EOF — only for the process itself to exit.
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val readers = listOf(pump(process.inputStream, stdout), pump(process.errorStream, stderr))
        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IOException("'${command.joinToString(" ")}' timed out after $timeout")
        }
        readers.forEach { it.join(500) }
        return ProcessResult(process.exitValue(), stdout.toString(), stderr.toString())
    }

    private fun pump(input: InputStream, into: StringBuffer) = thread(isDaemon = true) {
        val buffer = CharArray(4096)
        input.bufferedReader().use { reader ->
            while (true) {
                val n = try {
                    reader.read(buffer)
                } catch (_: IOException) {
                    -1
                }
                if (n < 0) break
                into.append(buffer, 0, n)
            }
        }
    }
}
