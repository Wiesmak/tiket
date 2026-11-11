// Extract fixed signals only; raw daemon logs / hs_err / heaps remain private.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
const out = 'build/ci-diagnostics';
fs.mkdirSync(out, {recursive: true});
const signals = [
  'OutOfMemoryError', 'Metaspace', 'Java heap space', 'GC overhead limit exceeded',
  'DaemonDisappearedException', 'daemon has disappeared', 'Kotlin compiler',
  'Compilation error', 'Could not resolve', 'Could not download', 'protoc',
  'Gradle Test Executor', 'BUILD FAILED', 'BUILD SUCCESSFUL', 'SIGSEGV', 'SIGBUS',
];
const home = process.env.GRADLE_USER_HOME || path.join(os.homedir(), '.gradle');
const candidates = [];
function walk(dir, depth, accept) {
  if (depth < 0 || !fs.existsSync(dir)) return;
  for (const e of fs.readdirSync(dir, {withFileTypes: true})) {
    const f = path.join(dir, e.name);
    if (e.isDirectory()) walk(f, depth - 1, accept);
    else if (accept(e.name)) candidates.push(f);
  }
}
walk(path.join(home, 'daemon'), 2, n => /^daemon-.*\.out\.log$/.test(n));
walk('.', 3, n => /^hs_err_pid.*\.log$/.test(n));
const counts = Object.fromEntries(signals.map(s => [s, 0]));
for (const f of candidates) {
  const stat = fs.statSync(f);
  if (stat.mtimeMs < Date.now() - 24 * 3600_000) continue;
  const fd = fs.openSync(f, 'r');
  const size = Math.min(stat.size, 2 * 1024 * 1024);
  const b = Buffer.alloc(size);
  fs.readSync(fd, b, 0, size, stat.size - size);
  fs.closeSync(fd);
  const text = b.toString('utf8');
  for (const s of signals) counts[s] += text.split(s).length - 1;
}
fs.writeFileSync(path.join(out, 'jvm-signals.json'), JSON.stringify(counts, null, 2));
