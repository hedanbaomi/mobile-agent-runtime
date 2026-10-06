// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import runtime.mobileagent.bridge.BridgeProtocol
import runtime.mobileagent.bridge.BridgeEncoding

sealed interface BridgeCliCommand {
    val adbPath: Path?

    data class Doctor(override val adbPath: Path) : BridgeCliCommand
    data class Devices(override val adbPath: Path) : BridgeCliCommand
    data class Pair(
        override val adbPath: Path,
        val serial: String,
        val desktopId: String?,
        val trustDirectory: Path,
    ) : BridgeCliCommand
    data class Run(
        override val adbPath: Path,
        val serial: String,
        val desktopId: String?,
        val appInstanceId: String,
        val trustDirectory: Path,
    ) : BridgeCliCommand
    data class Status(
        override val adbPath: Path,
        val serial: String,
        val desktopId: String?,
        val appInstanceId: String,
        val trustDirectory: Path,
    ) : BridgeCliCommand
    data class Forget(
        override val adbPath: Path,
        val serial: String,
        val desktopId: String?,
        val appInstanceId: String,
        val trustDirectory: Path,
    ) : BridgeCliCommand

    /** Interactive Windows USB setup; absent --adb is requested through stdin. */
    data class Quickstart(
        override val adbPath: Path?,
        val trustDirectory: Path,
    ) : BridgeCliCommand

    /** One-time Windows activation of the Android app's resident ADB service. */
    data class Activate(override val adbPath: Path?) : BridgeCliCommand
}

object BridgeCliParser {
    fun parse(args: Array<String>): BridgeCliCommand {
        require(args.isNotEmpty()) { usage() }
        val command = args.first()
        val values = StrictArgs(args.drop(1))
        if (command == "quickstart") {
            val adbPath = values.optional("--adb")?.let { Path.of(it) }
            require(adbPath == null || adbPath.isAbsolute) { "--adb must be an absolute path" }
            val trustDirectory = Path.of(values.optional("--trust-dir") ?: defaultTrustDirectory())
            require(trustDirectory.isAbsolute) { "--trust-dir must be an absolute path" }
            values.finish()
            return BridgeCliCommand.Quickstart(adbPath, trustDirectory)
        }
        if (command == "activate") {
            val adbPath = values.optional("--adb")?.let { Path.of(it) }
            require(adbPath == null || adbPath.isAbsolute) { "--adb must be an absolute path" }
            values.finish()
            return BridgeCliCommand.Activate(adbPath)
        }
        val adbPath = Path.of(values.required("--adb"))
        require(adbPath.isAbsolute) { "--adb must be an absolute path" }
        val serialRequired = command in setOf("pair", "run", "status", "forget")
        if (command !in setOf("doctor", "devices", "pair", "run", "status", "forget")) {
            throw IllegalArgumentException("unknown or forbidden command: $command")
        }
        val serial = if (serialRequired) values.required("--serial") else values.optional("--serial")
        if (!serial.isNullOrBlank()) {
            require(serial.none { it.isWhitespace() || it.code < 0x20 || it == '\u007f' }) {
                "--serial is invalid"
            }
        }
        // Desktop identity is generated/loaded from the protected identity
        // store. An optional value is only a legacy pin and never a default.
        val desktopId = values.optional("--desktop-id")
        val appInstanceId = values.optional("--app-instance-id")
        if (command in setOf("run", "status", "forget")) {
            require(!appInstanceId.isNullOrBlank()) {
                "--app-instance-id is required for $command"
            }
        }
        val trustDirectory = Path.of(values.optional("--trust-dir") ?: defaultTrustDirectory())
        require(trustDirectory.isAbsolute) { "--trust-dir must be an absolute path" }
        values.finish()
        return when (command) {
            "doctor" -> BridgeCliCommand.Doctor(adbPath)
            "devices" -> BridgeCliCommand.Devices(adbPath)
            "pair" -> BridgeCliCommand.Pair(adbPath, serial!!, desktopId, trustDirectory)
            "run" -> BridgeCliCommand.Run(adbPath, serial!!, desktopId, appInstanceId!!, trustDirectory)
            "status" -> BridgeCliCommand.Status(adbPath, serial!!, desktopId, appInstanceId!!, trustDirectory)
            "forget" -> BridgeCliCommand.Forget(adbPath, serial!!, desktopId, appInstanceId!!, trustDirectory)
            else -> error("unreachable")
        }
    }

