// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

import kotlinx.serialization.Serializable

@Serializable enum class TrpgActorKind { PLAYER, COMPANION, NPC }
@Serializable enum class TrpgRest { NONE, SHORT, LONG }
@Serializable data class TrpgRecovery(val resource: String, val shortAmount: Int = 0, val longAmount: Int = 0,
    val shortFull: Boolean = false, val longFull: Boolean = false)
@Serializable data class TrpgSkill(val id: String, val name: String, val expression: String = "1d20", val dc: Int? = null,
    val cocSkill: Int? = null)
@Serializable data class TrpgActor(val id: String, val name: String, val kind: TrpgActorKind = TrpgActorKind.PLAYER,
    val description: String = "", val resources: List<TrpgResource> = emptyList(), val skills: List<TrpgSkill> = emptyList(),
    val recovery: List<TrpgRecovery> = emptyList(), val fields: Map<String, String> = emptyMap(),
    val visibility: TrpgVisibility = TrpgVisibility.PLAYER)
@Serializable data class TrpgResourceDelta(val resource: String, val amount: Int)
@Serializable data class TrpgItem(val id: String, val name: String, val ownerId: String? = null, val quantity: Int = 1,
    val equipped: Boolean = false, val consumable: Boolean = false, val effects: List<TrpgResourceDelta> = emptyList(),
    val description: String = "", val visibility: TrpgVisibility = TrpgVisibility.PLAYER)
@Serializable data class TrpgCondition(val id: String, val actorId: String, val name: String, val note: String = "",
    val remainingRounds: Int? = null, val endsOnRest: TrpgRest = TrpgRest.NONE,
    val visibility: TrpgVisibility = TrpgVisibility.PLAYER)
@Serializable data class TrpgInitiative(val actorId: String, val score: Int)
@Serializable data class TrpgEncounter(val order: List<TrpgInitiative> = emptyList(), val round: Int = 0, val turn: Int = 0)
@Serializable data class TrpgParty(val actors: List<TrpgActor> = emptyList(), val items: List<TrpgItem> = emptyList(),
    val conditions: List<TrpgCondition> = emptyList(), val encounter: TrpgEncounter = TrpgEncounter())

@Serializable enum class TrpgPartyOperation { UPSERT_ACTOR, REMOVE_ACTOR, UPSERT_ITEM, REMOVE_ITEM, EQUIP_ITEM,
    CONSUME_ITEM, ADJUST_RESOURCE, UPSERT_CONDITION, REMOVE_CONDITION, START_ENCOUNTER, NEXT_TURN, END_ENCOUNTER, REST }

/** Only the fields for the chosen operation are accepted. No text is evaluated as a rule or formula. */
@Serializable data class TrpgPartyCommand(val operation: TrpgPartyOperation, val id: String? = null,
    val actor: TrpgActor? = null, val item: TrpgItem? = null, val condition: TrpgCondition? = null,
    val resource: String? = null, val amount: Int? = null, val equipped: Boolean? = null,
    val initiative: List<TrpgInitiative>? = null, val rest: TrpgRest? = null) {
    fun validate() {
        val present = setOfNotNull("id".takeIf { id != null }, "actor".takeIf { actor != null }, "item".takeIf { item != null },
            "condition".takeIf { condition != null }, "resource".takeIf { resource != null }, "amount".takeIf { amount != null },
            "equipped".takeIf { equipped != null }, "initiative".takeIf { initiative != null }, "rest".takeIf { rest != null })
        val required = when (operation) {
            TrpgPartyOperation.UPSERT_ACTOR -> setOf("actor")
            TrpgPartyOperation.UPSERT_ITEM -> setOf("item")
            TrpgPartyOperation.UPSERT_CONDITION -> setOf("condition")
            TrpgPartyOperation.REMOVE_ACTOR, TrpgPartyOperation.REMOVE_ITEM, TrpgPartyOperation.REMOVE_CONDITION -> setOf("id")
            TrpgPartyOperation.EQUIP_ITEM -> setOf("id", "equipped")
            TrpgPartyOperation.CONSUME_ITEM -> setOf("id", "amount")
            TrpgPartyOperation.ADJUST_RESOURCE -> setOf("id", "resource", "amount")
            TrpgPartyOperation.START_ENCOUNTER -> setOf("initiative")
            TrpgPartyOperation.REST -> setOf("id", "rest")
            TrpgPartyOperation.NEXT_TURN, TrpgPartyOperation.END_ENCOUNTER -> emptySet()
        }
        require(present == required) { "Unexpected or missing party operation fields" }
        id?.let(TrpgCampaignValidation::id)
    }
}

