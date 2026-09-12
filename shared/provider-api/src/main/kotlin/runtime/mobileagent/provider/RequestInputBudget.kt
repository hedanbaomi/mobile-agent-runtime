// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider

import kotlinx.serialization.json.JsonElement

/**
 * Conservative size of one prepared model request, in abstract *units*.
 *
 * A unit is **not** a token. The default implementation counts one unit per
 * UTF-8 byte of model-visible text plus fixed protocol, image and
 * provider-continuation reservations. For well-formed text a standard text
 * tokenizer cannot emit more tokens than UTF-8 bytes, so the byte count is a
 * conservative upper bound; it must never be presented as real provider usage.
 *
 * [imageCount] is kept separate so callers can enforce an image budget without
 * re-walking the request.
 */
data class InputBudgetEstimate(
    val units: Long,
    val imageCount: Int,
    val basis: String = INPUT_BUDGET_BASIS,
    val protocolUnits: Long = 0L,
    val messageTextUnits: Long = 0L,
    val toolCallUnits: Long = 0L,
    val toolSchemaUnits: Long = 0L,
    val imageUnits: Long = 0L,
    val continuationUnits: Long = 0L,
)

/**
 * Default basis label for [RequestInputBudget.estimate]. Adapters that change
 * what actually reaches the wire must return their own clear label instead.
 */
const val INPUT_BUDGET_BASIS: String =
    "conservative-utf8-upper-bound: text, tool call ids/names/arguments, tool schemas and " +
        "parameter layers counted as UTF-8 bytes; fixed per-image (4096 units) and protocol " +
        "reservations; provider-private continuation counted as replayed payload bytes; " +
        "not a tokenizer count and not provider usage"

/**
 * Basis label for transports that drop [ChatMessage.providerContinuationItems]
 * (for example Chat Completions) and therefore must not reserve for them.
 */
const val INPUT_BUDGET_BASIS_WITHOUT_CONTINUATION: String =
    "conservative-utf8-upper-bound: text, tool call ids/names/arguments, tool schemas and " +
        "parameter layers counted as UTF-8 bytes; fixed per-image (4096 units) and protocol " +
        "reservations; provider-private continuation excluded because this transport does " +
        "not send it; not a tokenizer count and not provider usage"

/**
 * Deterministic, overflow-safe conservative input estimator shared by adapters.
 *
 * What is counted:
 * - every [ChatMessage.text] and [ChatMessage.role] as UTF-8 bytes;
 * - every [ChatMessage.toolCallId];
 * - every [AssistantToolCall] id, name and [AssistantToolCall.argumentsJson]
 *   (the argument JSON was previously ignored, which let tool-call history grow
 *   past the input budget unnoticed);
 * - every tool schema entry (name, description, parameters JSON, strict flags);
 * - the validated parameter layers ([ModelRequest.parameters], [ModelRequest.extra]);
 * - a fixed protocol envelope and per-message/per-tool-call envelopes;
 * - images as a fixed per-image reservation and a separate [InputBudgetEstimate.imageCount];
 * - provider-private continuation items as their replayed UTF-8 payload size.
 *
 * What is deliberately not counted or echoed:
 * - request headers, including [RequestHeaderValue.SecretRef] aliases and all
 *   secret values: authorization material is never part of the input budget and
 *   must never reach budget logs;
 * - [ModelRequest.outputTokenLimit]: the output budget is tracked separately
 *   from the input budget by callers;
 * - provider-private continuation **content** itself: only its size is
 *   measured, and the returned [InputBudgetEstimate] never contains it.
 *
 * Images are reserved at a fixed cost per image rather than summing base64
 * bytes: providers bill images by dimensions/tiles while the app enforces
 * separate attachment byte limits. The reservation is conservative for text
 * windows and is always labelled as a reservation, never as a token count.
 *
 * All arithmetic saturates at [Long.MAX_VALUE] so a pathological request can
 * never wrap into a small budget.
 */
object RequestInputBudget {

    /** Fixed request-level envelope: JSON keys, model name, stream flag, arrays. */
    private const val PROTOCOL_ENVELOPE_UNITS = 256L

    /** Fixed per-message envelope: `{"role":...,"content":...,"tool_calls":...}`. */
    private const val MESSAGE_ENVELOPE_UNITS = 32L

    /** Fixed per-tool-call envelope: `{"id":...,"type":"function","function":{...}}`. */
    private const val TOOL_CALL_ENVELOPE_UNITS = 48L

    /** Fixed per-tool-result envelope around `toolCallId`. */
    private const val TOOL_CALL_ID_ENVELOPE_UNITS = 16L

