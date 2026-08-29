<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Third-party notices

第一方代码和文档使用 `AGPL-3.0-only`。本文件列出 Android debug 运行时依赖的
固定版本来源和随 APK 提供的完整许可证/notice 文本；第三方材料保持其原许可，
不会因为本项目许可而重新许可。

## Android debug runtime

主流程保存的 `app-android:debugRuntimeClasspath` 报告解析出 144 个 distinct
external `group:artifact:version`。每个坐标都有 exact Gradle cache POM（144/144）
和一项位于 `app-android/src/main/assets/licenses/maven/<group>__<artifact>__<version>/`
的目录；索引中的对应 APK 路径为 `licenses/maven/<group>__<artifact>__<version>/`。
可读索引为
[`app-android/src/main/assets/licenses/index.json`](../app-android/src/main/assets/licenses/index.json)。

索引 `schemaVersion` 为 `1`，Maven 组件使用以下契约：

```json
{
  "id": "maven:group:artifact:version",
  "name": "artifact",
  "version": "version",
  "license": "Apache-2.0",
  "source": "https://repo1.maven.org/maven2/.../artifact-version.pom",
  "files": [{"label": "...", "path": "licenses/maven/..."}]
}
```

每个 `files` 项还带 SHA-256 和来源说明；路径是 Android AssetManager 相对路径，
只使用 `licenses/...` 或 `modelpacks/...`，以便 Android 端按索引读取完整原文。
AndroidX 的 `source` 使用 Google Maven 的精确版本 POM URL，其他 Maven 组件使用
其对应的 canonical Maven POM URL。缓存 JAR/AAR 中存在的 LICENSE/NOTICE 会逐字节提取，
缺失时才使用固定官方来源的完整原文。每个不可注释的第三方文本都有对应的
`.license` REUSE sidecar。

| POM 原始 license 名称 | 数量 | 处理 |
| --- | ---: | --- |
| `The Apache Software License, Version 2.0` | 136 | Apache 2.0 原文；优先使用缓存归档内文本 |
| `The Apache License, Version 2.0`（Kotlin stdlib 家族） | 4 | Apache 2.0 原文；保留 Kotlin 的 POM 名称 |
| `MIT License` | 1 | ONNX Runtime 1.29.0 的 Microsoft 官方版本原文 |
| `Bouncy Castle Licence` | 1 | Bouncy Castle 1.79 官方版本原文（HTML） |
| POM 没有 license 元素 | 2 | Guava parent 与 exact SLF4J JAR/官方源补查，见证据 |

许可证解析未确认项为 `0/144`；POM 空白项已经完成逐项补查。

### 特殊 notice

OkHttp 4.12.0 的归档内 `okhttp3/internal/publicsuffix/NOTICE` 原文随 APK 提供，
并同时提供 NOTICE 所引用的 Mozilla Public License 2.0 完整原文。OkHttp 自身
的 Apache 2.0 原文单独列出，不能把 public suffix 数据的 MPL-2.0 归并成 Apache。

Guava `listenablefuture:1.0` 的 child POM 没有 license 元素，缓存的
`guava-parent:26.0-android` 声明 Apache 2.0；SLF4J `2.0.16` 的 child POM 同样
没有 license 元素，但 exact JAR 带有完整 `META-INF/LICENSE.txt` MIT 文本。

完整的 144 坐标清单、POM/归档 SHA-256、提取与回退依据、未执行的 APK/设备验证
见 [`docs/evidence/2026-08-29/runtime-notices.md`](evidence/2026-08-29/runtime-notices.md)。

## Native and model assets

以下由其他 owner 管理的资产也已在 APK 索引中登记；本次没有创建或覆盖它们。
索引将其标为 `pending-main-flow`，待主流程生成实际 APK 后核对存在性、APK
路径和 hash：

- CPython 3.14.7：`licenses/cpython-3.14.7/LICENSE.txt`、`NOTICE.txt`
- USearch 2.25.1：`licenses/usearch-2.25.1/LICENSE.txt`
- ONNX Runtime 1.29.0：`licenses/onnxruntime-1.29.0/LICENSE.txt`
- all-MiniLM-L6-v2：`modelpacks/all-MiniLM-L6-v2/LICENSES/Apache-2.0.txt`

这些条目的许可不替代各自原文，也不把全部依赖笼统标为 AGPL。

## Generation and verification

生成器是 [`tools/runtime-notices.py`](../tools/runtime-notices.py)：

```powershell
python -B tools/runtime-notices.py --write
python -B tools/runtime-notices.py --check
```

生成器只读取指定的依赖报告和 exact Gradle cache；不调用 Gradle、不下载二进制，
许可证回退只访问脚本白名单中的 Apache、Microsoft/ONNX Runtime、Bouncy Castle
和 Mozilla 官方 HTTPS 来源。本次已完成 Python 生成、JSON/path/hash/sidecar 检查；
APK 打包、`generateDebugSbom`、模拟器/真机和主流程读取验证仍由主流程执行。
