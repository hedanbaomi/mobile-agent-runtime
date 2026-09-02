// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.shizuku

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/**
 * Small Binder envelope for one workspace read chunk.  The chunk itself is
 * always carried by [contentFd]; only bounded metadata crosses Binder as a
 * String.  Rejected responses never carry a descriptor.
 */
class ShizukuWorkspaceReadResponse(
    val accepted: Boolean,
    val errorCode: String?,
    val metadata: String?,
    val contentFd: ParcelFileDescriptor?,
) : Parcelable {
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun writeToParcel(destination: Parcel, flags: Int) {
        destination.writeInt(if (accepted) 1 else 0)
        destination.writeString(errorCode?.take(MAX_ERROR_CODE_CHARS))
        destination.writeString(metadata?.take(MAX_METADATA_CHARS))
        if (contentFd == null) {
            destination.writeInt(0)
        } else {
            destination.writeInt(1)
            contentFd.writeToParcel(destination, flags)
        }
    }

    companion object {
        private const val MAX_ERROR_CODE_CHARS = 128
        private const val MAX_METADATA_CHARS = 4 * 1024

        @JvmField
        val CREATOR: Parcelable.Creator<ShizukuWorkspaceReadResponse> =
            object : Parcelable.Creator<ShizukuWorkspaceReadResponse> {
                override fun createFromParcel(source: Parcel): ShizukuWorkspaceReadResponse {
                    val accepted = source.readInt() != 0
                    val errorCode = source.readString()?.take(MAX_ERROR_CODE_CHARS)
                    val metadata = source.readString()?.take(MAX_METADATA_CHARS)
                    val descriptor = if (source.readInt() == 0) {
                        null
                    } else {
                        ParcelFileDescriptor.CREATOR.createFromParcel(source)
                    }
                    return ShizukuWorkspaceReadResponse(accepted, errorCode, metadata, descriptor)
                }

                override fun newArray(size: Int): Array<ShizukuWorkspaceReadResponse?> = arrayOfNulls(size)
            }

        fun accepted(metadata: String, contentFd: ParcelFileDescriptor): ShizukuWorkspaceReadResponse =
            ShizukuWorkspaceReadResponse(true, null, metadata.take(MAX_METADATA_CHARS), contentFd)

        fun rejected(errorCode: String): ShizukuWorkspaceReadResponse =
            ShizukuWorkspaceReadResponse(false, errorCode.take(MAX_ERROR_CODE_CHARS), null, null)
    }
}
