// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

/**
 * Self-authored fixed bilingual relevance pairs for deterministic local ONNX retrieval checks.
 * Each pair binds one synthetic knowledge document to one natural-language query without external
 * corpus text, keeping single-KB and four-KB RRF evaluations reproducible across clean checkouts.
 */
internal object RetrievalQualityCorpus {
    val documents = listOf(
            "购买商品七天内可以申请退款，退回原支付账户。" to "东西不满意，怎样拿回付的钱？",
            "图书馆周末上午九点开门，晚上六点闭馆。" to "星期六什么时候能去借书？",
            "密码遗忘后可通过注册邮箱重置登录凭据。" to "忘记登录密码该怎么办？",
            "月度报销需要提交发票和经理审批记录。" to "How do employees claim expenses?",
            "A domestic cat is a small feline animal that often lives with people." to "Which pet is feline?",
            "Railway trains carry passengers between stations on tracks." to "What vehicle travels on rails?",
            "Solar panels convert sunlight into electrical energy." to "How can sunlight generate power?",
            "Backups retain earlier files and can restore documents after accidental deletion." to "误删文件怎么恢复？",
            "玫瑰需要阳光和适量浇水，避免土壤长期积水。" to "如何照顾花园中的玫瑰？",
            "The conference starts on Tuesday; presentations last twenty minutes." to "When does the conference begin?",
            "借阅图书期限为三十天，到期前可以申请续借。" to "书还没看完可以延长借阅时间吗？",
            "Aircraft fly between airports and require a boarding pass." to "Where do passengers board an airplane?",
            "发票上的税号必须与公司登记信息一致。" to "开公司发票时税号填写什么？",
            "Water freezes at zero degrees Celsius under ordinary atmospheric pressure." to "At what temperature does water turn into ice?",
            "缓存采用最近最少使用策略，容量满时移除最长时间未访问的项目。" to "LRU如何选择淘汰的数据？",
            "Repair appointments can be cancelled until the previous evening." to "Can I cancel a scheduled repair?",
        )
}
