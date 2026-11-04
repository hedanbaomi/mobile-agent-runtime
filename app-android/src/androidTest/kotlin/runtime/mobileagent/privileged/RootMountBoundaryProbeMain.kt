// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.serialization.encodeToString

import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.PrivilegeTier
import runtime.mobileagent.domain.PrivilegedSessionIdentity
import runtime.mobileagent.domain.RootCallerObservation
import runtime.mobileagent.domain.RootControlPlaneBoundary
import runtime.mobileagent.domain.RootFdNodeIdentity
import runtime.mobileagent.domain.RootFileOperationResult
import runtime.mobileagent.domain.RootFileReadResult
import runtime.mobileagent.domain.RootTypedArguments
import runtime.mobileagent.domain.RootServiceSession
import runtime.mobileagent.domain.RootWorkspaceFileExecutor
import runtime.mobileagent.domain.RootWorkspaceRootKind

/**
 * UID0 mount-boundary probe (`U4`/`R13` follow-up).
 *
 * The JUnit instrumentation process runs as the app UID, so it can never observe real kernel mount
 * identities of the CE/DE private directories. This entry point runs the **production** boundary
 * (`RootMountBoundary.read` + `RootWorkspaceFileExecutor` over `AndroidOsPosixFdOperations`) from a
 * UID0 `app_process` started by the harness, and prints one `MOUNT_PROBE <name> PASS|FAIL` line per
 * scenario. It never mounts, formats, deletes or repairs anything: the harness owns the fixture and
 * this helper only reads, and writes `probe-output.txt` inside the given workspace root.
 *
 * Usage:
 * `app_process ... runtime.mobileagent.privileged.RootMountBoundaryProbeMain <ceDir> <deDir> <workspaceRoot> [basic]`
 *
 * The optional 4th argument `basic` runs the fixture-free subset only
 * (`fixture-identities`, `mount-policy-read`, `payload-read`, `workspace-write-output`,
 * `seed-kernel-metadata`), which is what a `/data/local/tmp` or `/sdcard` ordinary read/write
 * acceptance needs. It never skips a failure: any refused read/write is reported as FAIL.
 *
 * Fixture contract (provided by the device harness, see mount-device-probe.md):
 * - `<workspaceRoot>/payload.txt`       UID0:UID0 0600, contains `MOUNT_PROBE_SEED`
 * - `<workspaceRoot>/other/`            a separate plain tmpfs mount containing `public.txt`
 * - `<workspaceRoot>/alias-file`        bind mount of `<ceDir>/files/mount-probe/secret.txt`
 * - `<workspaceRoot>/alias-dir/`        bind mount of `<ceDir>/files/mount-probe/deep`
 * - `<workspaceRoot>/de-alias/`         bind mount of `<deDir>/files/mount-probe/deep`
 * - `<workspaceRoot>/link-to-secret`    symlink to `alias-dir/secret.txt`
 * Every `secret.txt` contains `MOUNT_PROBE_SECRET`; it must never appear in any result.
 */
object RootMountBoundaryProbeMain {
    private const val WORKSPACE_ID = "mount-probe"
    /** Optional 4th argument: only the fixture-free read/write/metadata subset runs. */
    private const val BASIC_MODE = "basic"
    private const val SEED_FILE = "payload.txt"
    private const val SEED_MARKER = "MOUNT_PROBE_SEED"
    private const val OUTPUT_FILE = "probe-output.txt"
    private const val OUTPUT_TEXT = "MOUNT_PROBE_OUTPUT_OK"
    private const val PUBLIC_FILE = "other/public.txt"
    private const val PUBLIC_MARKER = "MOUNT_PROBE_PUBLIC"
    private const val SECRET_MARKER = "MOUNT_PROBE_SECRET"
    private const val ALIAS_FILE = "alias-file"
    private const val ALIAS_DIR_FILE = "alias-dir/secret.txt"
    private const val DE_ALIAS_DIR_FILE = "de-alias/secret.txt"
    private const val SYMLINK_FILE = "link-to-secret"

