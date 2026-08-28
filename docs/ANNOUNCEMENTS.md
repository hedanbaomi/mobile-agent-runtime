<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# 公告系统实现契约

状态：M2 本地实现已落地（Worker/Admin/验签缓存/客户端展示），对应 R13—R15、N01—N09。公告独立于用户模型/API Key；不能等 Python 完成才补。Worker、D1 逻辑表、管理端与占卜产品完全隔离。**当前不创建、不绑定、不部署 Cloudflare 生产资源**；本地 PASS 不等于已部署。

## 1. 职责与展示

Android负责定期拉取、验签、缓存、本地定向复核、已读/关闭/确认、Compose展示；共享模块负责DTO、版本/语言/灰度规则；Worker负责发布、定向、快照、签名和事件；D1负责版本与审计；管理端负责编辑、预览、发布和统计。

| 类别 | 值 |
| --- | --- |
| category | GENERAL、FEATURE、MAINTENANCE、SERVICE_INCIDENT、UPDATE、SECURITY、DEPRECATION |
| severity | INFO、NOTICE、WARNING、CRITICAL |
| displayMode | CENTER_ONLY、BANNER、MODAL |
| status | draft、scheduled、published、withdrawn、archived |

类别、严重程度和展示方式独立。公告中心固定入口：未读/全部/历史/详情、单条和全部标已读。横幅关闭后同一revision不再重复。重要弹窗必须有可见确认按钮，确认后同revision不再弹出；普通推广不得使用强制确认。

实施约束：`mustAcknowledge=true`只允许WARNING/CRITICAL且MODAL，必须存在确认动作；`dismissible=false`不能去掉确认后的退出路径。即使重要公告也不能在离线、服务失败或坏内容时锁死App。一次展示一个弹窗，按severity、pinned、发布时间、ID稳定排序。

`opened/read`、`dismissed`、`acknowledged`分别记录，不把“列表可见”当作已读，也不把“标全部已读”当作重要公告确认。revision更新后新状态独立；旧修订状态保留，不能覆盖新修订。

## 2. 数据契约

公开对象：id、revision、category、severity、displayMode、title、summary、bodyMarkdown、mustAcknowledge、dismissible、pinned、target、actions、image、startsAt、endsAt、publishedAt、locale。UTC时间，revision正整数，整数versionCode，rolloutPercent为0—100。

| D1逻辑表 | 必要字段和约束 |
| --- | --- |
| announcements | id主键、currentPublishedRevision（可空）、status、createdAt、updatedAt；只存身份/当前发布指针 |
| announcement_revisions | announcementId+revision主键；revisionStatus、不可变正文/target/actions/time/rolloutSalt；修订必须递增；草稿状态与发布指针分离；对draft/scheduled的announcementId建立部分唯一索引 |
| announcement_translations | announcementId+revision+locale主键；title/summary/bodyMarkdown；必须有default |
| announcement_receipts | installIdHash、announcementId、revision、eventType、actionKey、firstAt、lastAt、count；前五项联合唯一，actionKey非空且非点击事件用空串 |
| install_state | installIdHash主键、platform/channel/versionCode/locale、firstSeenAt/lastActiveAt/lastCountedActivityAt；只含同意统计实例 |
| event_dedup | eventId主键、receivedAt；限时幂等，不保存内容类payload |
| admin_audit_log | id、actor、action、announcementId/revision、timestamp、before/after摘要、requestId；不存管理员secret |
| feed_state | singleton发布序列、内容版本、keyId；与变更事务协调 |

该拆表是对对话SQL的实施规范化，避免主表正文和translation互相冲突。已发布正文不得PATCH原地修改，需创建新revision再发布；仅草稿可编辑。编辑/定时安排新草稿不移动currentPublishedRevision，旧版仍可公开读取；新修订正式发布才原子切换，禁止较小revision覆盖较大revision。每个公告至多保留一个待发布修订，冲突需管理员明确取消/替换。withdrawn立即撤销该公告所有修订的主动展示资格；archived保留历史但不继续分发横幅/弹窗。

定时发布采用持久scheduled状态、服务端UTC时间判断和调度任务推进；公共查询自身也检查生效时间，不能因调度延迟泄露未到时间的公告。发布/撤回、审计与feed序列以同一批原子写入完成，并用期望revision/version防并发覆盖；实现期验证D1选用API的真实事务语义。

