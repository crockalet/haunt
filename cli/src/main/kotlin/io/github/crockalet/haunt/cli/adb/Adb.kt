package io.github.crockalet.haunt.cli.adb

import io.github.crockalet.haunt.cli.HauntFailure
import io.github.crockalet.haunt.protocol.Protocol
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One line of `adb devices -l`. [state] is `device` when usable (else `unauthorized`, `offline`, …). */
@Serializable
data class AdbDevice(
    val serial: String,
    val state: String,
    val model: String? = null,
    val product: String? = null,
    val device: String? = null,
    val transportId: String? = null,
) {
    val ready: Boolean get() = state == "device"

    /** e.g. `Pixel_8 (emulator-5554)`. */
    val displayName: String get() = model?.let { "$it ($serial)" } ?: serial
}

/** Finds the adb executable: `--adb`, `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`, `PATH`, then usual SDK locations. */
class AdbLocator(
    private val env: Map<String, String> = System.getenv(),
    private val isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
    private val home: String = System.getProperty("user.home") ?: "",
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
) {
    private val exe get() = if (windows) "adb.exe" else "adb"

    /** Candidate paths in priority order (explicit path excluded). */
    fun candidates(): List<File> = buildList {
        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
            env[key]?.takeIf { it.isNotBlank() }?.let { add(File(File(it, "platform-tools"), exe)) }
        }
        env["PATH"].orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.forEach { add(File(it, exe)) }
        if (home.isNotEmpty()) {
            listOf("Android/Sdk", "Library/Android/sdk", "android-sdk").forEach { add(File(File(home, it), "platform-tools/$exe")) }
        }
        env["LOCALAPPDATA"]?.let { add(File(it, "Android/Sdk/platform-tools/$exe")) }
    }

    /** @throws HauntFailure (not connected) if adb can't be found. */
    fun locate(explicit: String? = null): String {
        if (explicit != null) {
            val f = File(explicit)
            if (!isExecutable(f)) throw HauntFailure.notConnected("adb not found at $explicit", "Pass the path to the adb executable with --adb.")
            return f.path
        }
        return candidates().firstOrNull(isExecutable)?.path ?: throw HauntFailure.notConnected(
            "adb not found",
            "Install Android SDK platform-tools and set ANDROID_HOME, add adb to PATH, or pass --adb /path/to/adb.",
        )
    }
}

/** Thin wrapper over the adb command line. */
class Adb(
    val path: String,
    private val runner: ProcessRunner = SystemProcessRunner,
    private val timeout: Duration = 20.seconds,
) {
    private fun adb(serial: String?, vararg args: String): ProcessResult {
        val command = buildList {
            add(path)
            if (serial != null) addAll(listOf("-s", serial))
            addAll(args)
        }
        return try {
            runner.run(command, timeout)
        } catch (e: IOException) {
            throw HauntFailure.notConnected("Failed to run adb: ${e.message}", "Check the adb path (--adb) and that adb works: `adb devices`.")
        }
    }

    private fun ProcessResult.orFail(what: String): ProcessResult {
        if (!ok) {
            val detail = (stderr.ifBlank { stdout }).trim().lineSequence().lastOrNull { it.isNotBlank() } ?: "exit $exitCode"
            throw HauntFailure.notConnected("adb $what failed: $detail")
        }
        return this
    }

    fun devices(): List<AdbDevice> = parseDevices(adb(null, "devices", "-l").orFail("devices").stdout)

    /** `adb forward tcp:0 localabstract:haunt` → the local TCP port adb picked. */
    fun forward(serial: String, socketName: String = Protocol.SOCKET_NAME): Int {
        val out = adb(serial, "forward", "tcp:0", "localabstract:$socketName").orFail("forward").stdout
        return parseForwardPort(out) ?: throw HauntFailure.notConnected("adb forward returned no port: ${out.trim()}")
    }

    fun removeForward(serial: String, port: Int) {
        runCatching { adb(serial, "forward", "--remove", "tcp:$port") }
    }

    /**
     * Starts Haunt's control service. Returns normally if `am` accepted it.
     * @throws HauntFailure if the app isn't installed or `am` refused.
     */
    fun startControlService(serial: String, component: String = Protocol.CONTROL_SERVICE_COMPONENT) {
        val result = adb(serial, "shell", "am", "start-foreground-service", "-n", component).orFail("shell am")
        val output = (result.stdout + "\n" + result.stderr).trim()
        if (output.lineSequence().any { it.startsWith("Error") || "Exception" in it }) {
            val notInstalled = "not found" in output.lowercase() || "does not exist" in output.lowercase()
            throw if (notInstalled) {
                HauntFailure.notConnected(
                    "Haunt is not installed on $serial",
                    "Install the Haunt app (package ${Protocol.APP_PACKAGE}) and open it once.",
                )
            } else {
                HauntFailure.notConnected("Could not start Haunt on $serial: ${output.lineSequence().first { it.isNotBlank() }}")
            }
        }
    }

    companion object {
        /** Parses `adb devices -l` output. Ignores the header and `* daemon …` lines. */
        fun parseDevices(output: String): List<AdbDevice> = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("List of devices") && !it.startsWith("adb server") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val props = parts.drop(2).mapNotNull { p ->
                    val i = p.indexOf(':')
                    if (i > 0) p.substring(0, i) to p.substring(i + 1) else null
                }.toMap()
                // Some states have a space ("no permissions (...)"); keep the first word.
                AdbDevice(
                    serial = parts[0],
                    state = parts[1],
                    model = props["model"],
                    product = props["product"],
                    device = props["device"],
                    transportId = props["transport_id"],
                )
            }
            .toList()

        fun parseForwardPort(output: String): Int? =
            output.lineSequence().map { it.trim() }.firstNotNullOfOrNull { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } }

        /**
         * Picks the device to talk to: [serial] if given, else the only ready device.
         * @throws HauntFailure (not connected) with an actionable hint otherwise.
         */
        fun select(devices: List<AdbDevice>, serial: String?): AdbDevice {
            if (serial != null) {
                val d = devices.firstOrNull { it.serial == serial } ?: throw HauntFailure.notConnected(
                    "Device $serial not found",
                    if (devices.isEmpty()) "No devices are attached." else "Attached: ${devices.joinToString { it.serial }}.",
                )
                if (!d.ready) throw notReady(d)
                return d
            }
            if (devices.isEmpty()) {
                throw HauntFailure.notConnected(
                    "No Android device connected",
                    "Connect a phone with USB debugging enabled (or `adb connect <ip>:<port>`), then run `haunt devices`.",
                )
            }
            val ready = devices.filter { it.ready }
            return when (ready.size) {
                1 -> ready.single()
                0 -> throw notReady(devices.first())
                else -> throw HauntFailure.notConnected(
                    "Several devices connected: ${ready.joinToString { it.serial }}",
                    "Choose one with -s <serial> (or set ANDROID_SERIAL).",
                )
            }
        }

        private fun notReady(d: AdbDevice) = HauntFailure.notConnected(
            "Device ${d.serial} is ${d.state}",
            when (d.state) {
                "unauthorized" -> "Unlock the phone and accept the \"Allow USB debugging\" prompt."
                "offline" -> "Reconnect the cable or run `adb kill-server`, then retry."
                "no" -> "adb lacks USB permissions; check your udev rules."
                else -> "Wait until `adb devices` shows it as \"device\"."
            },
        )
    }
}
