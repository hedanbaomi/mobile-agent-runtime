// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import { generateKeyPairSync } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve, relative, isAbsolute } from 'node:path';

const privateRoot = resolve('.private/overnight/announcements-production');
const output = resolve(process.argv[2] ?? privateRoot);
const relativeOutput = relative(privateRoot, output);
if (isAbsolute(relativeOutput) || relativeOutput.startsWith('..')) {
  throw new Error('Signing secrets must stay in the ignored project private directory');
}
mkdirSync(output, { recursive: true });
const { privateKey, publicKey } = generateKeyPairSync('ed25519');
const privateDer = privateKey.export({ type: 'pkcs8', format: 'der' });
const publicDer = publicKey.export({ type: 'spki', format: 'der' });
if (publicDer.length !== 44) throw new Error('Unexpected Ed25519 SPKI encoding');
const metadata = {
  keyId: 'mar-prod-20260829-1',
  algorithm: 'Ed25519',
  publicKeyHex: publicDer.subarray(-32).toString('hex'),
  createdAt: new Date().toISOString(),
};
writeFileSync(resolve(output, 'private-key.pkcs8.base64'), privateDer.toString('base64') + '\n', { flag: 'wx', mode: 0o600 });
writeFileSync(resolve(output, 'public-key.json'), JSON.stringify(metadata, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
privateDer.fill(0);
process.stdout.write(JSON.stringify(metadata) + '\n');