数据库约束必须落到迁移，不能只靠管理端先查后写：`UNIQUE(announcementId) WHERE revisionStatus IN ('draft','scheduled')`；发布时新修订变published、旧修订变superseded、发布指针/审计/feed序列一起更新。重复创建待发布修订冲突409。若目标D1版本/事务API不能实现该方式，改用同等强度的单槽pendingRevision与CAS事务并提供并发测试，不能降为仅UI限制。

事件入库必须将event_dedup首次插入与receipt原子upsert绑定：重复eventId不再递增count；首次事件按联合唯一键令firstAt取最早、lastAt取最晚、count加一。发生冲突/回滚时不能只写去重标记却丢计数，也不能重复计数。app_active的lastCountedActivityAt采用带6小时间隔条件的原子更新，只有满足条件的请求产生计数；lastActiveAt记录最近已接收活动供活跃窗口查询，两者不可混用。N01/N08必须用并发请求证明这些约束。

Android本地保存已验签feed、ETag、lastFetchedAt、lastAttemptAt、时钟偏移，以及按`(id, revision)`记录的readAt、displayedAt、dismissedAt、acknowledgedAt。缓存和状态事务更新，更新App不清空历史。

## 3. 公开API

| 路由 | 行为 |
| --- | --- |
| GET /api/v1/announcements | 当前请求维度的完整有效快照，200或合法304 |
| POST /api/v1/events | 自愿统计事件批次；白名单、去重、限流；无用户内容 |

GET查询：platform、channel、versionCode、locale。请求头`X-Install-ID`为本安装随机UUID，用于稳定灰度；`If-None-Match`为同一缓存键ETag。请求不是认证，公开接口不得将installId视为可信用户身份。

响应中不含草稿、未到期发布内容、管理身份或密钥。完整快照包括`items`与必要的withdrawn tombstone；明确`complete=true`，首版不分页。响应超出限额返回错误并保留旧缓存，不截断后仍声称完整。生产内容限额先由压测确认，初值：单feed解码后≤1 MiB、≤100项、单正文≤32 KiB、每项≤4动作。

完整同步成功后：撤回项删除/隐藏正文并保留本地状态；其他不在当前快照的旧项只允许历史展示，不再主动弹出。历史记录来自本机曾收到的公告，不从公开接口泄露所有定向历史。离线撤回不能即时送达，客户端最多在下一次成功同步获知，文案不承诺实时强制下线。

错误返回：400参数/版本范围无效、413体积超限、429带Retry-After、5xx暂时失败；任何错误不覆盖上一个有效缓存，不阻止聊天/知识库/Skills。

## 4. 传输、签名与缓存

HTTPS必需。签名信封是本轮工程补充，用于防伪造/错误缓存，不是远程授权入口。采用Ed25519，私钥只在服务端Secret，客户端只内置公开key ring。最低API26的验签库/实现需M2固定并测试，不能假定系统原生一定提供算法。

外层JSON包含`schemaVersion=1`、`keyId`、`payloadBase64`、`signatureBase64`。payload是UTF-8 JSON原始字节的标准Base64（无换行）；签名输入为UTF-8的固定前缀`MAR-ANNOUNCEMENTS-V1\n`拼接payloadBase64。验签使用原始字节，不重序列化JSON；先限制尺寸和keyId，再验签，再严格解析schema。

payload最少包含：feedVersion（单调递增）、issuedAt、expiresAt、requestTarget（platform/channel/versionCode/locale）、audienceHash（安装UUID的SHA-256）、complete、items、withdrawn。验签后校验target/audience匹配、有效时间、非降级feedVersion和数据范围。未知key、坏签名、错audience、未知主schema一律拒绝，不能回退不验签。

工程初值：有效期最长24小时；使用已验签server时间与本地单调时钟估计时效，处理用户改钟与重启。缓存到期后仍可在公告中心以“离线历史”查看，停止新的主动弹窗/横幅。关键安全通知不是离线强制控制系统。

ETag必须对应该请求维度/灰度身份/发布版本/内容快照；服务端处理If-None-Match前重新计算定向和生效窗口。使用`Cache-Control: private`或等价禁止共享CDN按仅URL复用个性化结果，不能忽略X-Install-ID产生串流。