    /** Fixed per-tool-schema envelope: `{"type":"function","name":...,...}`. */
    private const val TOOL_SCHEMA_ENVELOPE_UNITS = 64L

    /** Fixed per-tool-schema-entry envelope (one `key:value` pair). */
    private const val TOOL_SCHEMA_ENTRY_UNITS = 32L

    /** Fixed per-parameter-layer entry envelope. */
    private const val PARAMETER_ENTRY_UNITS = 32L

    /** Fixed per-continuation-item envelope: `{"type":"reasoning","encrypted_content":...}`. */
    private const val CONTINUATION_ENVELOPE_UNITS = 32L

    /**
     * Keep the "(4096 units)" wording in [INPUT_BUDGET_BASIS] in sync with this
     * constant.
     *
     * Fixed reservation per inline image. This matches the existing runtime
     * budget convention (`4096` units per image) so callers keep identical
     * numbers; it is a reservation, not a tokenizer or byte count.
     */
    const val IMAGE_UNITS_PER_IMAGE = 4096L

    /**
     * Estimate the conservative input size of [request].
     *
     * @param includeProviderContinuation `true` (the default) reserves for
     *   [ChatMessage.providerContinuationItems] because the generic request
     *   shape may replay them; adapters whose transport drops the channel must
     *   pass `false` (see [ModelAdapter.estimateInput]) so the budget reflects
     *   what is actually sent.
     */
    fun estimate(
        request: ModelRequest,
        includeProviderContinuation: Boolean = true,
    ): InputBudgetEstimate {
        var protocolUnits = saturatingAddUnits(PROTOCOL_ENVELOPE_UNITS, conservativeUtf8Units(request.modelId))
        protocolUnits = saturatingAddUnits(protocolUnits, parameterLayerUnits(request.parameters))
        protocolUnits = saturatingAddUnits(protocolUnits, legacyExtraUnits(request.extra))
        var messageTextUnits = 0L
        var toolCallUnits = 0L
        var toolSchemaUnits = 0L
        var imageUnits = 0L
        var continuationUnits = 0L
        var imageCount = 0
        request.messages.forEach { message ->
            messageTextUnits = saturatingAddUnits(messageTextUnits, MESSAGE_ENVELOPE_UNITS)
            messageTextUnits = saturatingAddUnits(messageTextUnits, conservativeUtf8Units(message.role))
            messageTextUnits = saturatingAddUnits(messageTextUnits, conservativeUtf8Units(message.text))
            message.toolCallId?.let { id ->
                toolCallUnits = saturatingAddUnits(toolCallUnits, TOOL_CALL_ID_ENVELOPE_UNITS)
                toolCallUnits = saturatingAddUnits(toolCallUnits, conservativeUtf8Units(id))
            }
            message.toolCalls.forEach { call ->
                toolCallUnits = saturatingAddUnits(toolCallUnits, TOOL_CALL_ENVELOPE_UNITS)
                toolCallUnits = saturatingAddUnits(toolCallUnits, conservativeUtf8Units(call.id))
                toolCallUnits = saturatingAddUnits(toolCallUnits, conservativeUtf8Units(call.name))
                toolCallUnits = saturatingAddUnits(toolCallUnits, conservativeUtf8Units(call.argumentsJson))
            }
            imageCount = saturatingAddCount(imageCount, message.images.size)
            imageUnits = saturatingAddUnits(
                imageUnits,
                saturatingMultiplyUnits(message.images.size.toLong(), IMAGE_UNITS_PER_IMAGE),
            )
            if (includeProviderContinuation) {
                message.providerContinuationItems.forEach { item ->
                    continuationUnits = saturatingAddUnits(continuationUnits, CONTINUATION_ENVELOPE_UNITS)
                    item.itemId?.let { id ->
                        continuationUnits = saturatingAddUnits(continuationUnits, conservativeUtf8Units(id))
                    }
                    continuationUnits = saturatingAddUnits(
                        continuationUnits,
                        conservativeUtf8Units(item.encryptedContent),
                    )
                }
            }
        }
        request.tools.forEach { spec ->
            toolSchemaUnits = saturatingAddUnits(toolSchemaUnits, TOOL_SCHEMA_ENVELOPE_UNITS)
            spec.forEach { (key, value) ->
                toolSchemaUnits = saturatingAddUnits(toolSchemaUnits, TOOL_SCHEMA_ENTRY_UNITS)
                toolSchemaUnits = saturatingAddUnits(toolSchemaUnits, conservativeUtf8Units(key))
                toolSchemaUnits = saturatingAddUnits(toolSchemaUnits, conservativeUtf8Units(value))
            }
        }
        var units = protocolUnits
        units = saturatingAddUnits(units, messageTextUnits)
        units = saturatingAddUnits(units, toolCallUnits)
        units = saturatingAddUnits(units, toolSchemaUnits)
        units = saturatingAddUnits(units, imageUnits)
        units = saturatingAddUnits(units, continuationUnits)
        val basis = if (includeProviderContinuation) {
            INPUT_BUDGET_BASIS
        } else {
            INPUT_BUDGET_BASIS_WITHOUT_CONTINUATION
        }
        return InputBudgetEstimate(
            units = units,
            imageCount = imageCount,
            basis = basis,
            protocolUnits = protocolUnits,
            messageTextUnits = messageTextUnits,
            toolCallUnits = toolCallUnits,
            toolSchemaUnits = toolSchemaUnits,
            imageUnits = imageUnits,
            continuationUnits = continuationUnits,
        )
    }

