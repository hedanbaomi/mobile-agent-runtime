# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Measure cold process startup on an explicitly selected synthetic test device.

Force-stops the app between samples. Does not clear app data, capture screens,
collect logcat or call providers. Output excludes serial numbers and user data.
"""
import argparse
import json
import pathlib
import re
import statistics
import subprocess


def startup_ms(text):
    if not re.search(r'^Status:\s*ok\s*$', text, re.M):
        raise ValueError('Activity did not report successful startup')
    found = re.search(r'^TotalTime:\s*(\d+)\s*$', text, re.M)
    if not found or int(found[1]) == 0:
        raise ValueError('Startup timing is unavailable')
    return int(found[1])


def summarize(samples):
    if len(samples) < 3 or any(value < 0 for value in samples):
        raise ValueError('At least three nonnegative samples required')
    ordered = sorted(samples)
    return {'samplesMs': samples, 'medianMs': statistics.median(samples),
            'p95Ms': ordered[min(len(ordered)-1, (len(ordered)*95+99)//100-1)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--samples', type=int, default=10, choices=range(3, 31))
    args = parser.parse_args()
    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.serial, *command], text=True, encoding='utf-8', timeout=60)
    samples = []
    for _ in range(args.samples):
        adb('shell', 'am', 'force-stop', 'runtime.mobileagent')
        samples.append(startup_ms(adb('shell', 'am', 'start', '-W', '-n', 'runtime.mobileagent/.MainActivity')))
    result = {'schema': 1, 'metric': 'cold-process-activity-startup',
              'sdk': adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
              'abi': adb('shell', 'getprop', 'ro.product.cpu.abi').strip(),
              **summarize(samples)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