    private fun defaultTrustDirectory(): String =
        Path.of(System.getProperty("user.home"), ".mobile-agent-runtime", "bridge-trust").toString()

    private fun usage(): Nothing = throw IllegalArgumentException(
        "usage: mar-bridge activate [--adb <absolute adb.exe>] | mar-bridge <doctor|devices|pair|run|status|forget> --adb <absolute adb.exe> [--serial <serial>] [--app-instance-id <id>] | mar-bridge quickstart [--adb <absolute adb.exe>] [--trust-dir <absolute path>]",
    )
}

private class StrictArgs(private val args: List<String>) {
    private val consumed = BooleanArray(args.size)

    fun required(name: String): String = optional(name) ?: throw IllegalArgumentException("missing $name")

    fun optional(name: String): String? {
        val index = args.indexOf(name)
        if (index < 0) return null
        require(index + 1 < args.size && !args[index + 1].startsWith("--")) { "$name requires a value" }
        require(!consumed[index] && !consumed[index + 1]) { "$name was repeated" }
        consumed[index] = true
        consumed[index + 1] = true
        return args[index + 1]
    }

    fun optionalFlag(name: String): Boolean {
        val index = args.indexOf(name)
        if (index < 0) return false
        require(!consumed[index]) { "$name was repeated" }
        consumed[index] = true
        return true
    }

    fun finish() {
        args.forEachIndexed { index, value ->
            require(consumed[index]) { "unknown option: $value" }
        }
    }
}

/** Minimal foreground CLI; run remains attached until Ctrl-C and never kills adb-server. */
fun main(args: Array<String>) {
    val command = BridgeCliParser.parse(args)
    when (command) {
        is BridgeCliCommand.Doctor -> {
            ProcessBuilderRunner().use { runner ->
                val configuration = AdbConfiguration.create(command.adbPath, "doctor", 38_765, 38_766)
                val report = AdbDoctor(configuration, runner).inspect()
                println("adb=${report.canonicalPath} version=${report.versionOutput.lineSequence().firstOrNull().orEmpty()} sha256=${report.sha256Hex} signatureVerified=${report.signatureVerified}")
            }
        }
        is BridgeCliCommand.Devices -> {
            ProcessBuilderRunner().use { runner ->
                val configuration = AdbConfiguration.create(command.adbPath, "devices", 38_765, 38_766)
                val report = AdbDoctor(configuration, runner).inspect()
                val result = AdbProcessManager.validated(configuration, runner, report).devices()
                require(result.process.outcome == ProcessOutcome.COMPLETE && result.process.exitCode == 0)
                AdbDevicesParser.parse(result.process.stdout.toUtf8Strict()).forEach {
                    println("${it.serial}\t${it.state}")
                }
            }
        }
        is BridgeCliCommand.Pair -> runPair(command)
        is BridgeCliCommand.Run -> runCompanion(command)
        is BridgeCliCommand.Status -> runStatus(command)
        is BridgeCliCommand.Forget -> runForget(command)
        is BridgeCliCommand.Quickstart -> runQuickstart(command)
        is BridgeCliCommand.Activate -> runActivate(command)
    }
}

private fun runPair(command: BridgeCliCommand.Pair) {
    ProcessBuilderRunner().use { runner ->
        val waiter = PairingWaiter()
        val companion = DesktopCompanion(
            command.adbPath,
            command.serial,
            command.desktopId,
            null,
            38_765,
            DpapiDesktopTrustStore(command.trustDirectory),
            runner,
            connectionHandler = waiter.handler(),
            desktopIdentityStore = DpapiDesktopIdentityStore(command.trustDirectory),
        )
        companion.use {
            val endpoint = it.start()
            println("pairing_endpoint=${endpoint.address}:${endpoint.port}")
            val pairingInput = readPairingToken()
            val token = pairingInput.token
            try {
                it.registerPairingToken(
                    token,
                    pairingInput.expiresAtMillis,
                )
            } finally {
                java.util.Arrays.fill(token, 0)
            }
            if (waiter.await()) println("pairing_complete=true") else println("pairing_complete=false timeout=true")
        }
    }
}

