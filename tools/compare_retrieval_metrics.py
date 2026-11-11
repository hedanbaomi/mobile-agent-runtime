# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Compare identity-matched retrieval runs; legacy count-only metrics cannot prove equivalence."""
import argparse
import json
import math
import pathlib
import re

IDENTITY = ("schema", "fixture", "corpusSha256", "identitySha256", "spaceId", "dimension", "sdk", "abi", "warmup")
NUMERIC = ("recallAt8", "mrrAt8", "p50Ms", "p95Ms", "warmP50Ms", "warmP95Ms",
           "firstQueryPassP50Ms", "firstQueryPassP95Ms", "singleLibraryP50Ms",
           "importMs", "buildMs", "pssKb", "nativeBytes")


def _identity(baseline, candidate):
    for key in IDENTITY:
        if key not in baseline or key not in candidate or baseline[key] != candidate[key]:
            raise ValueError(f"Incompatible or missing metric identity: {key}")
    for metrics in (baseline, candidate):
        for key in ("corpusSha256", "identitySha256"):
            if not isinstance(metrics[key], str) or not re.fullmatch("[0-9a-f]{64}", metrics[key]):
                raise ValueError(f"Invalid metric identity: {key}")
        for key in ("fixture", "spaceId", "abi", "warmup"):
            if not isinstance(metrics[key], str) or not metrics[key].strip():
                raise ValueError(f"Invalid metric identity: {key}")
        for key in ("schema", "dimension", "sdk"):
            if type(metrics[key]) is not int or metrics[key] <= 0:
                raise ValueError(f"Invalid metric identity: {key}")


def _distribution(baseline, candidate):
    if (set(NUMERIC) & baseline.keys()) != (set(NUMERIC) & candidate.keys()):
        raise ValueError("Metric fields differ; a partial comparison would hide missing evidence")
    for key in ("queries", "libraries"):
        if (type(baseline.get(key)) is not int or baseline[key] <= 0 or
                type(candidate.get(key)) is not int or baseline[key] != candidate[key]):
            raise ValueError(f"Incompatible metric: {key}")
    for metrics in (baseline, candidate):
        for key in ("recallAt8", "mrrAt8"):
            value = metrics.get(key)
            if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 1:
                raise ValueError(f"Invalid metric: {key}")
        for key in NUMERIC:
            if key not in metrics:
                continue
            value = metrics[key]
            if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
                raise ValueError(f"Invalid metric: {key}")
    return {key: {"baseline": baseline[key], "candidate": candidate[key],
                  "delta": candidate[key] - baseline[key]}
            for key in NUMERIC if key in baseline and key in candidate}


def compare(baseline, candidate):
    _identity(baseline, candidate)
    if "distributions" not in baseline and "distributions" not in candidate:
        return _distribution(baseline, candidate)
    def grouped(metrics):
        items = metrics.get("distributions")
        if not isinstance(items, list) or not items:
            raise ValueError("Missing distributions")
        result = {}
        for item in items:
            if not isinstance(item, dict) or type(item.get("libraries")) is not int:
                raise ValueError("Invalid distribution")
            key = item["libraries"]
            if key <= 0 or key in result:
                raise ValueError("Duplicate or invalid library distribution")
            result[key] = item
        return result
    before, after = grouped(baseline), grouped(candidate)
    if before.keys() != after.keys():
        raise ValueError("Incompatible library distributions")
    return {str(key): _distribution(before[key], after[key]) for key in sorted(before)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=pathlib.Path)
    parser.add_argument("candidate", type=pathlib.Path)
    args = parser.parse_args()
    read = lambda path: json.loads(path.read_text(encoding="utf-8-sig"))
    print(json.dumps(compare(read(args.baseline), read(args.candidate)), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
