// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.storage

import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.FileBlobSink
import runtime.mobileagent.knowledge.StoredBlob
import java.io.File

class CasBlobSink(root: File) : BlobSink {
    private val inner = FileBlobSink(root)

    override fun put(bytes: ByteArray, mediaType: String): StoredBlob = inner.put(bytes, mediaType)
    override fun get(sha256: String): ByteArray? = inner.get(sha256)
}
