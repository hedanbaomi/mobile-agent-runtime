// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/**
 * Small Binder response for an accepted shell invocation.
 *
 * The three descriptors carry stdout, stderr and a bounded completion JSON
 * envelope independently.  Actual command output never travels as a Binder
 * String or byte array, which keeps the transaction well below Binder's
 * per-transaction limit even when a command emits a large result.
 */
class ShizukuShellResponse(
    val accepted: Boolean,
    val errorCode: String?,
    val stdoutFd: ParcelFileDescriptor?,
    val stderrFd: ParcelFileDescriptor?,
    val resultFd: ParcelFileDescriptor?,
) : Parcelable {
    override fun describeContents(): Int =
        Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeInt(if (accepted) 1 else 0)
        destination.writeString(errorCode?.take(MAX_ERROR_CODE_CHARS))
        writeDescriptor(destination, stdoutFd, flags)
        writeDescriptor(destination, stderrFd, flags)
        writeDescriptor(destination, resultFd, flags)
    }

    private fun writeDescriptor(destination: Parcel, descriptor: ParcelFileDescriptor?, flags: Int) {
        if (descriptor == null) {
            destination.writeInt(0)
        } else {
            destination.writeInt(1)
            descriptor.writeToParcel(destination, flags)
        }
    }

    companion object {
        private const val MAX_ERROR_CODE_CHARS = 128

        @JvmField
        val CREATOR: Parcelable.Creator<ShizukuShellResponse> =
            object : Parcelable.Creator<ShizukuShellResponse> {
                override fun createFromParcel(source: Parcel): ShizukuShellResponse {
                    val accepted = source.readInt() != 0
                    val errorCode = source.readString()?.take(MAX_ERROR_CODE_CHARS)
                    return ShizukuShellResponse(
                        accepted = accepted,
                        errorCode = errorCode,
                        stdoutFd = readDescriptor(source),
                        stderrFd = readDescriptor(source),
                        resultFd = readDescriptor(source),
                    )
                }

                override fun newArray(size: Int): Array<ShizukuShellResponse?> =
                    arrayOfNulls(size)
            }

        private fun readDescriptor(source: Parcel): ParcelFileDescriptor? =
            if (source.readInt() == 0) null else ParcelFileDescriptor.CREATOR.createFromParcel(source)

        fun accepted(
            stdoutFd: ParcelFileDescriptor,
            stderrFd: ParcelFileDescriptor,
            resultFd: ParcelFileDescriptor,
        ): ShizukuShellResponse = ShizukuShellResponse(
            accepted = true,
            errorCode = null,
            stdoutFd = stdoutFd,
            stderrFd = stderrFd,
            resultFd = resultFd,
        )

        fun rejected(errorCode: String): ShizukuShellResponse = ShizukuShellResponse(
            accepted = false,
            errorCode = errorCode.take(MAX_ERROR_CODE_CHARS),
            stdoutFd = null,
            stderrFd = null,
            resultFd = null,
        )
    }
}
