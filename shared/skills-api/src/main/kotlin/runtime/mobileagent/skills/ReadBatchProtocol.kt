// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Input, schema and eligible names for [ReadBatchToolExecutor]. All dispatches still pass the delegate's live guards. */
object ReadBatchProtocol {
    data class Child(val callId: String, val tool: String, val arguments: JsonObject)

    internal fun eligibleReadTools(requested: Set<String>, specs: Map<String, ToolSpec>): Set<String> =
        requested.filterTo(linkedSetOf()) { name -> specs[name]?.sideEffect == false && name !in RunControlTools.ALL }

    internal fun spec(names: Set<String>, maxCalls: Int) = ToolSpec(
        name = RunControlTools.READ_BATCH,
        description = "Run up to $maxCalls eligible read tool calls in one explicit batch. " +
            "Results keep the requested order. A batch never approves a call: a child that needs approval " +
            "is reported as NEEDS_APPROVAL.",
        parametersJson = schema(names.sorted(), maxCalls),
        capability = "",
        sideEffect = false,
    )

    private fun schema(names: List<String>, maxCalls: Int): String {
        val enum = names.joinToString(separator = ",") { "\"$it\"" }
        return """{"type":"object","additionalProperties":false,"required":["calls"],""" +
            """"properties":{"calls":{"type":"array","minItems":1,"maxItems":$maxCalls,""" +
            """"description":"Read calls to run together, in order.",""" +
            """"items":{"type":"object","additionalProperties":false,""" +
            """"required":["call_id","tool","arguments"],"properties":{""" +
            """"call_id":{"type":"string","minLength":1,"maxLength":128},""" +
            """"tool":{"type":"string","enum":[$enum]},""" +
            """"arguments":{"type":"object","additionalProperties":true,""" +
            """"description":"Arguments object passed unchanged to the selected tool."}}}}}}"""
    }

    /** Only a strictly formed batch with distinct IDs can be auto-approved as a whole. */
    fun childrenForApproval(raw: String): List<Child>? {
        val children = mutableListOf<Child>()
        val error = visitChildren(raw, Int.MAX_VALUE) { _, child ->
            children += child
            null
        }
        if (error != null || children.distinctBy { it.callId }.size != children.size) return null
        return children
    }

    /** Validate in request order. Execution may reject a child for exceeding its argument budget. */
    internal fun visitChildren(raw: String, maxCalls: Int, visit: (Int, Child) -> String?): String? {
        val args = parseObject(raw) ?: return "Tool arguments are incomplete JSON"
        unknownKeys(args, rootKeys)?.let { return it }
        val calls = args["calls"] as? JsonArray ?: return "calls must be a JSON array"
        if (calls.isEmpty()) return "calls must not be empty"
        if (calls.size > maxCalls) return "calls exceeds the per-batch limit of $maxCalls"
        calls.forEachIndexed { index, element ->
            val child = element as? JsonObject ?: return "calls[$index] must be a JSON object"
            unknownKeys(child, childKeys)?.let { return "calls[$index]: $it" }
            val id = child.nonBlankString("call_id") ?: return "calls[$index].call_id must be a nonblank string"
            val tool = child.nonBlankString("tool") ?: return "calls[$index].tool must be a nonblank string"
            val arguments = child["arguments"] as? JsonObject ?: return "calls[$index].arguments must be a JSON object"
            visit(index, Child(id, tool, arguments))?.let { return it }
        }
        return null
    }

    private fun parseObject(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun JsonObject.nonBlankString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun unknownKeys(obj: JsonObject, allowed: Set<String>): String? {
        val unknown = obj.keys - allowed
        return if (unknown.isEmpty()) null else "Unknown parameter: ${unknown.sorted().joinToString()}"
    }

    private val rootKeys = setOf("calls")
    private val childKeys = setOf("call_id", "tool", "arguments")
}
