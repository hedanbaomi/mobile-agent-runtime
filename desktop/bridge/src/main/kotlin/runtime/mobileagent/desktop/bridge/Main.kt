// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import runtime.mobileagent.bridge.BridgeProtocol
import runtime.mobileagent.bridge.BridgeEncoding

sealed interface BridgeCliCommand {
    val adbPath: Path

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
}

object BridgeCliParser {
    fun parse(args: Array<String>): BridgeCliCommand {
        require(args.isNotEmpty()) { usage() }
        val command = args.first()
        val values = StrictArgs(args.drop(1))
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
        "usage: mar-bridge <doctor|devices|pair|run|status|forget> --adb <absolute adb.exe> [--serial <serial>] [--app-instance-id <id>]",
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
