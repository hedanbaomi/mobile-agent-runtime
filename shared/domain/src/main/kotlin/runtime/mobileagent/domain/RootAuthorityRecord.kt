// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/** Local durable control-plane state. It is deliberately absent from transfer/backup DTOs. */
data class RootAuthorityRecord(
    val consent: RootAuthorityConsent,
    val revision: Long = 0,
    val identityEpoch: Long = 0,
    val degradedEpoch: Long? = null,
    /** Older uncertainty explicitly covered by the last foreground recovery. */
    val recoveryAckEpoch: Long = 0,
) {
    init {
        require(revision >= 0 && identityEpoch >= 0)
        require(recoveryAckEpoch >= 0 && (recoveryAckEpoch == 0L || recoveryAckEpoch < identityEpoch))
        require(degradedEpoch == null || (degradedEpoch in 1..identityEpoch && degradedEpoch > recoveryAckEpoch))
    }

    val degraded: Boolean get() = degradedEpoch != null
}
