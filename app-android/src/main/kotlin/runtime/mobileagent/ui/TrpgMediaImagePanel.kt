// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import runtime.mobileagent.TrpgMediaImageResult
import runtime.mobileagent.TrpgMediaPolicy
import runtime.mobileagent.trpgMediaText

/**
 * User-picked local scene image preview.
 *
 * The image is decoded with a byte budget and edge/pixel sampling, shown only
 * on this device and never uploaded or handed to a model. Clearing the pick
 * forgets the preference; it does not delete the user's file.
 */
@Composable
internal fun TrpgMediaImagePanel(
    chinese: Boolean,
    uri: String?,
    loading: Boolean,
    result: TrpgMediaImageResult?,
    message: String?,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = trpgMediaText(chinese, "场景图片", "Scene image"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("trpg.media.image.title"),
        )
        Text(
            text = trpgMediaText(
                chinese,
                "仅由你从本机选择，只在设备上预览，不上传，也不写入模型提示或团务备份。",
                "Only a local file you choose. It is previewed on this device only, never uploaded, and never written to model prompts or campaign backups.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onChoose,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.image.choose"),
            ) {
                Text(trpgMediaText(chinese, "选择图片", "Choose image"))
            }
            TextButton(
                onClick = onClear,
                enabled = uri != null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.media.image.clear"),
            ) {
                Text(trpgMediaText(chinese, "清除图片", "Clear image"))
            }
        }
        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(
                    text = trpgMediaText(chinese, "正在读取图片预览…", "Loading image preview…"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            result is TrpgMediaImageResult.Ready -> Image(
                bitmap = result.bitmap.asImageBitmap(),
                contentDescription = trpgMediaText(
                    chinese,
                    "场景图片预览，${result.width}×${result.height}",
                    "Scene image preview, ${result.width}×${result.height}",
                ),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp)
                    .testTag("trpg.media.image.preview"),
            )
            result is TrpgMediaImageResult.RejectedTooLarge -> Text(
                text = trpgMediaText(
                    chinese,
                    "图片超过 ${TrpgMediaPolicy.MAX_IMAGE_BYTES / (1024 * 1024)} MiB，未读取。请选择更小的图片。",
                    "The image is over ${TrpgMediaPolicy.MAX_IMAGE_BYTES / (1024 * 1024)} MiB and was not read. Choose a smaller image.",
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("trpg.media.image.error"),
            )
            result is TrpgMediaImageResult.Failed -> Text(
                text = trpgMediaText(
                    chinese,
                    "无法读取所选图片：文件可能已移动、不是图片，或访问授权已失效。",
                    "The selected image cannot be read: the file may have moved, not be an image, or the access grant expired.",
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("trpg.media.image.error"),
            )
            uri != null -> Text(
                text = trpgMediaText(chinese, "尚未生成预览。", "No preview yet."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("trpg.media.image.message"),
            )
        }
    }
}
