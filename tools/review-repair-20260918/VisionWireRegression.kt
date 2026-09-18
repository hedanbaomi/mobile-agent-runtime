// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

    @Test
    fun reviewSlicesAreBoundedAndUnicodeSafeOnBothWireProtocols() {
        val sources = listOf(
            "A" + " ".repeat(30_000) + "B",
            "甲".repeat(4_000) + "😀" + "乙".repeat(4_000),
        )
        for (format in ApiFormat.entries) for (source in sources) {
            val pair = target("review-wire", "review-wire-model")
            val selected = pair.first.copy(apiFormat = format) to pair.second
            val intercepted = mutableListOf<String>()
            val engine = MockEngine { request ->
                val root = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
                val messages = (root["messages"] ?: root["input"])!!.jsonArray
                val content = messages.first().jsonObject["content"]!!.jsonArray
                val text = content.first { it.jsonObject["text"] != null }.jsonObject["text"]!!.jsonPrimitive.content
                intercepted += text.substringAfter("<context>").substringBefore("</context>")
                val response = if (root["input"] != null) {
                    """{"id":"review-response","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\"ocrText\":\"ocr\",\"semanticDescription\":\"description\",\"tableMarkdown\":\"\",\"type\":\"image\"}"}]}],"usage":{"input_tokens":5,"output_tokens":2}}"""
                } else successBody()
                respond(response, HttpStatusCode.OK, jsonHeaders())
            }
            val vision = backend(listOf(selected), engine)
            val units = runtime.mobileagent.knowledge.DocumentUnitPlanner().plan(
                "review-wire-source", listOf(runtime.mobileagent.knowledge.PlanningPage(1, source, true)),
            )
            for (unit in units) {
                val result = vision.process(input(selected).copy(surroundingText = unit.effectiveRequestText()))
                assertTrue(result is VisionOutcome.Success, "$format: $result")
            }
            assertEquals(units.size, intercepted.size)
            assertTrue(intercepted.all { it.length <= runtime.mobileagent.knowledge.DocumentUnitPlanner.MAX_REQUEST_TEXT_CHARS })
            assertEquals(source, intercepted.joinToString(""))
            assertEquals(source, intercepted.joinToString("") { it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) })
        }
    }