private fun runActivate(command: BridgeCliCommand.Activate) {
    require(com.sun.jna.Platform.isWindows()) {
        "Wired ADB activation requires Windows 10/11, Java 17+, and official Android platform-tools."
    }
    ProcessBuilderRunner().use { runner ->
        val adbPath = command.adbPath ?: readQuickstartAdbPath()
        val selectionConfiguration = AdbConfiguration.create(adbPath, "device-selection", 38_765, 38_766)
        val doctor = AdbDoctor(selectionConfiguration, runner).inspect()
        val selectionAdb = AdbProcessManager.validated(selectionConfiguration, runner, doctor)
        val listing = selectionAdb.devices().process
        require(listing.outcome == ProcessOutcome.COMPLETE && listing.exitCode == 0) {
            "adb devices failed. Check the official adb.exe path, USB cable, and USB driver."
        }
        val devices = AdbDevicesParser.parse(listing.stdout.toUtf8Strict())
        if (devices.isEmpty()) {
            throw IllegalArgumentException(
                "No USB devices were listed. Connect an unlocked phone with a data-capable USB cable, enable Developer options > USB debugging, and approve this PC's RSA prompt.",
            )
        }
        println("USB devices (type one exact serial; no device is selected automatically):")
        devices.forEach { println("${it.serial}\t${it.state}") }
        print("USB device serial: ")
        val selected = selectUsbActivationDevice(readLine(), devices)

        val configuration = AdbConfiguration.create(adbPath, selected.serial, 38_765, 38_766)
        val adb = AdbProcessManager.validated(configuration, runner, doctor)
        val currentUser = adb.currentAndroidUser().process
        require(currentUser.outcome == ProcessOutcome.COMPLETE && currentUser.exitCode == 0) {
            "Could not read the phone's active Android user. Unlock it, approve the USB debugging prompt, and retry."
        }
        val userId = ResidentAdbActivation.parseCurrentAndroidUser(currentUser.stdout)

        val token = readResidentActivationToken()
        val secret = ByteArray(ResidentAdbActivation.BOOTSTRAP_SECRET_BYTES)
        val frame = try {
            SecureRandom().nextBytes(secret)
            ResidentAdbActivation.encodeBootstrapFrame(token, secret)
        } finally {
            java.util.Arrays.fill(token, 0)
            java.util.Arrays.fill(secret, 0)
        }
        val activationDispatch = try {
            adb.startResidentDaemon(userId, frame).process
        } finally {
            java.util.Arrays.fill(frame, 0)
        }
        require(activationDispatch.outcome != ProcessOutcome.FAILED) {
            "adb did not dispatch resident activation. Keep the phone unlocked, reconnect USB, and start a fresh activation from Android."
        }
        if (activationDispatch.outcome == ProcessOutcome.UNKNOWN_OUTCOME) {
            println("Activation dispatch outcome is uncertain; checking the phone's provider state without replaying the token.")
        }
        println("等待手机完成配对。现在回到 Android 页面点“完成”；完成后回到此窗口按 Enter 检查 READY。")
        awaitAndroidCompletionAction()

        val deadlineNanos = System.nanoTime() + RESIDENT_READY_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadlineNanos) {
            val status = adb.residentStatus(userId).process
            val providerReady = status.outcome == ProcessOutcome.COMPLETE && status.exitCode == 0 &&
                runCatching { ResidentAdbActivation.providerReportsReady(status.stdout) }.getOrDefault(false)
            if (providerReady) {
                println("Android reported resident ADB READY. USB is no longer required; the service remains on the phone.")
                return
            }
            Thread.sleep(1_000)
        }
        throw IllegalStateException(
            "The phone did not report resident ADB READY within ${RESIDENT_READY_TIMEOUT_MS / 1_000} seconds. Do not reuse this token; start a fresh activation on Android and retry over USB.",
        )
    }
}

