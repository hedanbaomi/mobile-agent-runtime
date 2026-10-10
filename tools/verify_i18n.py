# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only
"""Ratchet feature string literals and check bilingual resource keys.

Only feature/*/src/main/kotlin and two exact strings.xml paths are read.
Baseline literal fingerprints and multiplicities are independent of line numbers.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

HAN = re.compile(r'[\u3400-\u9fff]')
TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*|\*/')
FORMAT = re.compile(r'%%|%([1-9]\d*)\$([a-zA-Z])')

def literals(source):
    depth = 0
    for token in TOKEN.finditer(source):
        value = token[0]
        if value == '/*':
            depth += 1
        elif value == '*/':
            depth = max(0, depth-1)
        elif not depth and not value.startswith('//') and HAN.search(value):
            line = source[source.rfind('\n', 0, token.start())+1:source.find('\n', token.end()) if '\n' in source[token.end():] else len(source)]
            if re.match(r'\s*(?:throw\b|require(?:NotNull)?\(|check(?:NotNull)?\(|error\(|Log\.[dviwe]\(|println\()', line):
                continue
            yield hashlib.sha256(value.encode('utf-8')).hexdigest()[:16]

def state(root):
    result = {}
    for module in sorted((root/'feature').iterdir()):
        if not module.is_dir():
            continue
        for file in sorted((module/'src/main/kotlin').rglob('*.kt')):
            hits = Counter(literals(file.read_text(encoding='utf-8')))
            if hits:
                result[file.relative_to(root).as_posix()] = dict(sorted(hits.items()))
    return result

def resource_keys(file):
    if not file.exists():
        return set()
    keys = [e.attrib['name'] for e in ET.parse(file).getroot() if e.tag in ('string', 'plurals', 'string-array')]
    if len(keys) != len(set(keys)):
        raise ValueError(f'duplicate resource key: {file}')
    return set(keys)

def format_contracts(file):
    if not file.exists():
        return {}
    return {entry.attrib['name']: {(m[1], m[2]) for m in FORMAT.finditer(''.join(entry.itertext())) if m[1]}
            for entry in ET.parse(file).getroot() if entry.tag == 'string' and entry.attrib.get('formatted') != 'false'}

def check(root, baseline):
    problems = []
    for file, current in state(root).items():
        excess = Counter(current) - Counter(baseline.get(file, {}))
        if excess:
            problems.append(f'{file}: {sum(excess.values())} new hardcoded Chinese literals; use bilingual resources')
    for module in sorted((root/'feature').iterdir()):
        if module.is_dir():
            default = resource_keys(module/'src/main/res/values/strings.xml')
            chinese = resource_keys(module/'src/main/res/values-zh-rCN/strings.xml')
            if default != chinese:
                problems.append(f'feature/{module.name}: resource translation keys differ: {sorted(default ^ chinese)}')
            formats = format_contracts(module/'src/main/res/values/strings.xml')
            translations = format_contracts(module/'src/main/res/values-zh-rCN/strings.xml')
            for key in formats.keys() & translations.keys():
                if formats[key] != translations[key]:
                    problems.append(f'feature/{module.name}: positional format contract differs: {key}')
    return problems

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--write-baseline', action='store_true', help='Explicit reviewed initial capture; never run in CI')
    parser.add_argument('--state', action='store_true')
    args = parser.parse_args()
    file = args.root/'tools/i18n-baseline.json'
    if args.write_baseline:
        payload = {'SPDX-FileCopyrightText': '2026 mobileAgentRuntime contributors', 'SPDX-License-Identifier': 'AGPL-3.0-only', 'note': 'Reviewed legacy bilingual/helper literals; new presentation copy belongs in resources.', 'files': state(args.root)}
        file.write_text(json.dumps(payload, indent=2, ensure_ascii=False)+'\n', encoding='utf-8', newline='\n')
        print(f'Captured reviewed baseline: {sum(sum(v.values()) for v in payload["files"].values())} literals')
        return 0
    if args.state:
        print(json.dumps(state(args.root), indent=2))
        return 0
    if not file.exists():
        print('Missing reviewed i18n baseline', file=sys.stderr)
        return 1
    problems = check(args.root, json.loads(file.read_text(encoding='utf-8'))['files'])
    for problem in problems:
        print(problem, file=sys.stderr)
    if not problems:
        print('Feature literal ratchet and bilingual resource keys passed')
    return bool(problems)

if __name__ == '__main__':
    sys.exit(main())
