// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Deterministic, self-authored business data; no downloads, random pixel noise or padding. */
internal object KnowledgeLoadDeviceFixtures {
    const val KIND = "k06-storage-waiting-uncompressed-png-v1"
    const val IMAGE_COUNT = 300
    const val REPORT_COUNT = 20
    const val FILE_COUNT = IMAGE_COUNT + REPORT_COUNT
    const val WIDTH = 1024
    const val HEIGHT = 512
    private val warehouseNames = listOf("青岚", "海晟", "云川", "松岳", "星浦", "澄湖", "锦河", "岚桥", "碧洲", "望津", "杉港", "远溪", "霁峰", "晴湾", "栖谷", "银汀", "柏林", "兰泽", "沐原", "新岑")

    data class Entry(
        val name: String,
        val mime: String,
        val kind: String,
        val bytes: Long,
        val sha256: String,
        val caseIndex: Int,
        val keyword: String,
        val businessDataSha256: String,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("name", name); put("mime", mime); put("kind", kind); put("bytes", bytes)
            put("sha256", sha256); put("caseIndex", caseIndex); put("keyword", keyword)
            put("businessDataSha256", businessDataSha256)
            put("license", "AGPL-3.0-only"); put("author", "mobileAgentRuntime contributors")
            if (kind == "visual") {
                put("width", WIDTH); put("height", HEIGHT); put("businessCells", 128 * 64)
                put("pngCompression", "stored-deflate-level-0")
                put("expectedStage", "WAITING_FOR_VISION_MODEL")
            } else put("expectedStage", "READY-with-real-local-ONNX")
        }
    }

    fun generate(root: File, progress: (Entry) -> Unit, checkDeadline: () -> Unit): List<Entry> {
        val sources = File(root, "sources").apply { check(mkdirs()) }
        val entries = mutableListOf<Entry>()
        // Reports first so manifest order also groups the bounded local inference workload.
        repeat(REPORT_COUNT) { index ->
            checkDeadline()
            val file = File(sources, "report-${index.toString().padStart(3, '0')}.md")
            val report = report(index)
            file.writeText(report, Charsets.UTF_8)
            val hash = file.inputStream().use(::sha256)
            val entry = Entry(file.name, "text/markdown", "text", file.length(), hash, index, warehouseNames[index] + "仓库", hash)
            check(entry.bytes in 8_000L..32_000L) { "Report must contain substantial bounded business content" }
            entries += entry
            progress(entry)
        }
        repeat(IMAGE_COUNT) { index ->
            checkDeadline()
            val file = File(sources, "dashboard-${index.toString().padStart(3, '0')}.png")
            val dataHash = dashboard(file, index)
            val entry = Entry(file.name, "image/png", "visual", file.length(), file.inputStream().use(::sha256), index,
                "Synthetic warehouse ${index + 1} demand-capacity dashboard", dataHash)
            entries += entry
            progress(entry)
        }
        check(entries.map { it.sha256 }.distinct().size == FILE_COUNT) { "Fixtures must be distinct source files" }
        check(entries.sumOf { it.bytes } in 300L * MIB..500L * MIB)
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("datasetKind", KIND); put("fileCount", entries.size)
            put("sourceBytes", entries.sumOf { it.bytes }); put("imageCount", IMAGE_COUNT); put("reportCount", REPORT_COUNT)
            put("scope", "storage/checkpoint/visual-waiting stress plus bounded real ONNX text subset")
            put("notEvidenceFor", "natural compressed corpus, 450 MiB text inference, Vision inference, full K06 acceptance")
            put("visualAuthorization", false); put("visualBackendCallsExpected", 0)
            put("content", "Self-authored synthetic warehouse observations, charts, chapters and operating decisions; no user data")
            put("license", "AGPL-3.0-only")
            put("files", JsonArray(entries.map { it.json() }))
        }
        atomicText(File(root, "manifest.json"), manifest.toString())
        return entries
    }

    fun read(root: File): List<Entry> {
        val manifest = Json.parseToJsonElement(File(root, "manifest.json").readText()).jsonObject
        check(manifest.getValue("datasetKind").jsonPrimitive.content == KIND)
        val entries = (manifest.getValue("files") as JsonArray).map { element ->
            val row = element.jsonObject
            Entry(row.text("name"), row.text("mime"), row.text("kind"), row.getValue("bytes").jsonPrimitive.long,
                row.text("sha256"), row.getValue("caseIndex").jsonPrimitive.long.toInt(), row.text("keyword"), row.text("businessDataSha256"))
        }
        check(entries.size == FILE_COUNT && entries.map { it.name }.distinct().size == FILE_COUNT)
        check(entries.count { it.kind == "visual" } == IMAGE_COUNT && entries.count { it.kind == "text" } == REPORT_COUNT)
        check(entries.sumOf { it.bytes } in 300L * MIB..500L * MIB)
        entries.forEach {
            val expectedName = if (it.kind == "text") "report-${it.caseIndex.toString().padStart(3, '0')}.md"
                else "dashboard-${it.caseIndex.toString().padStart(3, '0')}.png"
            check(it.name == expectedName)
            check(it.caseIndex in (if (it.kind == "text") 0 until REPORT_COUNT else 0 until IMAGE_COUNT))
            check(it.mime == if (it.kind == "text") "text/markdown" else "image/png")
            check(it.sha256.matches(Regex("[0-9a-f]{64}")))
            check(source(root, it).length() == it.bytes)
        }
        return entries
    }

    fun source(root: File, entry: Entry): File {
        val base = File(root, "sources").canonicalFile
        val file = File(base, entry.name).canonicalFile
        check(file.parentFile == base && file.isFile) { "Fixture source escaped its dedicated directory" }
        return file
    }

    private fun report(index: Int): String = buildString {
        val name = warehouseNames[index] + "仓库"
        appendLine("<!-- SPDX-" + "FileCopyrightText: 2026 mobileAgentRuntime contributors -->")
        appendLine("<!-- SPDX-" + "License-Identifier: AGPL-3.0-only -->")
        appendLine("# $name：合成运营分析报告 ${index + 1}")
        appendLine("本报告全部为自造测试数据，不描述真实客户、货物或人员。标识 HarborLedger${index.toString().padStart(2, '0')} 用于检索回归。")
        appendLine("## 业务目标与口径")
        appendLine("评估进货、订单、可用容量和补货缺口。需求单位为箱；窗口为两小时；容量不是概率。缺口 = max(需求 - 容量, 0)，利用率 = 需求 / 容量。数字按固定场景公式生成，每个产品和窗口可复算。")
        repeat(6) { chapter ->
            appendLine("## 第 ${chapter + 1} 章：${listOf("早班接货", "午间分拣", "晚班发运", "安全库存", "容量复核", "异常处置")[chapter]}")
            appendLine("$name 在本章跟踪产品 ${chapter * 4} 至 ${chapter * 4 + 3} 的 24 个观察窗口。计划采用有界补货，不把高需求直接解释为实际出库；记录缺口后由下一班复核容量。")
            appendLine("|窗口|产品|需求箱|容量箱|缺口箱|已计划补货箱|判定|")
            appendLine("|---|---|---|---|---|---|---|")
            repeat(24) { window ->
                val sensor = chapter * 4 + window % 4
                val period = chapter * 16 + window
                val demand = demand(index, sensor, period)
                val capacity = capacity(index, sensor, period)
                val gap = (demand - capacity).coerceAtLeast(0)
                appendLine("|${period * 2}:00|SKU-${index + 1}-${sensor + 1}|$demand|$capacity|$gap|${gap + 4 + chapter}|${if (gap > 0) "需补货复核" else "容量覆盖"}|")
            }
            appendLine("处置依据：只对正缺口安排补货，保留四箱缓冲并记录负责人班次。容量覆盖时不重复发起同一订单，下一班继续检查到货和损耗。本文记录的计划不代表已完成操作。")
            appendLine("审计问题：若同一批次重复导入，文件哈希和源文档应保持一致；若删除本仓资料，其他仓库引用的共享原件仍须可读。待处理图表没有获得视觉授权时不得标为已识别。")
        }
        appendLine("## 结论与复核清单")
        appendLine("$name 的复核必须同时查看需求、容量、缺口和补货计划，不能仅根据图例颜色给出已发运结论。保留章节编号和原始表格，用 HarborLedger${index.toString().padStart(2, '0')} 与$name 验证词法和向量检索。")
    }

    private fun demand(warehouse: Int, sensor: Int, period: Int): Int =
        18 + (warehouse * 11 + sensor * 7) % 51 + (period % 12) * 3 +
            (if ((period / 12 + warehouse) % 7 in 4..5) 24 else 0) + (if (sensor % 9 == period % 9) 15 else 0)

    private fun capacity(warehouse: Int, sensor: Int, period: Int): Int =
        55 + (sensor * 13 + warehouse * 3) % 55 + (if (period % 24 < 6) 12 else 0)

    private fun cellColor(warehouse: Int, sensor: Int, period: Int): Int {
        val pressure = (100 * demand(warehouse, sensor, period) / capacity(warehouse, sensor, period)).coerceIn(0, 160)
        return Color.rgb(35 + pressure * 210 / 160, 210 - pressure * 165 / 160, 225 - pressure * 175 / 160)
    }

    private fun dashboard(file: File, index: Int): String {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.WHITE)
            paint.color = Color.rgb(22, 38, 52); paint.textSize = 24f
            canvas.drawText("Warehouse ${index + 1}: demand / capacity", 32f, 34f, paint)
            paint.textSize = 13f
            canvas.drawText("SYNTHETIC DATA | 64 SKUs x 128 two-hour windows | red = demand pressure", 32f, 61f, paint)
            listOf(0, 100, 160).forEachIndexed { position, pressure ->
                val x = 620 + position * 118
                paint.color = Color.rgb(35 + pressure * 210 / 160, 210 - pressure * 165 / 160, 225 - pressure * 175 / 160)
                canvas.drawRect(x.toFloat(), 72f, (x + 16).toFloat(), 85f, paint)
                paint.color = Color.rgb(22, 38, 52)
                canvas.drawText("$pressure%", (x + 22).toFloat(), 84f, paint)
            }
            paint.textSize = 11f
            repeat(8) { tick -> canvas.drawText("H${tick * 32}", (80 + tick * 112).toFloat(), 102f, paint) }
            repeat(64) { sensor ->
                if (sensor % 8 == 0) {
                    paint.color = Color.rgb(22, 38, 52)
                    canvas.drawText("SKU${sensor + 1}", 22f, (117 + sensor * 5).toFloat(), paint)
                }
                repeat(128) { period ->
                    val data = "$index,$sensor,$period,${demand(index, sensor, period)},${capacity(index, sensor, period)}\n"
                    digest.update(data.toByteArray(Charsets.US_ASCII))
                    paint.color = cellColor(index, sensor, period)
                    canvas.drawRect((80 + period * 7).toFloat(), (112 + sensor * 5).toFloat(),
                        (87 + period * 7).toFloat(), (117 + sensor * 5).toFloat(), paint)
                }
            }
            paint.color = Color.rgb(22, 38, 52); paint.textSize = 13f
            canvas.drawText("Demand = base SKU demand + shift cycle + promotion + SKU-window event", 32f, 459f, paint)
            canvas.drawText("Capacity = base SKU capacity + receiving shift; no real customer or inventory data", 32f, 480f, paint)
            canvas.drawText("AGPL-3.0-only | mobileAgentRuntime contributors | scenario ${index + 1}", 32f, 501f, paint)
            writeStoredPng(bitmap, file)
        } finally {
            bitmap.recycle()
        }
        // Decode the actual file: valid PNG and rendered business pixels, not only a header fixture.
        val decoded = checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "Generated PNG failed Android decoding" }
        try {
            check(decoded.width == WIDTH && decoded.height == HEIGHT)
            check(decoded.getPixel(83, 114) == cellColor(index, 0, 0))
            check(decoded.getPixel(83 + 87 * 7, 114 + 33 * 5) == cellColor(index, 33, 87))
            var titlePixels = 0
            for (y in 12 until 36 step 2) for (x in 30 until 650 step 2) if (decoded.getPixel(x, y) != Color.WHITE) titlePixels++
            check(titlePixels > 100) { "Dashboard title must be visible" }
        } finally {
            decoded.recycle()
        }
        return digest.digest().hex()
    }

    /** PNG pixels are real RGB scanlines. Level 0 is deliberate and declared, never hidden padding. */
    private fun writeStoredPng(bitmap: Bitmap, file: File) {
        val compressed = ByteArrayOutputStream(WIDTH * HEIGHT * 3 + HEIGHT + 1024)
        val deflater = Deflater(Deflater.NO_COMPRESSION)
        try {
            DeflaterOutputStream(compressed, deflater).use { output ->
                val pixels = IntArray(WIDTH)
                val row = ByteArray(WIDTH * 3 + 1)
                repeat(HEIGHT) { y ->
                    bitmap.getPixels(pixels, 0, WIDTH, 0, y, WIDTH, 1)
                    row[0] = 0
                    repeat(WIDTH) { x ->
                        row[1 + x * 3] = Color.red(pixels[x]).toByte()
                        row[2 + x * 3] = Color.green(pixels[x]).toByte()
                        row[3 + x * 3] = Color.blue(pixels[x]).toByte()
                    }
                    output.write(row)
                }
            }
        } finally {
            deflater.end()
        }
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { it.writeInt(WIDTH); it.writeInt(HEIGHT); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
            }.toByteArray()
            pngChunk(output, "IHDR", header)
            pngChunk(output, "tEXt", "License\u0000AGPL-3.0-only; Copyright 2026 mobileAgentRuntime contributors; synthetic business data".toByteArray(Charsets.ISO_8859_1))
            pngChunk(output, "IDAT", compressed.toByteArray())
            pngChunk(output, "IEND", byteArrayOf())
        }
    }

    private fun pngChunk(output: DataOutputStream, name: String, bytes: ByteArray) {
        val type = name.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(bytes) }
        output.writeInt(bytes.size); output.write(type); output.write(bytes); output.writeInt(crc.value.toInt())
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            if (size > 0) digest.update(buffer, 0, size)
        }
        return digest.digest().hex()
    }

    fun atomicText(file: File, value: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(value)
        check(temporary.renameTo(file)) { "Cannot publish fixture metadata" }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private const val MIB = 1024L * 1024
}
