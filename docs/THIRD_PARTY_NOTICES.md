<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Third-party notices

第一方代码和文档使用 `AGPL-3.0-only`。本文件列出 Android 运行时及可分发桌面
辅助程序依赖的固定版本来源和许可证/notice 处理；第三方材料保持其原许可，
不会因为本项目许可而重新许可。

## Shizuku API

应用可选集成 `dev.rikka.shizuku:aidl:13.1.5`、
`dev.rikka.shizuku:api:13.1.5`、`dev.rikka.shizuku:provider:13.1.5` 与
`dev.rikka.shizuku:shared:13.1.5`。这些组件来自
[`RikkaApps/Shizuku-API`](https://github.com/RikkaApps/Shizuku-API)，使用 MIT
License，版权所有 2021 RikkaW。Shizuku 是用户另行安装、启动和授权的第三方应用；
本项目不打包 Shizuku 管理器、不代替用户启用 ADB/root，也不把 Shizuku 的授权
描述为 Android 平台权限或本项目的 AGPL 代码。四个坐标共用固定提交
[`510fc988`](https://github.com/RikkaApps/Shizuku-API/tree/510fc988c02c3475d8c25db170f96792f105bdf8)，
许可证原文 SHA-256 为
`64870037f294b6f2b75c8ebe77cc0702acb3b64f86b4b0e58bf8558525303686`。

## JNA（Desktop Companion）

Windows 有线 ADB Desktop Companion 使用 `net.java.dev.jna:jna:5.19.1` 和
`net.java.dev.jna:jna-platform:5.19.1` 调用 Windows DPAPI。这两个组件仅进入
`desktop:bridge` 分发物，不进入 Android APK。上游为
[`java-native-access/jna` 5.19.1](https://github.com/java-native-access/jna/tree/1a91122853f6ab6f1fb2a4a284a6cf2ed8af0a4d)，
按 `Apache-2.0 OR LGPL-2.1-or-later` 双重许可发布；分发 JAR 内保留上游
`META-INF/LICENSE`，本项目不将其重新许可为 AGPL。

## Android debug runtime

主流程保存的 `app-android:debugRuntimeClasspath` 报告解析出 148 个 distinct
external `group:artifact:version`，其中包含上述四个 Shizuku 坐标。每个坐标都有 exact Gradle cache POM（148/148）
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
| `MIT License` | 5 | Shizuku API 四个组件与 ONNX Runtime 1.29.0 的固定版本原文 |
| `Bouncy Castle Licence` | 1 | Bouncy Castle 1.79 官方版本原文（HTML） |
| POM 没有 license 元素 | 2 | Guava parent 与 exact SLF4J JAR/官方源补查，见证据 |

许可证解析未确认项为 `0/148`；POM 空白项已经完成逐项补查。

### 特殊 notice

OkHttp 4.12.0 的归档内 `okhttp3/internal/publicsuffix/NOTICE` 原文随 APK 提供，
并同时提供 NOTICE 所引用的 Mozilla Public License 2.0 完整原文。OkHttp 自身
的 Apache 2.0 原文单独列出，不能把 public suffix 数据的 MPL-2.0 归并成 Apache。

Guava `listenablefuture:1.0` 的 child POM 没有 license 元素，缓存的
`guava-parent:26.0-android` 声明 Apache 2.0；SLF4J `2.0.16` 的 child POM 同样
没有 license 元素，但 exact JAR 带有完整 `META-INF/LICENSE.txt` MIT 文本。

完整的 148 坐标清单、POM/归档 SHA-256、提取与回退依据、未执行的 APK/设备验证
见 [`docs/evidence/2026-08-29/runtime-notices.md`](evidence/2026-08-29/runtime-notices.md)。

## Native and model assets

以下由其他 owner 管理的资产已在 APK 索引中登记。它们的法律文件、模型
payload 和 manifest 都带有由当前 generated output 实测得到的 SHA-256；索引状态
为 `generated-assets-or-artifact`。检查器只接受明确的模块 generated-assets 根或
APK/AAB/ZIP 内的 `assets/` 路径，不把缺失的 source `ASSETS_ROOT` 当成通过：

- CPython 3.14.7（`runtime/python-android/build/generated/pythonAssets`）：
  `licenses/cpython-3.14.7/LICENSE.txt`
  `b0e25a78cffb43f4d92de8b61ccfa1f1f98ecbc22330b54b5251e7b6ba010231`；
  `NOTICE.txt`
  `87e76594ca56bd6b1116d2ee9b902b98c0940863762f9ef822de9a3cec31fcb3`；
  `python/python3.14.zip`
  `8aa6768b2585279a566bef9f2b5c0568f3199c940fe77b2d84b240b97474e351`。
  两个官方 Android archive 及其 Sigstore bundle 的 SHA-256 记录在 index provenance 中。
- USearch 2.25.1（`runtime/vector-usearch/build/generated/vector-assets`）：
  `LICENSE.txt` `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4`；
  `NOTICE.txt` `145493df94feaef58efd5f5fcedc9d4d3c08a285dbbf87a9eefae3455cb475a4`；
  官方 source archive SHA-256 为
  `30dd99efab891a6385a89ecd3a3a8a85ed7d3f064b7657588fc3ef5ccd2d52e3`。
- ONNX Runtime 1.29.0（`runtime/embedding-onnx/build/generated/embedding-assets`）：
  `LICENSE.txt` `2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c`；
  `NOTICE.txt` `af50216ba6c698e64b3a91f63278543d79f963938ca14fd23df45f2d6de3491c`。
- all-MiniLM-L6-v2（同一 embedding generated root）：
  `LICENSES/Apache-2.0.txt` `9bf4e882bdd75e8d10fc788c53d0a787ef0f59cead1fe1f0878c240896fc8610`；
  `LICENSE-NOTICE.txt` `e4870c1e6fe1a4652eb6912c1df83a097653dd337560ac63c6a211a50a1f0333`；
  `model.onnx` `6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452`；
  `tokenizer.json` `be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037`；
  `manifest.json` `e7ac456cccf4def26d7599afe0c200ff8900599776f970b4b7b8881fd17bf6e5`。

这些条目的许可不替代各自原文，也不把全部依赖笼统标为 AGPL。

## Generation and verification

生成器是 [`tools/runtime-notices.py`](../tools/runtime-notices.py)：

```powershell
python -B tools/runtime-notices.py --write
python -B tools/runtime-notices.py --check
python -B tools/runtime-notices.py --check --artifact app-android/build/outputs/apk/debug/app-android-debug.apk
```

生成器只读取指定的依赖报告和 exact Gradle cache；不调用 Gradle、不下载二进制，
许可证回退只访问脚本白名单中的 Apache、Microsoft/ONNX Runtime、Bouncy Castle
和 Mozilla 官方 HTTPS 来源。默认 `--check` 会验证三个固定的 generated-assets 根；
也可以重复传入 `--generated-assets`，或用 `--artifact` 检查 APK/AAB/ZIP 中的
`assets/` 边界。检查器逐项核对 Maven sidecar、native/model legal files、payload
和 index hash；任何边界或材料缺失都会 fail closed。本脚本不构建 APK、不运行
`generateDebugSbom`，也不把本地 debug 包或自动化结果写成设备验收或发布结论；
主流程仍负责生成并复核目标 APK/AAB。
