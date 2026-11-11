import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { evaluate, record } from './vulnerability-gate.mjs';

export function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 100 * 1024 * 1024, timeout: 15 * 60 * 1000 });
  if (result.error || result.status !== 0) throw new Error(command + ' failed: ' + (result.error?.message ?? result.stderr));
  return result.stdout;
}
const [mode, image, directory] = process.argv.slice(2);
let temporary;
let output;
try {
  if (!['development','release'].includes(mode) || !image || !directory)
    throw new Error('Usage: scan-image.mjs development|release IMAGE OUTPUT_DIRECTORY');
  mkdirSync(directory, { recursive: true });
  output = join(directory, 'vulnerability-summary.json');
  // Fresh temporary archive: never accept a stale report after a scanner failure.
  temporary = mkdtempSync(join(tmpdir(), 'tiket-scan-'));
  const scanner = 'aquasec/trivy:0.67.2';
  run('docker', ['save', '-o', join(temporary, 'image.tar'), image]);
  const version = run('docker', ['run','--rm',scanner,'--version']);
  const raw = run('docker', ['run','--rm','-v',resolve(temporary) + ':/input:ro',scanner,
    'image','--input','/input/image.tar','--scanners','vuln','--format','json','--exit-code','0','--timeout','10m']);
  writeFileSync(join(directory, 'vulnerabilities.json'), raw);
  const [inspection] = JSON.parse(run('docker',['image','inspect', image]));
  const report = JSON.parse(raw);
  if (report.Metadata?.ImageID !== inspection.Id) throw new Error('Scanned image identity mismatch');
  writeFileSync(join(directory, 'scan-metadata.json'), JSON.stringify({
    scanner, version, completedAt: new Date().toISOString(), image,
    baseImage: readFileSync('Dockerfile','utf8').match(/^ARG BASE_IMAGE=(.+)$/m)?.[1],
    imageID: inspection.Id, repoDigests: inspection.RepoDigests, layers: inspection.RootFS.Layers
  }, null, 2));
  const summary = evaluate(report, mode);
  record(summary, output);
  process.exitCode = summary.decision === 'FAIL' ? 1 : 0;
} catch (error) {
  if (output) record({ policy: mode, decision: 'FAIL', error: error.message }, output);
  console.error(error.message);
  process.exitCode = 1;
} finally {
  if (temporary) rmSync(temporary, { recursive: true, force: true });
}
