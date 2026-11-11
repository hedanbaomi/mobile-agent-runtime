# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
import unittest
from measure_frame_times import frame_durations


class FrameMetricTest(unittest.TestCase):
    def test_ignores_incomplete_and_flagged_frames(self):
        raw = '''---PROFILEDATA---
Flags,IntendedVsync,FrameCompleted
0,1000000,17000000
0,2000000,32000000
1,3000000,990000000
0,3000000,9223372036854775807
---PROFILEDATA---'''
        self.assertEqual(dict(frames=2, p50Ms=16, p95Ms=30, maxMs=30), frame_durations(raw))
        with self.assertRaises(ValueError):
            frame_durations('No frames rendered')


if __name__ == '__main__':
    unittest.main()
