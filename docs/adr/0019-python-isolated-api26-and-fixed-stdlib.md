<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR 0019：隔离 Python 的 API26 构建与固定标准库

日期：2026-10-02。状态：采用；设备证据见本轮附件修复报告。

## 背景

附件 PY-001 的 API26/x86_64 调用在 isolated UID 下于 CPython 初始化前 SIGSYS。本轮在全新 API26 模拟器复现；上游 mimalloc 构造过程使用该隔离身份不允许的 legacy SYS_open。原报告 syscall 文本与反汇编矛盾作为原始证据保留，不能据此取消隔离。

官方嵌入归档含纯 Python 标准库和独立原生模块。原 APK 未注册 csv、math、binascii 等所需模块；兼容分析器已将相关标准库列为可支持。另有已合法接受的 DEFLATED Skill 在只支持 STORED 的原生 ZIP 读取器中不能执行。

## 决定

1. 仅 x86_64 核心由固定 CPython 3.14.7 原始源包及 NDK r27d/API26 构建，使用 --without-mimalloc、常规 GIL 和 pymalloc，不修改上游源码。不改变 isolatedProcess、权限、预算、审计或 Broker 校验。arm64-v8a 保留已固定的官方核心。
2. 将两 ABI 上游归档内 12 个固定模块 math、_csv、binascii、_struct、_random、unicodedata、_sha1、_sha2、_sha3、_md5、_blake2、zlib 按固定 SHA256 校验，随应用链接并在初始化前注册为 builtins。继续拒绝用户 Skill 的 .so、动态依赖、任意 native 加载、网络/进程入口；不支持完整 PyPI。
3. 原始 ZIP SHA256 仍是安装、grant、ticket、Broker 和审计的包身份。完整结构、路径、重复条目、symlink、CRC、压缩和解压预算校验后，Host 为单次调用生成至多 32 MiB 的 STORED 私有副本；副本具有独立 runtimeArtifactHash。Service 在 ACK 前以只读 FD 校验该 hash，JNI 在 CPython 前再次校验。使用 pread，避免改变共享 FD 游标。
4. START 私有消息增加运行副本 digest；双方随同一 APK 发布，旧 Parcel 读取失败时 fail closed，不承诺混合版本进程互通。现行协议 VERSION=1 不用于跨 APK 协商。
5. 保留原官方归档与 Sigstore 固定校验。派生核心记录源包、NDK、构建配置、固定输入二进制 hash 和可复现配方；两次独立构建输出一致。PSF、HACL MIT、BLAKE2 CC0 原文与完整 CC0 条款随源码和 APK 提供。Android stripping 后的实际原生文件 hash 由最终 APK SBOM 记录，不能冒称原始输入 hash。

## 替代方案与影响

不采用主进程 Python、放宽 seccomp/isolated UID、关闭校验、二进制 syscall 打补丁或拒绝 API26 作为修复。固定模块扩大受审查应用运行时，维护者更新版本时须同时更新原始归档、registry、许可 inventory、兼容核心配方/输入和验证证据，不能自动刷新 hash 接受未知文件。

## 验证

独立只读审查核对普通 GIL 构建 ABI、24 个模块固定 hash、导入动态符号、固定 DT_NEEDED、16 KiB ELF 对齐及法务元数据。设备测试需证明 JSON 新 PID/无状态污染、STORED/DEFLATED 等价、真实标准库结果、原包篡改拒绝、副本篡改在 ACK 前拒绝、取消/超时真实 worker 死亡及 Broker 原身份校验。模拟器通过不能替代真实 Provider、USB/Shizuku 设备、长跑或所有桌面模块验收。
