$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    Push-Location scripts
    try {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    } finally { Pop-Location }
    node scripts/test-kind.mjs @args
    if ($LASTEXITCODE -ne 0) { throw "kind tests failed ($LASTEXITCODE)" }
} finally { Pop-Location }