304只在本地已有同key已验签且未到期快照时有效，不延长签名expiresAt；签名将过期、定时状态改变或定向改变时返回新签名200。无本地快照的304触发一次无条件GET；失败则保持可用旧状态，不循环请求。

密钥轮换先通过App版本提供新公钥重叠期，再切服务端keyId。不能信任未经旧可信key验证的远程新公钥；具体轮换/吊销runbook在生产授权前建立。

## 5. 定向和语言

平台：android/desktop/ios/all；渠道：stable/beta/nightly/all；整数min/maxVersionCode闭区间；locales可限制受众。处理顺序为基础合法性 → 发布/时间 → 平台/渠道/版本/locale → 灰度 → 语言正文。缺默认翻译时发布校验失败，不给用户空白弹窗。

跨端稳定灰度算法（工程具体化）：UTF-8 JSON数组`[announcementId, rolloutSalt, installId]`，紧凑编码；SHA-256前8字节按大端无符号整数取模100，bucket < rolloutPercent命中。不使用语言runtime的hashCode或浮点转换。Kotlin与Worker必须用同一黄金向量；salt不变则安装分组稳定，0%无人、100%全命中。

规范化：announcementId/rolloutSalt使用ASCII字母、数字、点、下划线、连字符；installId为小写标准UUID，非法输入返回400。数组无空白、无换行。实现取模时只遍历哈希前8字节，执行`bucket = (bucket * 256 + unsignedByte) % 100`，避免JavaScript整数精度和Kotlin有符号Long溢出。

以下为本轮用Python标准库计算的固定测试向量，rolloutPercent=30时前者不命中、后者命中；后续必须在Kotlin与Worker分别复现，当前未做双端运行验证：

| 紧凑JSON输入 | SHA-256 | bucket |
| --- | --- | --- |
| `["security-demo","stable-salt","00000000-0000-4000-8000-000000000001"]` | `73dcff3b2ca160a42ae52255f37fab3b237878890b4bc2cde4558d93ada7a7ec` | 44 |
| `["security-demo","stable-salt","00000000-0000-4000-8000-000000000002"]` | `709b46d79f19e04305e176d9458fd5953122649c7e2a0eb2bb2fe293c536fee3` | 27 |

locale标准化为BCP-47；例如zh-Hans-CN → zh-CN → zh-Hans → zh → default。定向locale和翻译回退是两个概念，不能因为存在default翻译就绕过locale受众限制。

客户端按同一规则复核，服务端筛选不能成为唯一的防错线。版本、语言或渠道变更重新生成缓存键，无条件取新快照，已读修订记录仍保留。

## 6. Android拉取与动作

冷启动、前台恢复距上次自动检查≥6小时、更新后首次启动、手动刷新公告中心触发。冷启动和普通恢复均遵守6小时节流；更新后首次启动与用户手动刷新可以越过客户端自动节流，但仍受服务端限流。请求失败采用有上限退避，不每次生命周期回调重试。

网络不可用继续展示未过期有效缓存；图片失败不影响正文。渲染成功才记displayed，不因fetch自动打已读。退出强制弹窗必须是用户点击确认；确认持久化成功后关闭，下次不重复。

动作白名单：OPEN_HTTPS_URL、OPEN_APP_ROUTE、DISMISS、ACKNOWLEDGE。内部路由仅`app://settings/providers`、`app://settings/knowledge`、`app://announcements`、`app://about`、`app://update`。外部链接/图片只能HTTPS且满足客户端受控域名和大小规则，显示目的域；禁止任意intent/file/javascript/自定义scheme。

Markdown关闭原始HTML、脚本、远程嵌入，图片不带Provider认证头，重定向同样校验。公告内容、按钮、图片任何形式都不能调用Python/Skill、授予权限、执行迁移、自动重建索引或静默安装APK。诸如“重建索引”只能导航到知识库设置，由用户另外确认。

更新页由独立Update用例负责。若该用例支持下载，必须校验SHA-256、包名、签名与版本，再交系统用户确认安装；未实现更新器时只显示版本/可信发布页，不让公告绕过。

## 7. 匿名统计与隐私

