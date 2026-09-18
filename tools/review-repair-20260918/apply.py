# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""One-shot, hash-bound preparation on the dedicated remote verification branch."""
from pathlib import Path
import hashlib
import subprocess

BASE = "aaace272b16df11a231df7ed61fbe926b2a32b5f"
BRANCH = "codex/review-r1-r5-20260918-remote"
ROOT = Path.cwd()
STAGING = ROOT / "tools/review-repair-20260918"
EXPECTED = {
    "shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/ProcessingUnit.kt": "3a1b22625d5849497f252e1a5f2e9da5c1cb2463",
    "data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt": "e79f72845ddaa64d35a33227886837702393b8e4",
    "data/sqlite/src/main/kotlin/runtime/mobileagent/data/DocumentPipelineStore.kt": "6fd914b364bf435c345a0d77931e8d700441fb73",
    "app-android/src/test/kotlin/runtime/mobileagent/OpenAiCompatibleVisionTest.kt": "80ffbecc8d82b5e109c8ef4dd7581da727cd8375",
}

def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

assert git("rev-parse", "HEAD^") == BASE, "Verification scaffold must directly descend from the reviewed source"
for path, expected in EXPECTED.items():
    assert git("hash-object", path) == expected, f"Source changed: {path}"

repo = ROOT / "data/sqlite/src/main/kotlin/runtime/mobileagent/data/KnowledgeRepository.kt"
text = repo.read_text()
old = "unit.requestText.ifBlank { unit.nativeText }"
assert text.count(old) == 4
text = text.replace(old, "unit.effectiveRequestText()")
old = '                    fallback.single()\n                } else ExtractedAsset("unit-${unit.unitId}"'
new = '                    fallback.single().copy(surroundingText = unit.effectiveRequestText())\n                } else ExtractedAsset("unit-${unit.unitId}"'
assert text.count(old) == 1
text = text.replace(old, new)
old = '''            val asset = if (parsed.format == SourceFormat.PDF && pdfRasterizer != null) {'''
new = '''            if (unit.effectiveRequestText().length > DocumentUnitPlanner.MAX_REQUEST_TEXT_CHARS) {
                pipeline.failUnit(job.id, unit.unitId, "PIPELINE_TEXT_LIMIT_EXCEEDED", "LOCAL_PREPARE")
                return VisionBatch.Failed("PIPELINE_TEXT_LIMIT_EXCEEDED: request text exceeds the local bound; no request was sent")
            }
            val asset = if (parsed.format == SourceFormat.PDF && pdfRasterizer != null) {'''
assert text.count(old) == 1
repo.write_text(text.replace(old, new))

store = ROOT / "data/sqlite/src/main/kotlin/runtime/mobileagent/data/DocumentPipelineStore.kt"
text = store.read_text()
old = '''            val unitRow = db.query("SELECT state FROM pipeline_units WHERE job_id=? AND unit_id=?",listOf(jobId,unit.unitId)).singleOrNull()
            val priorUnitState = unitRow?.string("state")
            val state = if(saved) "SUCCEEDED" else if(unknown(jobId,unit.unitId,unit.page)) "UNKNOWN_OUTCOME" else attempt?.string("state") ?: priorUnitState?.takeIf { it != "FAILED" } ?: "PLANNED"'''
new = '''            // Unit state is a projection for the selected target. An old target's
            // success (or an attempt without a current-version result) cannot prove it.
            val state = if(saved) "SUCCEEDED" else if(unknown(jobId,unit.unitId,unit.page)) "UNKNOWN_OUTCOME"
                else attempt?.string("state")?.takeIf { it != "SUCCEEDED" } ?: "PLANNED"'''
assert text.count(old) == 1
store.write_text(text.replace(old, new))

copies = {
    "ProcessingUnit.kt": "shared/knowledge-api/src/main/kotlin/runtime/mobileagent/knowledge/ProcessingUnit.kt",
    "DocumentUnitPlannerReviewRegressionTest.kt": "shared/knowledge-api/src/test/kotlin/runtime/mobileagent/knowledge/DocumentUnitPlannerReviewRegressionTest.kt",
    "DocumentPipelineReviewRegressionTest.kt": "data/sqlite/src/test/kotlin/runtime/mobileagent/data/DocumentPipelineReviewRegressionTest.kt",
}
for source, dest in copies.items():
    path = ROOT / dest
    if source != "ProcessingUnit.kt":
        assert not path.exists(), f"Refuse to overwrite existing test: {path}"
    path.write_text((STAGING / source).read_text())

wire = ROOT / "app-android/src/test/kotlin/runtime/mobileagent/OpenAiCompatibleVisionTest.kt"
text = wire.read_text()
anchor = "    private fun complexPagePdf(text: String): ByteArray {"
assert text.count(anchor) == 1
wire.write_text(text.replace(anchor, (STAGING / "VisionWireRegression.kt").read_text().split("\n", 3)[3] + anchor))

# Only remove scaffold files created for this task. No existing document, cache,
# workspace or source is deleted. The final candidate tree contains no helper job.
for path in list(STAGING.iterdir()):
    assert path.name in {"apply.py", "ProcessingUnit.kt", "DocumentUnitPlannerReviewRegressionTest.kt", "DocumentPipelineReviewRegressionTest.kt", "VisionWireRegression.kt"}
    path.unlink()
STAGING.rmdir()
(ROOT / ".github/workflows/review-repair-20260918.yml").unlink()
subprocess.run(["git", "diff", "--check"], check=True)
print("Applied hash-bound R1-R5 patch; no provider was called; no branch was advanced.")
