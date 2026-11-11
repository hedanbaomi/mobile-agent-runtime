<!-- SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-only -->

# Released database fixture

`v1.4.0.db` was generated on 2026-11-10 using tag v1.4.0, commit
`d1264475c03be7b8444dc605ed331adae0c402c1`, and its unmodified production
Migrations/ProfileRepository/AgentRepository/ConversationRepository/RunRepository/
KnowledgeRepository implementations. Its real schema is 39; the current code was
not used to initialize it and no schema version was reassigned.

All content is synthetic. The fixture has one provider/model/agent/snapshot,
one conversation and Chinese/English user message, an UNKNOWN_OUTCOME run with
37 input tokens and no retry acknowledgement, and an indexed text document.
The provider points to example.invalid and the secret reference has no secret.
The knowledge CAS was in memory: this fixture tests SQL truth preservation, not
original-blob backup restoration. No capability grants are created.

The adjacent SHA-256 file locks the bytes. ReleasedDatabaseUpgradeTest copies
the database, upgrades and reopens it, checks message/snapshot/chunk preservation,
foreign keys and the UNKNOWN gate. The source fixture is never opened writable.

Generation was performed with an isolated old-tag checkout and a synthetic test;
the generator is `ReleasedFixtureGeneration.kt.txt` in this directory. Copy that
file to the old checkout's data/sqlite test Kotlin directory with a .kt extension,
choose a new output path, and run the named test. Do not initialize with the
current migration code or merely set an old schema_version value.

`v1.4.0.zip` is a second, independently generated portable archive from the same
released commit. `ReleasedArchiveGeneration.kt.txt` creates a physical schema39
source database and file CAS, a granted synthetic Agent, a cited reply and an
UNKNOWN run/tool invocation, then invokes that release's production exporter.
It includes original source bytes and citation/version identity. The archive
must not carry the source's capability grant or local provider secret reference.
The source and CAS generation artifacts remain outside this repository; the
portable ZIP and its SHA-256 are the immutable upgrade/restore fixture.
