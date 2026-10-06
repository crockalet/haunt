package io.github.crockalet.haunt.cli

import io.github.crockalet.haunt.cli.adb.Adb
import io.github.crockalet.haunt.cli.adb.AdbDevice
import io.github.crockalet.haunt.cli.adb.AdbLocator
import io.github.crockalet.haunt.cli.adb.ProcessResult
import io.github.crockalet.haunt.cli.adb.ProcessRunner
import io.github.crockalet.haunt.protocol.Protocol
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbTest {
    @Test
    fun parsesDevicesList() {
        val out = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1
            R58M12ABCDE            unauthorized usb:1-1 transport_id:3
            192.168.1.20:5555      offline transport_id:4
            adb-R58M-abc._adb-tls-connect._tcp device product:p model:SM_S918B device:dm3q transport_id:5

        """.trimIndent()
        val devices = Adb.parseDevices(out)
        assertEquals(4, devices.size)
        assertEquals(AdbDevice("emulator-5554", "device", "sdk_gphone64_x86_64", "sdk_gphone64_x86_64", "emu64xa", "1"), devices[0])
        assertEquals("unauthorized", devices[1].state)
        assertNull(devices[1].model)
        assertEquals("offline", devices[2].state)
        assertEquals("SM_S918B (adb-R58M-abc._adb-tls-connect._tcp)", devices[3].displayName)
        assertEquals(emptyList(), Adb.parseDevices("List of devices attached\n\n"))
    }

    @Test
    fun parsesForwardPort() {
        assertEquals(38123, Adb.parseForwardPort("38123\n"))
        assertNull(Adb.parseForwardPort(""))
        assertNull(Adb.parseForwardPort("error: something"))
    }

    private val ready = AdbDevice("a", "device")
    private val ready2 = AdbDevice("b", "device")
    private val unauthorized = AdbDevice("c", "unauthorized")

    private fun failure(block: () -> Unit) = assertFailsWith<HauntFailure> { block() }.also {
        assertEquals(ExitCode.NOT_CONNECTED, it.exit)
    }

    @Test
    fun selectsDevice() {
        assertEquals(ready, Adb.select(listOf(ready, unauthorized), null))
        assertEquals(ready2, Adb.select(listOf(ready, ready2), "b"))
        assertTrue("No Android device" in failure { Adb.select(emptyList(), null) }.message)
        val several = failure { Adb.select(listOf(ready, ready2), null) }
        assertTrue("-s" in several.hint!!, several.hint)
        assertTrue("USB debugging" in failure { Adb.select(listOf(unauthorized), null) }.hint!!)
        assertTrue("USB debugging" in failure { Adb.select(listOf(unauthorized), "c") }.hint!!)
        assertTrue("Attached: a" in failure { Adb.select(listOf(ready), "zzz") }.hint!!)
    }

    @Test
    fun locatesAdb() {
        val sep = File.pathSeparator
        val existing = setOf(File("/opt/sdk/platform-tools/adb"), File("/usr/bin/adb"))
        fun locator(env: Map<String, String>) = AdbLocator(env, { it in existing }, home = "/home/me", windows = false)

        assertEquals("/opt/sdk/platform-tools/adb", locator(mapOf("ANDROID_HOME" to "/opt/sdk", "PATH" to "/usr/bin")).locate())
        assertEquals("/usr/bin/adb", locator(mapOf("ANDROID_HOME" to "/nope", "PATH" to "/bin$sep/usr/bin")).locate())
        assertEquals("/opt/sdk/platform-tools/adb", locator(mapOf("ANDROID_SDK_ROOT" to "/opt/sdk")).locate())
        assertEquals("/usr/bin/adb", locator(emptyMap()).locate("/usr/bin/adb"))
        val missing = assertFailsWith<HauntFailure> { locator(emptyMap()).locate() }
        assertTrue("--adb" in missing.hint!!)
        assertFailsWith<HauntFailure> { locator(emptyMap()).locate("/bad/adb") }
        assertTrue(File("/home/me/Android/Sdk/platform-tools/adb") in locator(emptyMap()).candidates())
    }

    @Test
    fun buildsAdbCommandLines() {
        val seen = mutableListOf<List<String>>()
        val adb = Adb("/sdk/adb", ProcessRunner { cmd, _ -> seen += cmd; ProcessResult(0, "4242\n", "") })
        assertEquals(4242, adb.forward("emu"))
        adb.removeForward("emu", 4242)
        adb.startControlService("emu")
        assertEquals(
            listOf(
                listOf("/sdk/adb", "-s", "emu", "forward", "tcp:0", "localabstract:haunt"),
                listOf("/sdk/adb", "-s", "emu", "forward", "--remove", "tcp:4242"),
                listOf("/sdk/adb", "-s", "emu", "shell", "am", "start-foreground-service", "-n", Protocol.CONTROL_SERVICE_COMPONENT),
            ),
            seen,
        )
        assertEquals("io.github.crockalet.haunt/.android.ControlService", Protocol.CONTROL_SERVICE_COMPONENT)
    }

    @Test
    fun reportsAdbFailures() {
        val notInstalled = Adb("adb", ProcessRunner { _, _ -> ProcessResult(0, "Error: Not found; no service started.", "") })
        assertTrue("not installed" in assertFailsWith<HauntFailure> { notInstalled.startControlService("emu") }.message)

        val broken = Adb("adb", ProcessRunner { _, _ -> ProcessResult(1, "", "error: device offline") })
        val e = assertFailsWith<HauntFailure> { broken.devices() }
        assertTrue("device offline" in e.message, e.message)

        val missing = Adb("adb", ProcessRunner { _, _ -> throw IOException("No such file") })
        assertEquals(ExitCode.NOT_CONNECTED, assertFailsWith<HauntFailure> { missing.devices() }.exit)
    }
}
