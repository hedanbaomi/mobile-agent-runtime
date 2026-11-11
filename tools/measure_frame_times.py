# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Measure rendered frame durations on an explicitly prepared synthetic Android screen.

Uses Android gfxinfo timestamps. This is not Macrobenchmark or a claim about
physical-device responsiveness. The foreground must already be the target app.
"""
import argparse
import csv
import io
import json
import pathlib
import subprocess


def frame_durations(raw):
    samples = []
    for block in raw.split('---PROFILEDATA---')[1::2]:
        lines = [line.strip() for line in block.splitlines() if line.strip()]
        if not lines or not lines[0].startswith('Flags,'):
            continue
        for row in csv.DictReader(io.StringIO('\n'.join(lines))):
            try:
                if int(row['Flags']) != 0:
                    continue
                start, end = int(row['IntendedVsync']), int(row['FrameCompleted'])
                if 0 < start <= end < 2**63 - 1:
                    samples.append((end - start) / 1_000_000)
            except (KeyError, ValueError, TypeError):
                continue
    if not samples:
        raise ValueError('No complete rendered frames; measurement is unverified')
    ordered = sorted(samples)
    percentile = lambda p: ordered[min(len(ordered)-1, (len(ordered)*p+99)//100-1)]
    return {'frames': len(samples), 'p50Ms': percentile(50), 'p95Ms': percentile(95), 'maxMs': max(samples)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--swipe', nargs=4, type=int, required=True, metavar=('X1', 'Y1', 'X2', 'Y2'))
    parser.add_argument('--repeats', type=int, choices=range(1, 21), default=6)
    args = parser.parse_args()
    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.serial, 'shell', *map(str, command)], text=True, encoding='utf-8', timeout=60)
    focus = adb('dumpsys', 'window')
    if not any('mCurrentFocus=' in line and 'runtime.mobileagent/' in line for line in focus.splitlines()):
        raise ValueError('Prepare a synthetic app screen in the foreground first')
    adb('dumpsys', 'gfxinfo', 'runtime.mobileagent', 'reset')
    for _ in range(args.repeats):
        adb('input', 'swipe', *args.swipe, 400)
    metric = {'schema': 1, 'metric': 'gfxinfo-frame-duration',
              'sdk': adb('getprop', 'ro.build.version.sdk').strip(),
              **frame_durations(adb('dumpsys', 'gfxinfo', 'runtime.mobileagent', 'framestats'))}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(metric, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(metric))


if __name__ == '__main__':
    main()