private fun runQuickstart(command: BridgeCliCommand.Quickstart) {
    require(com.sun.jna.Platform.isWindows()) {
        "Wired ADB quickstart requires Windows 10/11, Java 17+, and official Android platform-tools."
    }
    ProcessBuilderRunner().use { runner ->
        val adbPath = command.adbPath ?: readQuickstartAdbPath()
        // Validate the executable and publisher before querying attached devices.
        val selectionConfiguration = AdbConfiguration.create(adbPath, "device-selection", 38_765, 38_766)
        val doctor = AdbDoctor(selectionConfiguration, runner).inspect()
        val listing = AdbProcessManager.validated(selectionConfiguration, runner, doctor).devices().process
        require(listing.outcome == ProcessOutcome.COMPLETE && listing.exitCode == 0) {
            "adb devices failed. Check that the selected official adb.exe is current and that its USB driver is installed."
        }
        val devices = AdbDevicesParser.parse(listing.stdout.toUtf8Strict())
        if (devices.isEmpty()) {
            throw IllegalArgumentException(
                "No USB devices were listed. Connect an unlocked phone with a data-capable USB cable, enable Developer options > USB debugging, and approve this PC's RSA prompt.",
            )
        }
        println("ADB USB devices (type one exact serial; none is selected automatically):")
        devices.forEach { println("${it.serial}\t${it.state}") }
        print("Device serial: ")
        val selected = selectQuickstartDevice(readLine(), devices)

        lateinit var companion: DesktopCompanion
        val authenticated = AuthenticatedBridgeConnectionHandler(
            DesktopTypedBridgeRequestHandler(
                shell = { companion.shellExecutor() },
                typedFiles = { companion.typedFileExecutor() },
                workspaceBindingStore = DpapiDesktopWorkspaceBindingStore(command.trustDirectory),
            ),
        )
        val switchedHandler = PairingThenAuthenticatedConnectionHandler(authenticated)
        companion = DesktopCompanion(
            adbPath,
            selected.serial,
            null,
            null,
            38_765,
            DpapiDesktopTrustStore(command.trustDirectory),
            runner,
            connectionHandler = switchedHandler,
            desktopIdentityStore = DpapiDesktopIdentityStore(command.trustDirectory),
        )
        companion.use {
            val endpoint = it.start()
            println("USB bridge ready at ${endpoint.address}:${endpoint.port} for serial ${selected.serial}.")
            val pairingInput = readPairingToken()
            val token = pairingInput.token
            try {
                it.registerPairingToken(token, pairingInput.expiresAtMillis)
            } finally {
                java.util.Arrays.fill(token, 0)
            }
            println("等待手机完成配对。现在回到 Android 页面点“完成”；请保持此窗口打开。")
            if (!switchedHandler.awaitPairing(5 * 60 * 1_000L)) {
                println("pairing_complete=false timeout=true. Start quickstart again with a fresh Android pairing token.")
                return
            }
            println("pairing_complete=true bridge_running=true. Android trust is saved; keep this window open. Press Ctrl-C to stop.")
            CountDownLatch(1).await()
        }
    }
}

private fun readQuickstartAdbPath(): Path {
    print("Absolute path to official Android platform-tools adb.exe: ")
    val input = readLine()?.trim()?.removeSurrounding("\"")
        ?: throw IllegalArgumentException("adb.exe path input is required")
    require(input.isNotBlank()) { "adb.exe path input is required" }
    val path = Path.of(input)
    require(path.isAbsolute) { "Enter an absolute path to official adb.exe" }
    return path
}

private fun readResidentActivationToken(): ByteArray {
    val console = System.console() ?: throw IllegalStateException(
        "The activation token is read without echo. Launch start-wired-adb.bat in an interactive Windows console.",
    )
    val chars = console.readPassword("Enter the 64-hex activation token from Android (input is hidden): ")
        ?: throw IllegalArgumentException("activation token input is required")
    return try {
        ResidentAdbActivation.decodeActivationTokenHex(chars)
    } finally {
        java.util.Arrays.fill(chars, '\u0000')
    }
}

private fun awaitAndroidCompletionAction() {
    val console = System.console() ?: throw IllegalStateException(
        "The activation window is not interactive. Relaunch start-wired-adb.bat in a Windows console.",
    )
    console.readLine("After tapping Complete on Android, press Enter here to check provider READY: ")
        ?: throw IllegalArgumentException("activation confirmation input is required")
}

internal fun selectQuickstartDevice(input: String?, devices: List<AdbDevice>): AdbDevice {
    require(!input.isNullOrBlank()) { "Type the exact serial of the USB device to select." }
    val serial = input.trim()
    val device = devices.singleOrNull { it.serial == serial }
        ?: throw IllegalArgumentException("That serial is not in the device list. Type one listed serial exactly.")
    when (device.state) {
        AdbDeviceState.DEVICE -> Unit
        AdbDeviceState.UNAUTHORIZED -> throw IllegalArgumentException(
            "The selected device is unauthorized. Unlock the phone and approve its USB debugging RSA prompt, then start quickstart again.",
        )
        AdbDeviceState.OFFLINE -> throw IllegalArgumentException(
            "The selected device is offline. Reconnect the USB data cable, keep the phone unlocked, and start quickstart again.",
        )
        else -> throw IllegalArgumentException(
            "The selected device is ${device.state}. Use an online USB device in the 'device' state.",
        )
    }
    return AdbDevicesParser.selectExplicit(devices, serial)
}

