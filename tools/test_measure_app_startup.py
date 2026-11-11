# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import unittest
from measure_app_startup import startup_ms, summarize


class StartupMetricsTest(unittest.TestCase):
    def test_failed_or_missing_timing_is_not_reported_as_zero(self):
        for raw in ('Status: timeout\nTotalTime: 1', 'Status: ok\nWarning: not started', 'Status: ok\nTotalTime: 0'):
            with self.assertRaises(ValueError):
                startup_ms(raw)
        self.assertEqual(204, startup_ms('Status: ok\nTotalTime: 204\nWaitTime: 220'))

    def test_percentile_uses_real_samples_and_preserves_observation_order(self):
        result = summarize([30, 10, 20])
        self.assertEqual([30, 10, 20], result['samplesMs'])
        self.assertEqual(20, result['medianMs'])
        self.assertEqual(30, result['p95Ms'])


if __name__ == '__main__':
    unittest.main()
