// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*
import runtime.mobileagent.domain.trpg.*

class TrpgCheckTest {
    private val at = "2026-10-21T00:00:00Z"
    private val request = TrpgCommand(TrpgOperation.REQUEST_CHECK, check = TrpgCheckRequest("check", "Listen", "1d20+3", dc = 12))

    @Test fun playerRequestWaitsForOneRealRollAndResolutionUsesThatEvidence() = fixture { _, repo ->
        val random = CountingRandom()
        val pending = repo.command("story", 0, "request", request, random)
        assertEquals(0, random.draws)
        assertEquals(TrpgCheckStatus.PENDING, pending.state.campaign.checks.single().status)
        assertThrows(IllegalArgumentException::class.java) {
            repo.command("story", 1, "premature", TrpgCommand(TrpgOperation.RESOLVE_CHECK, id = "check", note = "Invented success"))
        }
        val rolled = repo.rollCheck("story", 1, "check", "roll", random)
        assertTrue(random.draws > 0)
        val count = random.draws
        assertEquals(rolled, repo.rollCheck("story", 1, "check", "roll", random))
        assertEquals(count, random.draws)
        assertThrows(Exception::class.java) { repo.rollCheck("story", 2, "check", "different-roll", random) }
        assertEquals(count, random.draws)
        val resolved = repo.command("story", 2, "resolve", TrpgCommand(TrpgOperation.RESOLVE_CHECK, id = "check", note = "You hear footsteps."))
        assertEquals(TrpgCheckStatus.RESOLVED, resolved.state.campaign.checks.single().status)
        assertEquals(rolled.state.campaign.checks.single().roll, resolved.state.campaign.checks.single().roll)
    }

    @Test fun automaticToolRequestAndReceiptCommitTogetherWithoutRedrawOnReplay() = fixture { db, repo ->
        repo.update("story", 0, TrpgState(campaign = TrpgCampaign(preferences = TrpgPreferences(diceMode = TrpgDiceMode.AUTOMATIC))))
        RunRepository(db).create(RunRecord("run", "snapshot", "story", RunStatus.TOOL_EXECUTING, createdAt = at))
        val random = CountingRandom()
        val rolled = repo.commandFromRun("story", "snapshot", "run", "call", "{}", 1, request, random)
        assertEquals(TrpgCheckStatus.ROLLED, rolled.state.campaign.checks.single().status)
        assertTrue(random.draws > 0)
        val replacementRandom = CountingRandom()
        assertEquals(rolled, TrpgRepository(db).commandFromRun("story", "snapshot", "run", "call", "{}", 1, request, replacementRandom))
        assertEquals(0, replacementRandom.draws)
        assertEquals(2, repo.events("story").size)
        assertNull(repo.undoableEventId("story"))
        assertThrows(AppException::class.java) {
            repo.commandFromRun("story", "snapshot", "run", "call", "{}", 1, request.copy(check = request.check!!.copy(dc = 15)))
        }
    }

    @Test fun staleAndInvalidRequestsAreRejectedBeforeRandomness() = fixture { _, repo ->
        val random = CountingRandom()
        assertThrows(Exception::class.java) { repo.command("story", 0, "bad", request.copy(check = request.check!!.copy(expression = "99999d6")), random) }
        assertEquals(0L, repo.get("story").revision)
        repo.command("story", 0, "request", request, random)
        assertThrows(AppException::class.java) { repo.rollCheck("story", 0, "check", "stale", random) }
        assertEquals(0, random.draws)
        assertEquals(TrpgCheckStatus.PENDING, repo.get("story").state.campaign.checks.single().status)
    }

    @Test fun legacyToolUpdatesPreserveCampaignAndCannotForgeCheckOrPreferences() = fixture { db, repo ->
        val preferences = TrpgPreferences(tone = "Quiet mystery", setupComplete = true)
        repo.update("story", 0, TrpgState(campaign = TrpgCampaign(preferences = preferences)))
        repo.command("story", 1, "request", request)
        RunRepository(db).create(RunRecord("run", "snapshot", "story", RunStatus.TOOL_EXECUTING, createdAt = at))
        val updated = repo.updateFromRun("story", "snapshot", "run", "legacy", "{}", 2, TrpgState(scene = "Library"))
        assertEquals(preferences, updated.state.campaign.preferences)
        assertEquals(TrpgCheckStatus.PENDING, updated.state.campaign.checks.single().status)
        assertThrows(AppException::class.java) { repo.updateFromRun("story", "snapshot", "run", "forge", "{}", 3,
            updated.state.copy(campaign = updated.state.campaign.copy(preferences = preferences.copy(diceMode = TrpgDiceMode.AUTOMATIC)))) }
        assertEquals(updated, repo.get("story"))
    }