object TrpgPartyValidation {
    private fun <T> ids(items: List<T>, max: Int, id: (T) -> String) {
        require(items.size <= max && items.map(id).distinct().size == items.size)
        items.forEach { TrpgCampaignValidation.id(id(it)) }
    }
    fun validate(party: TrpgParty) {
        ids(party.actors, 24) { it.id }; ids(party.items, 128) { it.id }; ids(party.conditions, 64) { it.id }
        val actors = party.actors.associateBy { it.id }
        party.actors.forEach { actor ->
            require(actor.name.isNotBlank() && actor.name.length <= 128 && actor.description.length <= 4096)
            require(actor.resources.size <= 32 && actor.resources.map { it.name }.distinct().size == actor.resources.size)
            actor.resources.forEach { require(it.name.isNotBlank() && it.name.length <= 64 && it.current in -1_000_000..1_000_000 && (it.maximum == null || it.maximum in 0..1_000_000)) }
            ids(actor.skills, 32) { it.id }
            actor.skills.forEach { TrpgCampaignValidation.validate(TrpgCheckRequest(it.id, it.name, it.expression, it.dc, it.cocSkill)) }
            require(actor.fields.size <= 16 && actor.fields.all { it.key.isNotBlank() && it.key.length <= 64 && it.value.length <= 512 })
            require(actor.recovery.size <= 32 && actor.recovery.map { it.resource }.distinct().size == actor.recovery.size)
            actor.recovery.forEach { policy ->
                val resource = actor.resources.singleOrNull { it.name == policy.resource } ?: error("Recovery resource is missing")
                require(policy.shortAmount in 0..1_000_000 && policy.longAmount in 0..1_000_000)
                require(!(policy.shortFull || policy.longFull) || resource.maximum != null) { "Full recovery requires a maximum" }
            }
        }
        party.items.forEach { item ->
            require(item.name.isNotBlank() && item.name.length <= 128 && item.description.length <= 2048 && item.quantity in 0..1_000_000)
            require(item.ownerId == null || item.ownerId in actors) { "Item owner is missing" }
            require(!item.equipped || (item.ownerId != null && item.quantity > 0)) { "Equipped items require an owner and stock" }
            require(item.effects.size <= 16 && item.effects.map { it.resource }.distinct().size == item.effects.size)
            item.effects.forEach { require(it.resource.isNotBlank() && it.resource.length <= 64 && it.amount in -1_000_000..1_000_000) }
            require(item.effects.isEmpty() || item.consumable) { "Only consumables have resource effects" }
        }
        party.conditions.forEach { require(it.actorId in actors && it.name.isNotBlank() && it.name.length <= 128 && it.note.length <= 2048 && (it.remainingRounds == null || it.remainingRounds in 0..100_000)) }
        with(party.encounter) {
            require(order.size <= 24 && order.map { it.actorId }.distinct().size == order.size)
            require(order.all { it.actorId in actors && it.score in -1_000_000..1_000_000 })
            require(if (order.isEmpty()) round == 0 && turn == 0 else round in 1..1_000_000 && turn in order.indices)
        }
    }
}