    private fun parameterLayerUnits(layers: ParameterLayers): Long {
        var units = jsonLayerUnits(layers.adapterDefaults)
        units = saturatingAddUnits(units, jsonLayerUnits(layers.modelParameters))
        units = saturatingAddUnits(units, jsonLayerUnits(layers.agentOverrides))
        layers.customJson?.let { units = saturatingAddUnits(units, conservativeUtf8Units(it)) }
        return units
    }

    private fun jsonLayerUnits(entries: Map<String, JsonElement>): Long {
        var units = 0L
        entries.forEach { (key, value) ->
            units = saturatingAddUnits(units, PARAMETER_ENTRY_UNITS)
            units = saturatingAddUnits(units, conservativeUtf8Units(key))
            units = saturatingAddUnits(units, conservativeUtf8Units(value.toString()))
        }
        return units
    }

    private fun legacyExtraUnits(entries: Map<String, Any?>): Long {
        var units = 0L
        entries.forEach { (key, value) ->
            units = saturatingAddUnits(units, PARAMETER_ENTRY_UNITS)
            units = saturatingAddUnits(units, conservativeUtf8Units(key))
            units = saturatingAddUnits(units, legacyValueUnits(value))
        }
        return units
    }

    /**
     * Size of a legacy extra value without ever materializing secret-like
     * character arrays: [CharArray] is measured by length only.
     */
    private fun legacyValueUnits(value: Any?): Long = when (value) {
        null -> 0L
        is String -> conservativeUtf8Units(value)
        is JsonElement -> conservativeUtf8Units(value.toString())
        // Never materialize a secret-like buffer; length is enough.
        is CharArray -> value.size.toLong()
        else -> runCatching { value.toString() }.fold(
            onSuccess = { conservativeUtf8Units(it) },
            // A value that cannot be rendered is still reserved conservatively.
            onFailure = { PARAMETER_ENTRY_UNITS },
        )
    }
}

/**
 * Conservative UTF-8 byte length of [text].
 *
 * Well-formed text is measured exactly (4 bytes per surrogate pair, 3 bytes
 * per other non-ASCII BMP character). Unpaired surrogates are charged 3 bytes
 * (the UTF-8 length of the replacement character) instead of the JDK encoder's
 * lossy 1-byte `?`, so malformed input can never understate the estimate.
 */
internal fun conservativeUtf8Units(text: String): Long {
    if (text.isEmpty()) return 0L
    var units = 0L
    var index = 0
    while (index < text.length) {
        val code = text[index].code
        when {
            code < 0x80 -> units = saturatingAddUnits(units, 1L)
            code < 0x800 -> units = saturatingAddUnits(units, 2L)
            Character.isHighSurrogate(text[index]) &&
                index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1]) -> {
                units = saturatingAddUnits(units, 4L)
                index++
            }
            else -> units = saturatingAddUnits(units, 3L)
        }
        index++
    }
    return units
}

/** Saturating non-negative addition: overflow clamps to [Long.MAX_VALUE]. */
internal fun saturatingAddUnits(current: Long, extra: Long): Long = when {
    extra > 0L && current > Long.MAX_VALUE - extra -> Long.MAX_VALUE
    extra < 0L && current < Long.MIN_VALUE - extra -> Long.MIN_VALUE
    else -> current + extra
}

/** Saturating non-negative multiplication: overflow clamps to [Long.MAX_VALUE]. */
internal fun saturatingMultiplyUnits(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left < 0L || right < 0L -> Long.MAX_VALUE
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}

/** Saturating [Int] count: overflow clamps to [Int.MAX_VALUE]. */
private fun saturatingAddCount(current: Int, extra: Int): Int =
    if (extra > 0 && current > Int.MAX_VALUE - extra) Int.MAX_VALUE else current + extra