internal fun selectUsbActivationDevice(input: String?, devices: List<AdbDevice>): AdbDevice {
    val selected = selectQuickstartDevice(input, devices)
    require(!selected.serial.startsWith("emulator-", ignoreCase = true)) {
        "Emulator transports are not supported. Connect the physical phone over USB and select its serial."
    }
    require(':' !in selected.serial) {
        "Wireless/network ADB transports are not supported. Connect the phone over USB and select its listed serial."
    }
    return selected
}

/** Reads only the Android foreground token; this path is never exposed to a bridge request. */
private data class PairingInput(
    val token: ByteArray,
    val expiresAtMillis: Long,
)

private fun readPairingToken(): PairingInput {
    val console = System.console()
    val chars = console?.readPassword("Enter the 64-hex pairing token shown by Android: ")
        ?: run {
            print("Enter the 64-hex pairing token shown by Android: ")
            (readLine() ?: throw IllegalArgumentException("pairing token input is required")).toCharArray()
        }
    val expiryChars = (console?.readLine("Enter the Android token expiry epoch milliseconds: ")
        ?: run {
            print("Enter the Android token expiry epoch milliseconds: ")
            readLine() ?: throw IllegalArgumentException("pairing token expiry input is required")
        }).toCharArray()
    return try {
        val value = chars.concatToString()
        require(value.length == BridgeProtocol.TOKEN_BYTES * 2) { "pairing token must be exactly 64 hex characters" }
        require(value.all { it in "0123456789abcdefABCDEF" }) { "pairing token must be hexadecimal" }
        val expiresAt = expiryChars.concatToString().toLongOrNull()
            ?: throw IllegalArgumentException("pairing token expiry must be epoch milliseconds")
        PairingInput(BridgeEncoding.unhex(value), expiresAt)
    } finally {
        java.util.Arrays.fill(chars, '\u0000')
        java.util.Arrays.fill(expiryChars, '\u0000')
    }
}

private const val RESIDENT_READY_TIMEOUT_MS = 90_000L

private fun runCompanion(command: BridgeCliCommand.Run) {
    ProcessBuilderRunner().use { runner ->
        lateinit var companion: DesktopCompanion
        val authenticated = AuthenticatedBridgeConnectionHandler(
            DesktopTypedBridgeRequestHandler(
                shell = { companion.shellExecutor() },
                typedFiles = { companion.typedFileExecutor() },
                workspaceBindingStore = DpapiDesktopWorkspaceBindingStore(command.trustDirectory),
            ),
        )
        companion = DesktopCompanion(
            command.adbPath,
            command.serial,
            command.desktopId,
            command.appInstanceId,
            38_765,
            DpapiDesktopTrustStore(command.trustDirectory),
            runner,
            connectionHandler = authenticated,
            desktopIdentityStore = DpapiDesktopIdentityStore(command.trustDirectory),
        )
        companion.use {
            val endpoint = it.start()
            println("ready address=${endpoint.address} port=${endpoint.port}")
            CountDownLatch(1).await()
        }
    }
}

private fun runStatus(command: BridgeCliCommand.Status) {
    val identityStore = DpapiDesktopIdentityStore(command.trustDirectory)
    val desktopId = identityStore.loadOrCreate()
    command.desktopId?.let { require(it == desktopId) { "--desktop-id does not match the stored desktop identity" } }
    val store = DpapiDesktopTrustStore(command.trustDirectory)
    val identity = runtime.mobileagent.bridge.BridgeIdentity.forSerial(desktopId, command.appInstanceId, command.serial)
    val record = store.load(identity)
    try {
        println("trusted=${record != null} serialFingerprint=${BridgeEncoding.hex(identity.serialFingerprint)}")
    } finally {
        record?.close()
    }
}

private fun runForget(command: BridgeCliCommand.Forget) {
    val identityStore = DpapiDesktopIdentityStore(command.trustDirectory)
    val desktopId = identityStore.loadOrCreate()
    command.desktopId?.let { require(it == desktopId) { "--desktop-id does not match the stored desktop identity" } }
    DpapiDesktopTrustStore(command.trustDirectory).forget(
        runtime.mobileagent.bridge.BridgeIdentity.forSerial(desktopId, command.appInstanceId, command.serial),
    )
    println("forgot=true")
}
