<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Android UI implementation evidence

Date: 2026-08-29
Design baseline: `7511b22ffd7a7d3021b7857b6500cbe75d037ad6`
Scope: feature Compose surfaces, the new `runtime.mobileagent.ui` shell, and the MainActivity host wiring.

## Implemented mapping

| Design area | Implementation and state surface |
| --- | --- |
| Chat | `feature/chat/.../ChatUi.kt`: responsive session chooser, message stream with scroll-to-bottom, IME-safe composer, text-only degradation state, explicit tool approval, citations in a wrapping row, request inspector and prompt layers. `ChatUiState` and `ChatActions` carry all business state and callbacks. |
| Agents | `feature/agents/.../AgentsUi.kt`: responsive list/detail editor, four model roles, prompt revisions and restore, parameter schema fields, resource binding switches, retrieval mode and snapshot boundary. Narrow layouts use one column. |
| Providers | `feature/providers/.../ProvidersUi.kt`: provider cards, credential-safe editor, model role/parameter/budget editor, model edit/delete entry points, capability display, explicit probe dialog, and disabled MCP entry when no adapter is configured. |
| Knowledge | `feature/knowledge/.../KnowledgeUi.kt`: knowledge base/document views, Android SAF multi-file selection with confirmation, knowledge-base create/delete, job cancellation, import jobs, explicit `WAITING` Vision actions, text-only route, evidence dialog, rebuild and delete callbacks. |
| Skills | `feature/skills/.../SkillsUi.kt`: install filter/list, package inspection and permission confirmation, enable/revoke actions, source-file viewer callback and audit log. No runtime success is synthesized. |
| Announcements | `feature/announcements/.../AnnouncementsUi.kt`: severity/category cards, pinned banner, filter/refresh/read actions, safe action dispatch, feed endpoint fields, detail and mandatory acknowledgement dialogs. |
| Settings | `feature/settings/.../SettingsUi.kt`: zh-CN-first language selector, Light/Dark and literal `66ccff` three-theme selector, privacy/request-inspector switches, export/import/update entries, feature routing, disabled MCP reason, version and AGPL license dialogs. |
| Shell | `app-android/.../ui/MobileAgentTheme.kt`, `AppNavigation.kt`, `MainScreens.kt`, and `UiDialogs.kt`: token-driven Material 3 light/dark/`#66CCFF` schemes, responsive rail/bottom navigation, seven route definitions and theme-value parsing, real ViewModel mappings, SAF export/import Agent selection, unsaved-edit confirmation, Vision consent, unknown-outcome retry confirmation, and scoped Skill permission confirmation. `MainActivity.kt` now hosts this shell. |

The map is covered by the following screen groups: `SCR-CHAT-01..06`,
`SCR-AGENT-01..06`, `SCR-PROV-01..04`, `SCR-KNOW-01..07`,
`SCR-SKILL-01..05`, `SCR-ANN-01..05`, and `SCR-SETT-01..04`.

## Integration contracts

Each new screen has a state/action overload with empty defaults for previews and a separate legacy overload remains unchanged for the existing host callbacks. The old agent entry now delegates to an empty state instead of embedding a sample prompt, and the old provider editor no longer pre-fills a provider URL. New actions do not report success; the host must update the state after the repository/runtime operation completes. `MainScreens.kt` maps the new contracts to the current ViewModels without reading secret values; provider model edits preserve role, JSON parameters and context/output budgets. Knowledge Vision confirmation displays the configured target and charge/retry risk before `confirmVision()`. Skill grants require explicit knowledge-base selection when applicable and display declared network host/method scope. Navigation and editor dismissal ask before discarding an open edit.

Notable contracts are `ChatUiState`/`ChatActions`, `ProvidersUiState`/`ProvidersActions`, `AgentsUiState`/`AgentsActions`, `KnowledgeUiState`/`KnowledgeActions`, `SkillsUiState`/`SkillsActions`, `AnnouncementsUiState`/`AnnouncementsActions`, and `SettingsUiState`/`SettingsActions`.

## Static checks

- `git diff --check`: passed for the working tree (Git emitted only existing line-ending warnings).
- Emoji scan over feature Kotlin and the new app UI package: no matches.
- The first module compile was stopped after the coordinator requested one shared Gradle run. The attempted `:feature:chat:compileDebugKotlin` did not reach `ChatUi.kt`; it was blocked by unrelated in-progress syntax/unresolved-reference errors in `shared/provider-api/.../mcp/McpStreamableHttp.kt` from the protocol adapter worker. No device or emulator acceptance was run by this worker.

## Remaining integration work

The coordinator owns the shared Gradle run, emulator screenshots and final acceptance evidence. The host now uses the dedicated Chat unknown-outcome cancellation method and continues to show only the status reported by the Provider ViewModel until richer probe state is available. Device acceptance has not been run by this worker.
