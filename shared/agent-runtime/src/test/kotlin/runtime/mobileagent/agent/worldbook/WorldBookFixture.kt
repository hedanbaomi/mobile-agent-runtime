// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import runtime.mobileagent.domain.worldbook.WorldBookContext
import runtime.mobileagent.domain.worldbook.WorldBookDocument
import runtime.mobileagent.domain.worldbook.WorldBookEntry
import runtime.mobileagent.domain.worldbook.WorldBookEvaluation
import runtime.mobileagent.domain.worldbook.WorldBookPosition
import runtime.mobileagent.domain.worldbook.WorldBookRole
import runtime.mobileagent.domain.worldbook.WorldBookScanMessage
import runtime.mobileagent.domain.worldbook.WorldBookScanRequest
import runtime.mobileagent.domain.worldbook.WorldBookSelectiveLogic
import runtime.mobileagent.domain.worldbook.WorldBookSettings
import runtime.mobileagent.domain.worldbook.WorldBookSource
import runtime.mobileagent.domain.worldbook.WorldBookSourceKind
import runtime.mobileagent.domain.worldbook.WorldBookTimedEffect

/**
 * 引擎测试用的合成样例构造器（不使用任何真实用户材料）。
 */
internal object WorldBookFixture {

    fun entry(
        uid: String,
        keys: List<String> = emptyList(),
        content: String = "body-$uid",
        title: String = "title-$uid",
        enabled: Boolean = true,
        constant: Boolean = false,
        vectorized: Boolean = false,
        selective: Boolean = true,
        selectiveLogic: WorldBookSelectiveLogic = WorldBookSelectiveLogic.AND_ANY,
        secondaryKeys: List<String> = emptyList(),
        order: Int = 100,
        position: WorldBookPosition = WorldBookPosition.BEFORE_CHAR,
        depth: Int = 4,
        role: WorldBookRole = WorldBookRole.SYSTEM,
        outletName: String = "",
        ignoreBudget: Boolean = false,
        excludeRecursion: Boolean = false,
        preventRecursion: Boolean = false,
        delayUntilRecursion: Int = 0,
        probability: Int = 100,
        useProbability: Boolean = true,
        group: String = "",
        groupOverride: Boolean = false,
        groupWeight: Int = 100,
        useGroupScoring: Boolean? = null,
        scanDepth: Int? = null,
        caseSensitive: Boolean? = null,
        matchWholeWords: Boolean? = null,
        sticky: Int? = null,
        cooldown: Int? = null,
        delay: Int? = null,
        automationId: String = "",
        triggers: List<String> = emptyList(),
        useRegex: Boolean = false,
        matchPersonaDescription: Boolean = false,
        matchCharacterDescription: Boolean = false,
        matchCharacterPersonality: Boolean = false,
        matchCharacterDepthPrompt: Boolean = false,
        matchScenario: Boolean = false,
        matchCreatorNotes: Boolean = false,
    ): WorldBookEntry = WorldBookEntry(
        uid = uid,
        keys = keys,
        content = content,
        title = title,
        enabled = enabled,
        secondaryKeys = secondaryKeys,
        constant = constant,
        vectorized = vectorized,
        selective = selective,
        selectiveLogic = selectiveLogic,
        order = order,
        position = position,
        depth = depth,
        role = role,
        outletName = outletName,
        ignoreBudget = ignoreBudget,
        excludeRecursion = excludeRecursion,
        preventRecursion = preventRecursion,
        delayUntilRecursion = delayUntilRecursion,
        probability = probability,
        useProbability = useProbability,
        group = group,
        groupOverride = groupOverride,
        groupWeight = groupWeight,
        useGroupScoring = useGroupScoring,
        scanDepth = scanDepth,
        caseSensitive = caseSensitive,
        matchWholeWords = matchWholeWords,
        sticky = sticky,
        cooldown = cooldown,
        delay = delay,
        automationId = automationId,
        triggers = triggers,
        useRegex = useRegex,
        matchPersonaDescription = matchPersonaDescription,
        matchCharacterDescription = matchCharacterDescription,
        matchCharacterPersonality = matchCharacterPersonality,
        matchCharacterDepthPrompt = matchCharacterDepthPrompt,
        matchScenario = matchScenario,
        matchCreatorNotes = matchCreatorNotes,
    )

    fun book(
        id: String = "book",
        entries: List<WorldBookEntry>,
        kind: WorldBookSourceKind = WorldBookSourceKind.GLOBAL,
        revision: Int = 1,
    ): WorldBookSource = WorldBookSource(
        id = id,
        revision = revision,
        document = WorldBookDocument(name = id, entries = entries),
        kind = kind,
    )

    fun messages(vararg texts: String): List<WorldBookScanMessage> =
        texts.mapIndexed { index, text ->
            WorldBookScanMessage(text = text, role = if (index % 2 == 0) "user" else "assistant", name = "n$index")
        }

    fun settings(
        scanDepth: Int = 4,
        recursive: Boolean = false,
        minActivations: Int = 0,
        minActivationsDepthMax: Int = 0,
        caseSensitive: Boolean = false,
        matchWholeWords: Boolean = false,
        useGroupScoring: Boolean = false,
        budgetCap: Int = 0,
        maxRecursionSteps: Int = 0,
        maxRegexPatternLength: Int = 512,
        regexStepBudget: Long = 2_000_000,
        maxMessageScanChars: Int = 2_000_000,
        wholeWordAsciiBoundaries: Boolean = true,
        includeNames: Boolean = true,
        maxRunExpansionChars: Int = WorldBookSettings.HARD_MAX_RUN_EXPANSION_CHARS,
    ): WorldBookSettings = WorldBookSettings(
        scanDepth = scanDepth,
        recursive = recursive,
        minActivations = minActivations,
        minActivationsDepthMax = minActivationsDepthMax,
        caseSensitive = caseSensitive,
        matchWholeWords = matchWholeWords,
        useGroupScoring = useGroupScoring,
        budgetCap = budgetCap,
        maxRecursionSteps = maxRecursionSteps,
        maxRegexPatternLength = maxRegexPatternLength,
        regexStepBudget = regexStepBudget,
        maxMessageScanChars = maxMessageScanChars,
        wholeWordAsciiBoundaries = wholeWordAsciiBoundaries,
        includeNames = includeNames,
        maxRunExpansionChars = maxRunExpansionChars,
    )

    fun evaluate(
        sources: List<WorldBookSource>,
        messages: List<WorldBookScanMessage>,
        settings: WorldBookSettings = settings(),
        context: WorldBookContext = WorldBookContext(),
        messageCount: Int = messages.size,
        generationType: String = "normal",
        previousEffects: List<WorldBookTimedEffect> = emptyList(),
        seed: Long = 20261018L,
        budgetUnits: Int = 100_000,
        hardBudgetUnits: Int = budgetUnits,
        estimateUnits: (String) -> Int = { it.length },
    ): WorldBookEvaluation = WorldBookEngine.evaluate(
        request = WorldBookScanRequest(
            sources = sources,
            messages = messages,
            settings = settings,
            context = context,
            messageCount = messageCount,
            generationType = generationType,
            previousEffects = previousEffects,
            seed = seed,
        ),
        budgetUnits = budgetUnits,
        hardBudgetUnits = hardBudgetUnits,
        estimateUnits = estimateUnits,
    )
}

internal fun WorldBookEvaluation.activatedUids(): List<String> = activated.map { it.bookId + ":" + it.entryUid }

internal fun WorldBookEvaluation.decisionFor(uid: String) = decisions.first { it.entryUid == uid }
