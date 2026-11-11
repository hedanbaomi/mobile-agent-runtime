# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import unittest
from compare_retrieval_metrics import compare


def sample():
    return dict(schema=1, fixture="fixed-v1", corpusSha256="a"*64, identitySha256="b"*64,
                spaceId="model-fingerprint", dimension=512, sdk=36, abi="x86_64", warmup="fixed",
                queries=16, libraries=4, recallAt8=1.0, mrrAt8=0.8, p50Ms=35)


class MetricComparisonTest(unittest.TestCase):
    def test_same_counts_cannot_hide_different_corpus_ids_model_or_device(self):
        baseline = sample()
        for change in (dict(corpusSha256="c"*64), dict(identitySha256="c"*64),
                       dict(spaceId="other-model"), dict(sdk=26), dict(abi="arm64-v8a"),
                       dict(warmup="cold"), dict(queries=20), dict(dimension=384), dict(libraries=1)):
            with self.subTest(change=change), self.assertRaises(ValueError):
                compare(baseline, baseline | change)
        self.assertEqual(-5, compare(baseline, baseline | dict(p50Ms=30))["p50Ms"]["delta"])

    def test_legacy_missing_identity_and_invalid_numbers_are_rejected(self):
        for key in ("corpusSha256", "identitySha256", "spaceId"):
            incomplete = sample()
            del incomplete[key]
            with self.assertRaises(ValueError):
                compare(incomplete, incomplete)
        for key, value in (("recallAt8", -1), ("mrrAt8", True), ("p50Ms", float("nan")),
                           ("p50Ms", -5), ("warmP50Ms", float("inf")), ("queries", True)):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                compare(sample(), sample() | {key: value})

    def test_nested_distributions_match_by_library_count_and_reject_duplicates(self):
        first = dict(sample(), distributions=[sample() | dict(libraries=1), sample()])
        second = dict(first, distributions=[sample() | dict(p50Ms=30), sample() | dict(libraries=1)])
        self.assertEqual(-5, compare(first, second)["4"]["p50Ms"]["delta"])
        self.assertEqual(0, compare(first, second)["1"]["p50Ms"]["delta"])
        with self.assertRaises(ValueError):
            compare(first, dict(first, distributions=[sample(), sample()]))
        with self.assertRaises(ValueError):
            compare(first, dict(first, distributions=[sample()]))


if __name__ == "__main__":
    unittest.main()
