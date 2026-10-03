<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# ADR 0020：选中知识库的导入任务显示索引

日期：2026-10-03。状态：采用；迁移与查询计划证据见本轮优化报告。

## 背景

Knowledge 页面已将全局任务物化改为按选中知识库绑定参数筛选。缺少匹配索引时，SQLite 仍会扫描整个 import_jobs 并排序；减少返回行数不能证明 SQL 访问成本已按选中范围缩小。

## 决定

Schema v29 在现有迁移事务内增加非唯一索引 idx_import_jobs_kb_updated ON import_jobs(kb_id, updated_at DESC)，允许一次前向范围读取满足库筛选与更新时间倒序。相同时间戳仍按索引隐含 rowid 前向读取，与旧全表读取稳定排序的当前行为相符；不把这一实现事实扩大为其它查询的通用排序协议。

使用 CREATE INDEX IF NOT EXISTS，首次创建、旧 v28 升级和重复打开共用原迁移入口。版本号只在全部 DDL 与原有完整验证通过后更新；失败保留原数据和版本，不清库或吞掉异常。旧版名字碰撞处理与线程绑定投影沿用升级前 current < 28 的截止版本；v28 增加索引不能重新推导用户未绑定线程的工作区，授权执行仍用原完整校验。

索引仅加速显示读取，不修改任务、同意、UNKNOWN_OUTCOME、失败恢复或 Provider 派发事实。全局 listJobs 保留原行为；此索引不承诺加速全局排序或等待视觉统计。

## 影响与验证

每次任务写入需要维护一个额外 B-tree，数据库占用有所增加；维护开销尚未做设备计时。采用复合索引以同时覆盖筛选和排序；只按 kb_id 索引仍需选中任务临时排序。

合成 v28 fixture 删除唯一新增索引并还原版本号；它不是用户原始数据库。验证 2,003 条任务升级前后全部字段、相同时间戳顺序、真实 SQLite EXPLAIN QUERY PLAN 使用命名索引且无全表扫描/临时排序；空库、重复升级、索引 DDL 后失败回滚与可重试、未绑定线程与 grant 保留也必须通过。执行独立迁移复审及严格 check/reviewGate/许可门禁。物理设备、真实原库升级、帧耗时、长稳和真实收费 Provider 验收单独记录。
