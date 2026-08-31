<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 许可证与防误改政策

<!-- LICENSE_POLICY_AGPL_ONLY: DO NOT REMOVE -->
**本项目所有第一方源代码和项目文档采用GNU Affero General Public License version 3.0 only，SPDX为`AGPL-3.0-only`。** 不采用`AGPL-3.0-or-later`或已弃用的模糊标识。其准确含义见[SPDX正式条目](https://spdx.org/licenses/AGPL-3.0-only.html)。

许可证完整正文见[LICENSE](LICENSE)及[LICENSES/AGPL-3.0-only.txt](LICENSES/AGPL-3.0-only.txt)。标准许可证附录中的示例措辞不修改；本项目的明确选择由本政策和第一方文件SPDX声明为only。

## 1. 归属边界

第一方范围包括Android、KMP、Python宿主/自有SDK、公告Worker/管理端、构建/测试/CI脚本和文档。文件带SPDX许可标识及准确版权归属；初始项目文档使用集体贡献者标记`mobileAgentRuntime contributors`，不据此转移作者权利或指定尚未确认的法人。

第三方依赖、vendored代码、模型权重、数据集、外部Skill和用户知识库**不自动重新许可**。保留其原版权/许可证/notice，逐项核查与目标分发方式的兼容性；不以“允许MIT依赖”推导任何其他依赖都可用。用户文件不应提交至仓库。

不得把所有文件统一加AGPL以覆盖第三方。不能将用户导入Skill里的MIT字样当作项目被重许可；扫描必须基于路径归属和真实SPDX声明，不是全文字符串黑名单。

## 2. 当前事实与M0目标

M0 许可防线已经建立：`LICENSE`、`LICENSES/AGPL-3.0-only.txt`、本政策、`REUSE.toml`、`CONTRIBUTING.md`、`AGENTS.md`/`agent.md`、`CODEOWNERS`、`.github/workflows/license-guard.yml` 与 `build-logic/license-guard/` 均存在。当前本地证据为 licenseGuard 正/反向、REUSE 514/514、Actions pin、28 个 dependency lockfile、root+included-build strict dependency verification、CycloneDX SBOM/provenance 和 runtime notices 成品校验全部通过；准确命令见 [v2 最终证据](docs/evidence/2026-08-31/authority-tooling-v2-final.md)。

本地 PASS 不能替代 GitHub 服务端 Ruleset/CODEOWNER 实际配置；远程保护状态必须以对应 run/ruleset 证据单列。当前产品 WIP 保持未提交，也没有利用历史远程授权绕过本地门禁。

REUSE对可注释文件使用逐文件header；不可注释资产使用`.license`或精确`REUSE.toml`归属。许可证文本本身保留原文，不加项目版权header。[REUSE规范](https://reuse.software/spec/)说明文件级归属方式；实际是否通过必须运行工具验证。

## 3. licenseGuard最低实现

使用构建链内Kotlin实现，不依赖开发机额外Python。所有常规check依赖licenseGuard；Worker/admin也在同一第一方范围，不遗漏TypeScript、JSON配置、Markdown或Python SDK。

检查许可证正文锁定SHA、两份文本一致、第一方SPDX准确、元数据许可准确、政策/Agent不可变marker存在、About/source信息存在。M0在最小App中提供真实静态许可/来源声明，后续发布前再校验完整版本与产物关联，不用空函数绕过。

保护实现、预期哈希、CI和归属清单本身也受CODEOWNERS/Ruleset保护；仅在同一可改文件中保存expectedHash不能防止整体篡改。

排除生成产物如`.git/`、`.codegraph/`、`.gradle/`、`build/`、`node_modules/`。第三方目录只排除第一方AGPL断言，仍检查其原许可归属；只对精确目录/fixture登记豁免，不用宽泛`test/**`跳过自有测试。

反向测试在临时fixture执行：改第一方SPDX为MIT、替换许可证、删除header、改变许可元数据必须失败；合法第三方MIT必须通过其归属检查。不能为了测试破坏工作区真实LICENSE。

## 4. 提交、CI与远程规则

每次提交/推送前运行`gradlew.bat licenseGuard`、`gradlew.bat check`及`reuse lint`，CI同时生成依赖许可报告和CycloneDX SBOM。任务缺失或检查未运行不能写PASS，更不能用文档检查替代。

所有者确认后配置CODEOWNERS保护许可证、归属清单、Agent规则、CI和guard；main要求PR、required checks、code-owner review，推新提交后旧approval失效，禁止force-push/删除和Agent bypass。实际支持能力取决于远程仓库配置，必须用可验证证据证明；仓库文件不能代替服务端规则。

当前仓库已有远程和历史 CI 证据，但本轮没有新的 commit/push、Ruleset 或 CODEOWNER 身份变更授权。远程 CI/Ruleset 只能凭相应 GitHub 证据记为有效；本地 dirty WIP 的 PASS 不自动成为远程 PASS。本轮不创建绕过检查要求的提交，也不沿用旧授权推送当前产品变更。

## 5. 发布与变更

APK及公告服务/管理端提供对应源代码入口、构建commit/tag、AGPL完整文本、第三方notice与SBOM。按实际分发/网络服务方式履行适用许可义务，不把许可证描述当法律保证；有兼容性疑问时先核查，不改成MIT规避。

不得替换、弱化或移除许可、防线、SPDX或相关规则。仅仓库所有者在当前任务中的明确书面许可变更指令可以开启变更流程；模板、旧对话、依赖许可、自动生成默认值不是授权。

## 6. 标准文本来源与锁定

标准文本从[GNU AGPL v3正文](https://www.gnu.org/licenses/agpl-3.0.txt)取得，两份逐字节一致；本轮实际SHA和获取结果记录在[文档核查记录](docs/DOCUMENTATION_CHECK.md)。M0把该已复核hash纳入独立受保护的guard基线，不自行重排、翻译或替换标准正文。