object TrpgPartyReducer {
    fun apply(state: TrpgState, command: TrpgPartyCommand): TrpgState {
        command.validate()
        val party = state.campaign.party
        fun actor(id: String) = party.actors.singleOrNull { it.id == id } ?: error("Actor is missing")
        fun item(id: String) = party.items.singleOrNull { it.id == id } ?: error("Item is missing")
        fun changed(actor: TrpgActor, resource: String, delta: Long): TrpgActor {
            val old = actor.resources.singleOrNull { it.name == resource } ?: error("Actor resource is missing")
            val next = old.current.toLong() + delta
            require(next in -1_000_000L..1_000_000L) { "Resource result is out of range" }
            return actor.copy(resources = actor.resources.map { if (it.name == resource) it.copy(current = next.toInt()) else it })
        }
        val next = when (command.operation) {
            TrpgPartyOperation.UPSERT_ACTOR -> party.copy(actors = upsert(party.actors, checkNotNull(command.actor)) { it.id })
            TrpgPartyOperation.REMOVE_ACTOR -> {
                actor(checkNotNull(command.id))
                require(party.encounter.order.none { it.actorId == command.id }) { "End the encounter before removing a participant" }
                require(state.campaign.checks.none { it.request.actorId == command.id && it.status in setOf(TrpgCheckStatus.PENDING, TrpgCheckStatus.ROLLED) }) { "Finish this actor's checks first" }
                party.copy(actors = party.actors.filterNot { it.id == command.id },
                    items = party.items.map { if (it.ownerId == command.id) it.copy(ownerId = null, equipped = false) else it },
                    conditions = party.conditions.filterNot { it.actorId == command.id })
            }
            TrpgPartyOperation.UPSERT_ITEM -> party.copy(items = upsert(party.items, checkNotNull(command.item)) { it.id })
            TrpgPartyOperation.REMOVE_ITEM -> { item(checkNotNull(command.id)); party.copy(items = party.items.filterNot { it.id == command.id }) }
            TrpgPartyOperation.EQUIP_ITEM -> { val old = item(checkNotNull(command.id)); party.copy(items = upsert(party.items, old.copy(equipped = checkNotNull(command.equipped))) { it.id }) }
            TrpgPartyOperation.CONSUME_ITEM -> {
                val old = item(checkNotNull(command.id)); val count = checkNotNull(command.amount)
                require(old.consumable && count > 0 && count <= old.quantity) { "Not enough consumable items" }
                val recipient = old.ownerId?.let(::actor)
                require(old.effects.isEmpty() || recipient != null) { "Assign an owner before applying effects" }
                val updated = recipient?.let { original -> old.effects.fold(original) { a, effect -> changed(a, effect.resource, effect.amount.toLong() * count) } }
                party.copy(items = upsert(party.items, old.copy(quantity = old.quantity - count, equipped = old.equipped && old.quantity > count)) { it.id },
                    actors = if (updated == null) party.actors else upsert(party.actors, updated) { it.id })
            }
            TrpgPartyOperation.ADJUST_RESOURCE -> party.copy(actors = upsert(party.actors,
                changed(actor(checkNotNull(command.id)), checkNotNull(command.resource), checkNotNull(command.amount).toLong())) { it.id })
            TrpgPartyOperation.UPSERT_CONDITION -> party.copy(conditions = upsert(party.conditions, checkNotNull(command.condition)) { it.id })
            TrpgPartyOperation.REMOVE_CONDITION -> {
                require(party.conditions.any { it.id == command.id }) { "Condition is missing" }
                party.copy(conditions = party.conditions.filterNot { it.id == command.id })
            }
            TrpgPartyOperation.START_ENCOUNTER -> {
                require(party.encounter.order.isEmpty()) { "End the current encounter first" }
                val order = checkNotNull(command.initiative); require(order.isNotEmpty())
                party.copy(encounter = TrpgEncounter(order.sortedByDescending { it.score }, 1, 0))
            }
            TrpgPartyOperation.NEXT_TURN -> {
                val encounter = party.encounter; require(encounter.order.isNotEmpty()) { "No active encounter" }
                val wraps = encounter.turn + 1 == encounter.order.size
                party.copy(encounter = encounter.copy(round = encounter.round + if (wraps) 1 else 0, turn = if (wraps) 0 else encounter.turn + 1),
                    conditions = if (!wraps) party.conditions else party.conditions.map { it.copy(remainingRounds = it.remainingRounds?.let { rounds -> (rounds - 1).coerceAtLeast(0) }) })
            }
            TrpgPartyOperation.END_ENCOUNTER -> party.copy(encounter = TrpgEncounter())
            TrpgPartyOperation.REST -> {
                require(party.encounter.order.isEmpty()) { "End the encounter before resting" }
                val old = actor(checkNotNull(command.id)); val rest = checkNotNull(command.rest); require(rest != TrpgRest.NONE)
                val resources = old.resources.map { resource ->
                    val recovery = old.recovery.singleOrNull { it.resource == resource.name }
                    if (recovery == null) resource else {
                        val full = if (rest == TrpgRest.SHORT) recovery.shortFull else recovery.longFull
                        val amount = if (rest == TrpgRest.SHORT) recovery.shortAmount else recovery.longAmount
                        // Explicit recovery policy caps at maximum without reducing an existing over-maximum value.
                        val current = if (full) maxOf(resource.current, checkNotNull(resource.maximum)) else {
                            val recovered = resource.current.toLong() + amount
                            val capped = resource.maximum?.let { maxOf(resource.current.toLong(), minOf(recovered, it.toLong())) } ?: recovered
                            require(capped in -1_000_000L..1_000_000L); capped.toInt()
                        }
                        resource.copy(current = current)
                    }
                }
                party.copy(actors = upsert(party.actors, old.copy(resources = resources)) { it.id },
                    conditions = party.conditions.filterNot { it.actorId == old.id && (it.endsOnRest == rest || rest == TrpgRest.LONG && it.endsOnRest == TrpgRest.SHORT) })
            }
        }
        val preferences = state.campaign.preferences.let {
            if (command.operation == TrpgPartyOperation.REMOVE_ACTOR && it.focusActorId == command.id) it.copy(focusActorId = null) else it
        }
        return state.copy(campaign = state.campaign.copy(party = next, preferences = preferences)).also(TrpgStateValidation::validate)
    }
    private fun <T> upsert(items: List<T>, value: T, id: (T) -> String): List<T> =
        if (items.any { id(it) == id(value) }) items.map { if (id(it) == id(value)) value else it } else items + value
}
