// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ipc

import android.os.Parcel
import android.os.ParcelFileDescriptor

data class PythonStartMessage(
    val ticket: InvocationTicket,
    val entrypoint: String,
    val limits: PythonIpcProtocol.PythonLimits,
    val packageFd: ParcelFileDescriptor,
    val stdlibFd: ParcelFileDescriptor,
    val inputFd: ParcelFileDescriptor,
    val resultFd: ParcelFileDescriptor,
    val brokerRequestFd: ParcelFileDescriptor,
    val brokerResponseFd: ParcelFileDescriptor,
    val logFd: ParcelFileDescriptor,
    /** Host-generated nonce; never copied into Python input or SDK objects. */
    val channelNonce: String = "",
) {
    /** Keep the native-only channel nonce out of accidental diagnostics. */
    override fun toString(): String =
        "PythonStartMessage(entrypoint=$entrypoint, limits=$limits)"
}

object PythonBinderCodec {
    fun writeStart(parcel: Parcel, message: PythonStartMessage) {
        parcel.writeInterfaceToken(PythonIpcProtocol.DESCRIPTOR)
        writeTicket(parcel, message.ticket)
        parcel.writeString(message.entrypoint)
        writeLimits(parcel, message.limits)
        writeFd(parcel, message.packageFd)
        writeFd(parcel, message.stdlibFd)
        writeFd(parcel, message.inputFd)
        writeFd(parcel, message.resultFd)
        writeFd(parcel, message.brokerRequestFd)
        writeFd(parcel, message.brokerResponseFd)
        writeFd(parcel, message.logFd)
        parcel.writeString(message.channelNonce)
    }

    fun readStart(parcel: Parcel): PythonStartMessage {
        parcel.enforceInterface(PythonIpcProtocol.DESCRIPTOR)
        val ticket = readTicket(parcel)
        val entrypoint = parcel.readString().orEmpty()
        val limits = readLimits(parcel)
        return PythonStartMessage(
            ticket = ticket,
            entrypoint = entrypoint,
            limits = limits,
            packageFd = readFd(parcel),
            stdlibFd = readFd(parcel),
            inputFd = readFd(parcel),
            resultFd = readFd(parcel),
            brokerRequestFd = readFd(parcel),
            brokerResponseFd = readFd(parcel),
            logFd = readFd(parcel),
            channelNonce = parcel.readString().orEmpty(),
        )
    }

    fun writeCancel(parcel: Parcel, ticket: InvocationTicket) {
        parcel.writeInterfaceToken(PythonIpcProtocol.DESCRIPTOR)
        writeTicket(parcel, ticket)
    }

    fun readCancel(parcel: Parcel): InvocationTicket {
        parcel.enforceInterface(PythonIpcProtocol.DESCRIPTOR)
        return readTicket(parcel)
    }

    fun writeAbort(parcel: Parcel, ticket: InvocationTicket) = writeCancel(parcel, ticket)

    fun readAbort(parcel: Parcel): InvocationTicket = readCancel(parcel)

    fun writePing(parcel: Parcel) = parcel.writeInterfaceToken(PythonIpcProtocol.DESCRIPTOR)

    private fun writeTicket(parcel: Parcel, ticket: InvocationTicket) {
        parcel.writeString(ticket.invocationId)
        parcel.writeString(ticket.runId)
        parcel.writeString(ticket.packageHash)
        parcel.writeInt(ticket.grantRevision)
        parcel.writeString(ticket.oneTimeToken)
    }

    private fun readTicket(parcel: Parcel): InvocationTicket = InvocationTicket(
        invocationId = parcel.readString().orEmpty(),
        runId = parcel.readString().orEmpty(),
        packageHash = parcel.readString().orEmpty(),
        grantRevision = parcel.readInt(),
        oneTimeToken = parcel.readString().orEmpty(),
    )

    private fun writeLimits(parcel: Parcel, limits: PythonIpcProtocol.PythonLimits) {
        parcel.writeInt(limits.timeoutMs)
        parcel.writeInt(limits.maxOutputBytes)
        parcel.writeInt(limits.maxLogBytes)
        parcel.writeInt(limits.maxInputBytes)
        parcel.writeInt(limits.maxBrokerCalls)
    }

    private fun readLimits(parcel: Parcel): PythonIpcProtocol.PythonLimits = PythonIpcProtocol.PythonLimits(
        timeoutMs = parcel.readInt(),
        maxOutputBytes = parcel.readInt(),
        maxLogBytes = parcel.readInt(),
        maxInputBytes = parcel.readInt(),
        maxBrokerCalls = parcel.readInt(),
    )

    private fun writeFd(parcel: Parcel, fd: ParcelFileDescriptor) {
        parcel.writeInt(1)
        fd.writeToParcel(parcel, 0)
    }

    private fun readFd(parcel: Parcel): ParcelFileDescriptor {
        check(parcel.readInt() == 1) { "Missing required IPC file descriptor" }
        return ParcelFileDescriptor.CREATOR.createFromParcel(parcel)
    }
}