首次安装生成随机UUID，不使用Android ID、IMEI、广告ID、MAC、联系人、账户或指纹。随机ID仍可关联安装活动，不宣称绝对匿名。统计默认关闭是本轮隐私默认；用户可开启/关闭/重置。关闭统计清除待上传事件，不关闭公告读取。

关闭统计时GET仍可能携带用于灰度的安装ID，隐私说明须明示用途；服务端不得把此请求自动写入install_state/receipts，也不把原始ID写访问日志。启用统计后才发送install_seen、app_active、announcement_fetched/displayed/opened/acknowledged/action_clicked。服务端存应用域隔离的installIdHash，不跨产品关联。

事件白名单字段：eventId、type、installId、platform/channel/versionCode/locale、announcementId、revision、actionId、occurredAt。禁止自由文本、任意属性、完整URL或请求内容；对多余字段拒绝或明确删除并计数。缺失同意状态不上传，撤回同意后不重放旧队列。事件入口公开故只能作为近似产品统计，不当安全审计身份。

批次默认≤50事件/64 KiB。eventId用于网络重试去重；app_active按同安装上次已计活动时间起连续6小时去重，避免固定时段边界双计。统计`active_24h/7d/30d`依据服务端接收时间窗口的去重安装数，不能把事件条数当活跃人数。

公告覆盖/拉取/展示/打开/确认人数均按同意统计的已观测安装样本计算；“符合条件安装数”只是近期有状态样本的估计，不是全部安装总数。UI说明口径、时间范围与关闭统计造成的偏差。工程初值：明细30天、管理审计180天，生产前由所有者确认保留期并实现删除/聚合策略。

绝不上传聊天、System Prompt、模型参数/API Key、知识库文件名/内容、Skill输入输出或用户请求。公告与Provider网络栈至少使用不同凭据域和Header构建器，防止共享拦截器泄漏认证。

## 8. 管理端和部署门槛

管理页`/admin/announcements`，提供草稿编辑/Markdown预览、类别/等级/展示、定向、时间、灰度、动作、修订、发布、撤回、归档、统计和审计。发布前预览Android小屏弹窗、横幅、详情、深浅色和超长文本。

管理API至少包括GET列表/详情、POST新建、PATCH草稿、POST revisions、POST schedule/publish/withdraw/archive；写入要求认证、期望revision和幂等requestId，冲突409。已发布正文只能新修订；服务端授权不能只靠隐藏入口。

优先Cloudflare Access管理员认证，Worker校验对应签发者/audience/有效期；没有Access的本地测试使用独立测试身份适配，不能在生产偷偷沿用。浏览器cookie认证需要CSRF保护和精确Origin规则；CORS不是身份鉴别。管理员凭据、Cloudflare token、签名私钥不进APK/仓库/日志。

本地Worker/D1 fixture先验证迁移、定时、发布和客户端验签。测试与生产独立绑定，禁止默认复用占卜项目资源。部署前确认账户/数据库/域名/secret来源，保存迁移备份和回退计划；发布应用/后端代码还需许可、source revision和审计证据。没有远程授权时交付本地验证结果，不把它标为已部署。

## 9. M2 本地运行（非生产）

验签库固定为 BouncyCastle `bcprov-jdk18on`（API 26 不依赖系统 Ed25519）。签名输入为 `MAR-ANNOUNCEMENTS-V1\n` + `payloadBase64`。服务端 Node `node:crypto` Ed25519 与客户端验签使用同一测试种子黄金向量。

本地 Worker 使用内存 store 实现与 `schema.sql` 相同的待发布修订冲突、审计和 feed 版本语义；未执行 `wrangler deploy`，未创建生产 D1。

```bash
cd services/announcements
set MAR_ADMIN_TOKEN=replace-with-local-test-token
node src/local-server.mjs
```

管理页：`http://127.0.0.1:8787/admin/announcements`。进程会打印 `MAR_ANNOUNCE_PUBLIC_KEY_HEX`（公钥，不是私钥）。Android 调试包在公告页填写该 URL 与公钥后手动刷新；debug 构建仅允许 `10.0.2.2`/`127.0.0.1`/`localhost` 明文，release 仍要求 HTTPS。统计开关默认关闭。

协议测试：`node src/rollout.test.mjs` 与 `node src/worker.test.mjs`。
