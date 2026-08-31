/*
 * SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package runtime.mobileagent.skills.tooling

import runtime.mobileagent.domain.Authority as DomainAuthority
import runtime.mobileagent.domain.AuthorityUserIntent as DomainAuthorityUserIntent

/** Domain-owned provider names; aliases keep the tooling API readable. */
typealias Authority = DomainAuthority
typealias AuthorityUserIntent = DomainAuthorityUserIntent
typealias ElevatedAuthority = Authority
typealias AuthorityProvider = Authority
typealias SelectedAuthority = Authority
typealias UserIntent = AuthorityUserIntent

/** Persistent platform/trust grant, distinct from runtime availability. */
enum class PlatformGrant {
    UNKNOWN,
    GRANTED,
    DENIED,
    REVOKED,
}

/** Whether the provider integration is supported and configured for use. */
enum class Availability {
    READY,
    TEMPORARILY_UNAVAILABLE,
    UNSUPPORTED,
}

/** Ephemeral connection/session state. */
enum class Connection {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEGRADED,
}

typealias AuthorityGrantState = PlatformGrant
typealias AuthorityAvailability = Availability
typealias AuthorityConnection = Connection

/**
 * Grant, availability, connection, and persistent user intent are deliberately
 * separate.  A disconnect must not change the persistent fields or tool
 * schema exposure; dispatch revalidates the ephemeral fields per invocation.
 */
data class AuthorityState(
    val authority: Authority,
    val userIntent: AuthorityUserIntent = AuthorityUserIntent.NONE,
    val grant: PlatformGrant = PlatformGrant.UNKNOWN,
    val availability: Availability = Availability.UNSUPPORTED,
    val connection: Connection = Connection.DISCONNECTED,
    /** Persistent provider configuration/trust, not a connection health bit. */
    val configured: Boolean = false,
    val revision: Long = 1,
    /** A non-secret identity summary suitable for local state comparisons. */
    val identity: String? = null,
) {
    init {
        require(revision > 0)
        require(identity == null || identity.length <= 128)
    }

    /** True when this exact provider is persistently selected and granted. */
    val isConfiguredForSelection: Boolean
        get() = authority != Authority.NONE && configured &&
            userIntent == intentFor(authority) && grant == PlatformGrant.GRANTED &&
            availability != Availability.UNSUPPORTED

    /** True only for an immediately dispatchable connection. */
    val isReady: Boolean
        get() = isConfiguredForSelection && availability == Availability.READY && connection == Connection.CONNECTED

    /** A disconnect is not a revocation or a provider switch. */
    fun preservingGrantAfterDisconnect(): AuthorityState = copy(
        availability = Availability.TEMPORARILY_UNAVAILABLE,
        connection = Connection.DISCONNECTED,
    )

    companion object {
        fun intentFor(authority: Authority): AuthorityUserIntent = when (authority) {
            Authority.NONE -> AuthorityUserIntent.NONE
            Authority.SHIZUKU -> AuthorityUserIntent.SHIZUKU
            Authority.WIRED_ADB -> AuthorityUserIntent.WIRED_ADB
        }

        /** A convenient fully configured fixture state. */
        fun configured(
            authority: Authority,
            availability: Availability = Availability.READY,
            connection: Connection = Connection.CONNECTED,
        ): AuthorityState = AuthorityState(
            authority = authority,
            userIntent = intentFor(authority),
            grant = PlatformGrant.GRANTED,
            availability = availability,
            connection = connection,
            configured = authority != Authority.NONE,
        )
    }
}

/** The user-selected provider is the sole source of backend routing. */
data class AuthoritySelection(
    val selected: Authority?,
    val states: Map<Authority, AuthorityState> = emptyMap(),
) {
    init {
        require(states.keys.all { it in Authority.entries })
        require(selected == null || selected in Authority.entries)
    }

    val selectedState: AuthorityState?
        get() = selected?.let(states::get)

    /** Never searches the map for another available authority. */
    fun selectedIsConfigured(): Boolean = selectedState?.isConfiguredForSelection == true &&
        selectedState?.authority == selected

    /** Never searches the map for another ready authority. */
    fun selectedIsReady(): Boolean = selectedState?.isReady == true &&
        selectedState?.authority == selected

    /** Resolve only the explicit selection; a missing selection never falls back. */
    fun <T> selectedBackend(backends: Map<Authority, T>): T? = selected?.let(backends::get)
}
