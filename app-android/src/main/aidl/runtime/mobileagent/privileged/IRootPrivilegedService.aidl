// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged;

interface IRootPrivilegedService {
    String handshake(String requestJson);
    String bindWorkspace(String requestJson);
    String submitEnvelope(long controlSequence, String rawEnvelopeJson);
    String executeAccepted(String requestId, in byte[] payloadBytes);
    String submitAndExecute(long controlSequence, String rawEnvelopeJson, in byte[] payloadBytes);
    boolean cancelCommand(String requestId);
    String revokeAndShutdown(long controlSequence, String reason);
    void destroy();
    boolean cancelInvocation(String requestJson);
    String queryQuiescence(String requestJson);
}