    @JvmStatic
    fun main(args: Array<String>) {
        var failures = 0

        fun report(name: String, ok: Boolean, detail: String) {
            println("MOUNT_PROBE $name ${if (ok) "PASS" else "FAIL"} $detail")
            if (!ok) failures++
        }

        val basic = args.size == 4 && args[3] == BASIC_MODE
        if (args.size !in 3..4 || (args.size == 4 && !basic)) {
            println(
                "MOUNT_PROBE usage FAIL expected 3 args (ceDir deDir workspaceRoot) or 4 with '$BASIC_MODE', " +
                    "got ${args.size}: ${args.joinToString(" ")}",
            )
            kotlin.system.exitProcess(2)
        }
        // The CE/DE spelling the harness passes is forwarded to the production boundary as-is
        // (for example `/data/user/0/<pkg>`): `RootMountBoundary.read` has its own Android user-0
        // alias resolution, and pre-canonicalizing here would hide whether that real path is
        // protected. Only the FD observation below uses the canonical object.
        val ceRawPath = rawAbsolutePath(args[0])
        val deRawPath = rawAbsolutePath(args[1])
        val ceDir = canonical(args[0])
        val deDir = canonical(args[1])
        val workspaceRoot = canonical(args[2])
        println("MOUNT_PROBE uid=${android.os.Process.myUid()} mode=${if (basic) BASIC_MODE else "full"}")

        val ops = AndroidOsPosixFdOperations()

        // Walk from `/` with O_NOFOLLOW at every component; a symlinked component is not an error
        // to hide, it simply yields no identity.
        fun identity(absolutePath: String): RootFdNodeIdentity? {
            var current = ops.openRootSlashDirectory() ?: return null
            try {
                for (segment in absolutePath.trim('/').split('/').filter { it.isNotEmpty() }) {
                    val next = ops.openChildDirectoryAtNoFollow(current, segment) ?: return null
                    current.close()
                    current = next
                }
                return ops.fstatIdentity(current)
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { current.close() }
            }
        }

        val ceIdentity = identity(ceDir.path)
        val deIdentity = identity(deDir.path)
        val workspaceIdentity = identity(workspaceRoot.path)
        report(
            "fixture-identities",
            ceIdentity != null && deIdentity != null && workspaceIdentity != null &&
                ceIdentity.isDirectory && !ceIdentity.isSymlink && ceIdentity.hasValidKernelIdentity &&
                deIdentity.isDirectory && !deIdentity.isSymlink && deIdentity.hasValidKernelIdentity &&
                workspaceIdentity.isDirectory && !workspaceIdentity.isSymlink &&
                workspaceIdentity.hasValidKernelIdentity,
            "ce=${ceIdentity != null} de=${deIdentity != null} ws=${workspaceIdentity != null} " +
                "ceRawAlias=${ceRawPath != ceDir.path} deRawAlias=${deRawPath != deDir.path}",
        )
        if (ceIdentity == null || deIdentity == null || workspaceIdentity == null) {
            println("MOUNT_PROBE summary failures=$failures")
            kotlin.system.exitProcess(1)
        }

        val boundary = RootControlPlaneBoundary(
            credentialEncryptedDataDir = ceRawPath,
            credentialEncryptedIdentity = ceIdentity,
            deviceProtectedDataDir = deRawPath,
            deviceProtectedIdentity = deIdentity,
        )
        // Production policy provider: the same reader the service uses, no test allowlist.
        val trusted = RootMountBoundary.read(boundary)
        report("mount-policy-read", trusted != null, "trustedMountIds=${trusted?.mountIds?.size ?: -1}")
        if (trusted == null) {
            println("MOUNT_PROBE summary failures=$failures")
            kotlin.system.exitProcess(1)
        }

        val driver = AndroidRootPosixFileDriver(Paths.get(workspaceRoot.path), ops)
        val executor = RootWorkspaceFileExecutor(
            workspaceRootIdentity = workspaceIdentity,
            controlPlane = boundary,
            driver = driver,
            confirmedMountIds = emptySet(),
            trustedMountRoots = trusted,
        )

        fun read(relativePath: String): RootFileOperationResult<RootFileReadResult> =
            executor.executeReadText(
                RootTypedArguments.FileReadText(
                    workspaceId = WORKSPACE_ID,
                    relativePath = relativePath,
                    maxBytes = 64 * 1024,
                ),
            )

        fun rejectedOrFailed(result: RootFileOperationResult<*>): Boolean =
            result !is RootFileOperationResult.Succeeded

        fun contentOf(result: RootFileOperationResult<RootFileReadResult>): String = when (result) {
            is RootFileOperationResult.Succeeded -> result.value.content.toString(Charsets.UTF_8)
            else -> ""
        }

        fun outcome(result: RootFileOperationResult<*>): String = when (result) {
            is RootFileOperationResult.Succeeded -> "Succeeded"
            else -> result.toString()
        }

        // 1. The ordinary UID0 seed is readable through the real boundary.
        val seed = read(SEED_FILE)
        report(
            "payload-read",
            seed is RootFileOperationResult.Succeeded && contentOf(seed).contains(SEED_MARKER),
            "result=${outcome(seed)}",
        )


        // 2. A test-only output write inside the workspace root succeeds.
        val outputBytes = OUTPUT_TEXT.toByteArray(Charsets.UTF_8)
        val written = executor.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(
                workspaceId = WORKSPACE_ID,
                relativePath = OUTPUT_FILE,
                contentSha256 = sha256Hex(outputBytes),
                contentByteLength = outputBytes.size,
                replaceExisting = true,
            ),
            outputBytes,
        )
        val outputRead = read(OUTPUT_FILE)
        val shorter = "OK".toByteArray(Charsets.UTF_8)
        val overwrite = executor.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(WORKSPACE_ID, OUTPUT_FILE, sha256Hex(shorter), shorter.size, replaceExisting = true),
            shorter,
        )
        val overwritten = read(OUTPUT_FILE)
        val noReplace = executor.executeAtomicWriteText(
            RootTypedArguments.FileWriteText(WORKSPACE_ID, OUTPUT_FILE, sha256Hex(outputBytes), outputBytes.size, replaceExisting = false),
            outputBytes,
        )
        report(
            "workspace-write-output",
            written is RootFileOperationResult.Succeeded &&
                outputRead is RootFileOperationResult.Succeeded &&
                contentOf(outputRead) == OUTPUT_TEXT && overwrite is RootFileOperationResult.Succeeded &&
                contentOf(overwritten) == "OK" && noReplace is RootFileOperationResult.Rejected && contentOf(read(OUTPUT_FILE)) == "OK",
            "write=${outcome(written)} read=${outcome(outputRead)} overwrite=${outcome(overwrite)} noReplace=${outcome(noReplace)}",
        )

        val directory = executor.executeCreateDirectory(
            RootTypedArguments.FileCreateDirectory(WORKSPACE_ID, "probe-created-dir"),
        )
        report("workspace-create-directory", directory is RootFileOperationResult.Succeeded,
            "result=${outcome(directory)}")
        val noReplaceName = "probe-移动😀.txt"
        val moved = executor.executeMove(
            RootTypedArguments.FileMove(WORKSPACE_ID, OUTPUT_FILE, noReplaceName, replaceExisting = false),
        )
        val missingNoReplace = (moved is RootFileOperationResult.UnsupportedAttributePreservation &&
            moved.attribute == "atomic_no_replace_rename") ||
            (moved is RootFileOperationResult.PermissionDenied && moved.errnoName == "EINVAL")
        report(if (missingNoReplace) "move-no-replace-unavailable-preserves-source" else "workspace-move-no-replace",
            if (missingNoReplace) contentOf(read(OUTPUT_FILE)) == "OK" && read(noReplaceName) is RootFileOperationResult.Rejected
            else moved is RootFileOperationResult.Succeeded && contentOf(read(noReplaceName)) == "OK" &&
                read(OUTPUT_FILE) is RootFileOperationResult.Rejected,
            "result=${outcome(moved)}")
        // This is a separate, explicitly authorized overwrite request, never an automatic fallback.
        val movedName = "probe-explicit-移动😀.txt"
        val priorBytes = "OLD".toByteArray()
        val prior = executor.executeAtomicWriteText(RootTypedArguments.FileWriteText(
            WORKSPACE_ID, movedName, sha256Hex(priorBytes), priorBytes.size, replaceExisting = false,
        ), priorBytes)
        val replacementSource = if (missingNoReplace) OUTPUT_FILE else noReplaceName
        val explicitMove = executor.executeMove(RootTypedArguments.FileMove(
            WORKSPACE_ID, replacementSource, movedName, replaceExisting = true,
        ))
        report("file-move-explicit-replace", prior is RootFileOperationResult.Succeeded &&
            explicitMove is RootFileOperationResult.Succeeded && contentOf(read(movedName)) == "OK" &&
            read(replacementSource) is RootFileOperationResult.Rejected,
            "result=${outcome(explicitMove)}")
        val collision = executor.executeMove(
            RootTypedArguments.FileMove(WORKSPACE_ID, SEED_FILE, movedName, replaceExisting = false),
        )
        report("workspace-move-existing-refused",
            collision is RootFileOperationResult.Rejected && contentOf(read(movedName)) == "OK" &&
                contentOf(read(SEED_FILE)).contains(SEED_MARKER),
            "result=${outcome(collision)}")
        val movedDirectory = executor.executeMove(
            RootTypedArguments.FileMove(WORKSPACE_ID, "probe-created-dir", "probe-moved-dir", replaceExisting = false),
        )
        val missingDirectoryMove = (movedDirectory is RootFileOperationResult.UnsupportedAttributePreservation &&
            movedDirectory.attribute == "atomic_no_replace_rename") ||
            (movedDirectory is RootFileOperationResult.PermissionDenied && movedDirectory.errnoName == "EINVAL")
        report(if (missingDirectoryMove) "directory-move-unavailable-preserves-source" else "workspace-move-directory-no-replace",
            if (missingDirectoryMove) executor.executeStat(RootTypedArguments.FileStat(WORKSPACE_ID, "probe-created-dir")) is RootFileOperationResult.Succeeded &&
                executor.executeStat(RootTypedArguments.FileStat(WORKSPACE_ID, "probe-moved-dir")) is RootFileOperationResult.Rejected
            else movedDirectory is RootFileOperationResult.Succeeded,
            "result=${outcome(movedDirectory)}")
        val selfDirectory = executor.executeCreateDirectory(
            RootTypedArguments.FileCreateDirectory(WORKSPACE_ID, "probe-self-dir"),
        )
        val selfChild = executor.executeCreateDirectory(
            RootTypedArguments.FileCreateDirectory(WORKSPACE_ID, "probe-self-dir/child"),
        )
        val selfMove = executor.executeMove(RootTypedArguments.FileMove(
            WORKSPACE_ID, "probe-self-dir", "probe-self-dir/child/nested",
        ))
        report("invalid-directory-move-preserves-source",
            selfDirectory is RootFileOperationResult.Succeeded && selfChild is RootFileOperationResult.Succeeded &&
                selfMove is RootFileOperationResult.PermissionDenied && selfMove.errnoName == "EINVAL" &&
                executor.executeStat(RootTypedArguments.FileStat(WORKSPACE_ID, "probe-self-dir/child")) is RootFileOperationResult.Succeeded &&
                executor.executeStat(RootTypedArguments.FileStat(WORKSPACE_ID, "probe-self-dir/child/nested")) is RootFileOperationResult.Rejected,
            "result=$selfMove")

        if (basic) {
            val current = read(movedName) as? RootFileOperationResult.Succeeded
            val patch = """{"search":"OK","replace":"MUST_NOT_WRITE"}""".toByteArray()
            val conditionalPatch = current?.let {
                executor.executeApplyPatch(RootTypedArguments.FileApplyPatch(
                    WORKSPACE_ID, movedName, sha256Hex(patch), patch.size,
                    it.value.version, "search_replace",
                ), patch)
            }
            report("shared-conditional-patch-refused",
                conditionalPatch is RootFileOperationResult.Rejected &&
                    conditionalPatch.rejection == runtime.mobileagent.domain.RootConfinementRejection.WRITE_DOMAIN_VERSION_UNSUPPORTED &&
                    contentOf(read(movedName)) == "OK",
                "result=$conditionalPatch")
        }

        // Items 3-8 need the bind/tmpfs/symlink fixture and are skipped by the minimal mode.
        if (!basic) {
        // 3. A different ordinary mount under the workspace stays reachable (allowlisted mount).
        val otherMount = read(PUBLIC_FILE)
        report(
            "other-mount-read",
            otherMount is RootFileOperationResult.Succeeded && contentOf(otherMount).contains(PUBLIC_MARKER),
            "type=${otherMount::class.simpleName}",
        )

        // 4-6. Every alias of a private host file/directory must fail and must not leak content.
        for ((name, relativePath) in listOf(
            "alias-file-blocked" to ALIAS_FILE,
            "alias-dir-blocked" to ALIAS_DIR_FILE,
            "de-alias-dir-blocked" to DE_ALIAS_DIR_FILE,
            "ancestor-alias-blocked" to "ancestor/runtime.mobileagent/files/mount-probe/secret.txt",
        )) {
            val result = read(relativePath)
            val leaked = contentOf(result).contains(SECRET_MARKER)
            report(name, rejectedOrFailed(result) && !leaked, "type=${result::class.simpleName} leaked=$leaked")
        }

        // 7. A symlink inside the workspace is not followed (O_NOFOLLOW), so it cannot reach the
        //    alias either. This keeps the real NoFollow protection in the probe instead of adding a
        //    name-based rejection.
        val symlink = read(SYMLINK_FILE)
        report(
            "symlink-not-followed",
            rejectedOrFailed(symlink) && !contentOf(symlink).contains(SECRET_MARKER),
            "type=${symlink::class.simpleName}",
        )

        // 8. The alias directory is refused by the production PROPOSE entry, which is where the
        //    mount-source guard lives (`identity.mountId !in mounts.mountIds`). The probe builds a
        //    test session/caller with matching identity so the real entry can run; only the directory
        //    admission is exercised here — this is not a claim about a real Root authorization
        //    handshake, and it deliberately does not assert anything about hand-built executors that
        //    bypass the entry.
        val probeIdentity = PrivilegedSessionIdentity(
            authority = Authority.ROOT_DIRECT,
            privilegeTier = PrivilegeTier.ROOT,
            serviceUid = 0,
            appUid = ceIdentity.ownerUid,
            androidUserId = 0,
            appInstanceId = "mount-probe-app-instance-0001",
            packageName = "runtime.mobileagent",
            signingSha256 = "0".repeat(64),
            identityEpoch = 1L,
            serviceInstanceId = "mount-probe-service-0001",
            sessionId = "mount-probe-session-0001",
            protocolVersion = PrivilegedSessionIdentity.ROOT_PROTOCOL_VERSION,
        )
        val probeCaller = RootCallerObservation(
            callingUid = ceIdentity.ownerUid,
            observedServiceUid = 0,
            packageName = probeIdentity.packageName,
            androidUserId = 0,
            signingSha256 = probeIdentity.signingSha256,
        )
        val selection = RootServiceWorkspaceSelection(
            fdOps = ops,
            workspaceExecutors = java.util.concurrent.ConcurrentHashMap(),
        )
        val proposeRequest = RootPrivilegedServiceEngine.json.encodeToString(
            RootServiceWorkspaceSelectionRequest(
                phase = RootServiceWorkspaceSelection.PHASE_PROPOSE,
                targetServiceInstanceId = probeIdentity.serviceInstanceId,
                targetSessionId = probeIdentity.sessionId,
                targetIdentityEpoch = probeIdentity.identityEpoch,
                workspaceRootPath = "${workspaceRoot.path.trimEnd('/')}/alias-dir",
                rootKindName = RootWorkspaceRootKind.DIRECTORY.name,
            ),
        )
        val proposeResult = runCatching {
            selection.dispatch(
                requestJson = proposeRequest,
                session = RootServiceSession(probeIdentity, controlPlane = boundary),
                caller = probeCaller,
                boundary = boundary,
                closed = false,
            )
        }.getOrNull()
        report(
            "alias-root-propose-rejected",
            proposeResult != null && !proposeResult.accepted &&
                proposeResult.errorReason == "mount_source_unavailable_or_protected",
            "accepted=${proposeResult?.accepted} reason=${proposeResult?.errorReason} " +
                "phase=${proposeResult?.phase}",
        )
        val ancestorRequest = RootPrivilegedServiceEngine.json.decodeFromString<RootServiceWorkspaceSelectionRequest>(proposeRequest)
            .copy(workspaceRootPath = "${workspaceRoot.path}/ancestor/runtime.mobileagent/files/mount-probe")
        val ancestorResult = selection.dispatch(
            RootPrivilegedServiceEngine.json.encodeToString(ancestorRequest),
            RootServiceSession(probeIdentity, controlPlane = boundary), probeCaller, boundary, false,
        )
        report("ancestor-root-propose-rejected", !ancestorResult.accepted &&
            ancestorResult.errorReason == "mount_source_unavailable_or_protected",
            "accepted=${ancestorResult.accepted} reason=${ancestorResult.errorReason}")

        fun browse(path: String, cursor: String? = null): RootDirectoryBrowsePage? {
            val request = ancestorRequest.copy(phase = "BROWSE", workspaceRootPath = path, cursor = cursor)
            return selection.dispatch(RootPrivilegedServiceEngine.json.encodeToString(request),
                RootServiceSession(probeIdentity, controlPlane = boundary), probeCaller, boundary, false).directoryPage
        }
        val firstPage = browse(workspaceRoot.path)
        val secondPage = firstPage?.nextCursor?.let { browse(workspaceRoot.path, it) }
        val directoryNames = firstPage?.entries.orEmpty() + secondPage?.entries.orEmpty()
        report("browse-protected-mounts-hidden", firstPage != null && directoryNames.none {
            it.name in setOf("alias-dir", "de-alias", "ancestor", "host-link", "alias-file", "link-to-secret")
        }, "entries=${directoryNames.size}")
        report("browse-directory-pagination", firstPage?.entries?.size == 128 && secondPage != null &&
            secondPage.nextCursor == null && directoryNames.map { it.name }.distinct().size == directoryNames.size &&
            directoryNames.count { it.name.startsWith("page-dir-") } == 225,
            "first=${firstPage?.entries?.size} second=${secondPage?.entries?.size}")
        val storageAlias = directoryNames.singleOrNull { it.name == "storage-alias" }
        report("browse-storage-alias", storageAlias != null && storageAlias.targetPath == File("/sdcard").canonicalPath &&
            storageAlias.path == storageAlias.targetPath,
            "alias=${storageAlias != null} canonical=${storageAlias?.targetPath == File("/sdcard").canonicalPath}")
        }

        // 9. The metadata path still reports real kernel facts (nlink 1, non-zero mount id), i.e. the
        //    protections above are the production ones and not a synthetic shortcut.
        val stat = executor.executeStat(
            RootTypedArguments.FileStat(workspaceId = WORKSPACE_ID, relativePath = SEED_FILE),
        )
        val statOk = stat is RootFileOperationResult.Succeeded &&
            stat.value.hardlinkCount == 1L && stat.value.mountId > 0L && stat.value.inode > 0L
        report("seed-kernel-metadata", statOk, "result=${outcome(stat)}")

        println("MOUNT_PROBE summary failures=$failures")
        if (failures != 0) {
            kotlin.system.exitProcess(1)
        }
    }

    private fun canonical(path: String): File = File(path).canonicalFile

    /** Raw absolute spelling for the boundary; only trims a trailing slash, never resolves links. */
    private fun rawAbsolutePath(path: String): String {
        require(path.startsWith("/")) { "CE/DE path must be absolute: $path" }
        return path.trimEnd('/').ifEmpty { "/" }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
