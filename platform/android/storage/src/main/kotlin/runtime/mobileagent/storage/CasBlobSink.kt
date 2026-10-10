// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.storage

import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.FileBlobSink
import java.io.File

class CasBlobSink(root: File) : BlobSink by FileBlobSink(root)