    @Test fun factSourcesMustBelongToTheSameConversationAndCorrectionsKeepIdentity() = fixture { db, repo ->
        val conversations = ConversationRepository(db)
        conversations.create("snapshot", "Other", "other")
        conversations.append("other", MessageRole.ASSISTANT, "Private", messageId = "foreign")
        conversations.append("story", MessageRole.ASSISTANT, "The door is blue.", messageId = "source")
        val fact = TrpgFact("fact", "Blue door", TrpgFactStatus.CONFIRMED, "foreign")
        assertThrows(AppException::class.java) { repo.command("story", 0, "foreign", TrpgCommand(TrpgOperation.UPSERT_FACT, fact = fact)) }
        repo.command("story", 0, "add", TrpgCommand(TrpgOperation.UPSERT_FACT, fact = fact.copy(sourceMessageId = "source")))
        repo.command("story", 1, "correct", TrpgCommand(TrpgOperation.UPSERT_FACT, fact = fact.copy(text = "Green door", status = TrpgFactStatus.CORRECTED, sourceMessageId = "source")))
        assertEquals("Green door", repo.get("story").state.campaign.facts.single().text)
        assertEquals("fact", repo.get("story").state.campaign.facts.single().id)
    }

    @Test fun rollingCheckIsNotUndoableButLaterRulingIs() = fixture { _, repo ->
        repo.command("story", 0, "request", request)
        repo.rollCheck("story", 1, "check", "roll", CountingRandom())
        assertNull(repo.undoableEventId("story"))
        val before = repo.get("story").state
        repo.command("story", 2, "resolve", TrpgCommand(TrpgOperation.RESOLVE_CHECK, id = "check", note = "Failure"))
        val afterUndo = repo.undo("story", 3, repo.undoableEventId("story")!!, "undo")
        assertEquals(before, afterUndo.state)
        assertEquals(TrpgCheckStatus.ROLLED, afterUndo.state.campaign.checks.single().status)
    }

    @Test fun partyConsumptionReplayAndUndoKeepInventoryAndActorResourcesAtomic() = fixture { _, repo ->
        val party = TrpgParty(actors = listOf(TrpgActor("hero", "Hero", resources = listOf(TrpgResource("HP", 2, 10)))),
            items = listOf(TrpgItem("potion", "Potion", "hero", 2, consumable = true, effects = listOf(TrpgResourceDelta("HP", 3)))))
        repo.update("story", 0, TrpgState(campaign = TrpgCampaign(party = party)))
        val command = TrpgCommand(TrpgOperation.PARTY, party = TrpgPartyCommand(TrpgPartyOperation.CONSUME_ITEM, id = "potion", amount = 1))
        val first = repo.command("story", 1, "consume", command)
        assertEquals(first, repo.command("story", 1, "consume", command))
        assertEquals(1, first.state.campaign.party.items.single().quantity)
        assertEquals(5, first.state.campaign.party.actors.single().resources.single().current)
        val event = repo.events("story").first()
        assertEquals("hero", event.resources.single().actorId)
        assertEquals(2, event.resources.single().before)
        assertEquals(5, event.resources.single().after)
        assertThrows(Exception::class.java) {
            repo.command("story", 2, "missing-stock", command.copy(party = command.party!!.copy(amount = 5)))
        }
        assertEquals(first, repo.get("story"))
        val restored = repo.undo("story", 2, event.id, "undo-party")
        assertEquals(party, restored.state.campaign.party)
    }

    @Test fun hiddenCheckRollIsDurableButPlayerHistoryCannotExposeIt() = fixture { _, repo ->
        val hidden = request.copy(check = request.check!!.copy(title = "SECRET check", visibility = TrpgVisibility.GAME_MASTER))
        repo.command("story", 0, "request-hidden", hidden)
        repo.rollCheck("story", 1, "check", "roll-hidden", CountingRandom())
        val event = repo.events("story").first()
        assertTrue(event.title.contains("SECRET"))
        assertTrue(event.detail.isNotEmpty())
        assertNull(TrpgProjection.eventForPlayer(event))
        assertTrue(TrpgProjection.player(repo.get("story").state).campaign.checks.isEmpty())
    }

    private class CountingRandom : Random() {
        var draws = 0
        override fun nextBits(bitCount: Int): Int { draws++; return 0 }
    }

    private fun fixture(block: (SqlConnection, TrpgRepository) -> Unit) = JdbcSqlConnection().use { db ->
        Migrations.apply(db)
        val profiles = ProfileRepository(db)
        profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", secretRef = "synthetic", revision = 1))
        profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", setOf("stream"), contextLimit = 4096, outputLimit = 512, revision = 1))
        val agents = AgentRepository(db)
        agents.saveWithPrompt(AgentProfile("agent", "Fixture", "pending", "model", revision = 0, mode = AgentMode.TRPG), "Fixture")
        ConversationRepository(db).create(agents.createSnapshot("agent", "snapshot").id, "Story", "story")
        block(db, TrpgRepository(db) { at })
    }
}
