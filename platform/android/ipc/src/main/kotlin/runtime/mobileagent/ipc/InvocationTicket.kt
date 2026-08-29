// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ipc

data class InvocationTicket(
    val invocationId: String,
    val runId: String,
    val packageHash: String,
    val grantRevision: Int,
    val oneTimeToken: String,
) {
    /**
     * Tickets are deliberately small, opaque values.  The token is held only
     * in memory and is never included in audit text or user facing errors.
     */
    fun validate(): Boolean =
        invocationId.isSafeIdentifier() &&
            runId.isSafeIdentifier() &&
            packageHash.matches(SHA256) &&
            grantRevision > 0 &&
            oneTimeToken.matches(TOKEN)

    private fun String.isSafeIdentifier(): Boolean = length in 1..128 && matches(IDENTIFIER)

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val SHA256 = Regex("[0-9a-fA-F]{64}")
        val TOKEN = Regex("[A-Za-z0-9_-]{32,256}")
    }
}
