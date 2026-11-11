# Serial printing

The sixth root Gradle module, `tiket-print-serial`, implements the existing
`TicketPrinter` contract. It depends only on the print API, Java2D,
`com.github.anastaciocintra:escpos-coffee:4.1.0` and
`com.fazecast:jSerialComm:2.11.4` from Maven Central. No Spring, database,
financial logic or gRPC is present in the adapter.

## Raster receipt

`TicketRasterRenderer` lays out all 18 frozen print fields using Java logical
Monospaced fonts, with bold heading, reprint marker and total. Dates use
`yyyy-MM-dd HH:mm` with Locale.ROOT. It wraps by measured character width,
including long identifiers, selections and amounts. Control and format
characters become spaces. It never expands bets or recalculates stakes.
Java2D works with `-Djava.awt.headless=true`; a desktop is unnecessary.
Install a supported JDK with java.desktop and logical-font backing fonts
(e.g. DejaVu fonts/fontconfig on minimal Linux systems). No Windows font or
bundled proprietary font is required. Exact glyph pixels may vary by platform.

Every image is exactly 384 pixels wide, TYPE_BYTE_BINARY, black on white.
Layout is measured before allocating the receipt image, capped at 32,000
characters and a configurable maximum height (default 4,000 rows; allowed
256–20,000). Over-limit receipts fail permanently with a sanitized error.
Do not raise limits without reviewing transfer time and lease duration.

`EscPosRasterEncoder` passes contiguous subimages through
`EscPosImage(CoffeeImageImpl(image), BitonalThreshold())` and
`RasterBitImageWrapper`. Default bands are 128 rows, always 384 pixels
(48 bytes per raster row). Commands are left justification from the wrapper
(`ESC a 48`), `GS v 0` in normal size with band width/height and bitmap data,
optional vendor commands, then `ESC d n` for final feed.
No feeds occur between bands. No native receipt text, fonts, barcode, QR,
initialization or cut commands are sent.
Coffee's `feed()` emits style commands, so final feed is encoded directly.

The optional vendor commands are:
`1D 28 4B 02 00 31 density` and `1D 28 4B 02 00 32 speed`.
Defaults 50 and 5 match the owner's tested profile, with ranges 0–127 and
1–9. Disable them for models where they are unsupported.

## Backend configuration

Console remains the default and its worker behavior is unchanged.
Explicit serial mode never falls back to console on invalid routes.
Example local application YAML (ports are examples, never library defaults):

```yaml
tiket:
  printing:
    enabled: true
    backend: serial
    lease-seconds: 300
    serial:
      routes:
        - pos: 1
          port: COM9
          baud: 9600
          data-bits: 8
          stop-bits: 1
          parity: 0
          write-timeout-millis: 5000
          vendor-commands: true
          density: 50
          speed: 5
          feed-lines: 3
          band-height: 128
          max-height: 4000
        - pos: 2
          port: COM10
        - pos: 3
          port: COM9
```

Every route carries a complete profile with these defaults. Shared ports
must have identical profiles. POS values must be positive and unique;
port names must be nonblank without controls or surrounding whitespace.
At most 64 POS routes and 16 physical ports are accepted. Windows COM names
are case normalized for locking. Use one consistent canonical device name
per physical device; do not configure Linux symlink aliases or alternative
Windows device paths for the same hardware.

Parity values follow jSerialComm (0 none, 1 odd, 2 even, 3 mark, 4 space).
Stop-bit values are its constants (1 one, 2 one-and-a-half, 3 two).
Data bits 5–8 and baud 1–4,000,000 are validated; hardware/driver rejection
is reported during dispatch. Flow control is explicitly disabled.
For another model set `vendor-commands: false`.

Serial startup validates routes and requires an explicit lease of at least
300 seconds. Startup does not enumerate/open ports. With printing disabled,
no worker is registered and no serial operations occur (configuration still
validates). Unknown adapter POS fails permanently. An unconfigured POS in
the database remains unclaimed for another host; it is never consumed by an
arbitrary printer.

## Dispatch, retries and leases

A dedicated serial worker groups routes by physical port. One bounded lane
per port runs through a fixed-size executor with a synchronous handoff and
no job queue. A busy lane cannot claim another job. Independent ports run
simultaneously; shared POS routes use the same lane. The adapter also uses
a lock per port and returns retryable busy rather than waiting with a claim.
Locks cover the whole render/open/write/close operation within one adapter.
Use one adapter per process. Cross-process port exclusivity depends on the
OS driver; never intentionally run competing hosts against one device.

`claimForPos(Set<Int>)` uses persisted coupon POS values, placeholders and
`FOR UPDATE OF j SKIP LOCKED`; the durable attempt/retry state machine is
shared with unfiltered `claim()`. No migration or second outbox is needed.
Only configured POS jobs are claimed in serial mode. A legacy unfiltered
console worker sharing the database can consume *all* POS jobs: disable
unfiltered workers in mixed routed deployments.

9600 baud 8N1 yields roughly 960 payload bytes/s. A 900-row receipt needs
43,200 raster bytes, roughly 45 seconds before overhead. The default
4,000-row cap needs about 200 seconds nominally. Use at least
`tiket.printing.lease-seconds=300`, allow extra driver/mechanical delay,
and increase the lease (existing maximum 3,600) when increasing max-height
or reducing baud. There is no lease renewal. Size receipt limits and leases
for the slowest printer. Serial completion locks the job and checks the
current owner and unexpired lease before delegating to existing completion.
Expired/stale owners cannot acknowledge success.

Dispatched means all planned bytes were handed to the stream without a
detected failure; it does not prove paper output. Offline/open/native/write
failures are retryable; partial writes have unknown outcome and retry may
duplicate printing. Invalid layout and unknown adapter routes are permanent.
Errors contain no receipt payload. Financial records and purchased coupons
are never reversed or changed because printing fails. Printing remains at
least once, including after crash/restart or lease recovery.

Sessions own the stream and port; the EscPos view flushes without closing
the physical stream. Both close on success/failure. On shutdown new claims
stop, active lanes get a shared 10-second completion window, then interrupts
are requested. Threads are daemon threads so a blocked native write cannot
hold JVM shutdown forever. Unfinished jobs recover through their leases.

jSerialComm documents write timeouts as Windows-only:
[SerialPort timeout API](https://fazecast.github.io/jSerialComm/javadoc/com/fazecast/jSerialComm/SerialPort.html#setComPortTimeouts(int,int,int)).
Linux blocking native writes need not honor the Windows 5-second timeout;
interrupts and stream flush cannot promise bounded hardware completion.
Driver/USB disconnects can leave uncertain partial output. Do not treat flush
or close as a printer acknowledgement.

## Local ports and smoke test

Windows Device Manager → Ports (COM & LPT) shows COM names. The optional
utility also prints names and descriptions. COM9 is the owner's reference
device, not an automatic fallback. Close any terminal/vendor program holding it.

Linux devices usually appear as /dev/ttyUSB* or /dev/ttyACM*; grant the local
service user the distribution's serial-device group (often dialout or uucp),
then log in again. Inspect device ownership and udev rules; avoid world-writable
permissions. Stable /dev/serial/by-id paths are useful when used consistently.

Run only with an explicitly attached printer and paper loaded:

```powershell
.\gradlew.bat :tiket-print-serial:serialSmoke -PserialSmokePort=COM9 --no-configuration-cache
```

On Linux use `./gradlew` and `-PserialSmokePort=/dev/ttyUSB0`.
This task is never wired into build/check. It enumerates ports, opens only
the supplied port with 9600/8N1/5-second write timeout, prints a small bordered
384-pixel diagnostic, a representative TicketPrintData fixture and a REPRINT.
All are clearly marked diagnostics, not purchased tickets. It reports stream
transmission results and closes each session. Visually inspect width, band
joins, amounts, selection and reprint label. Hardware output has not been
verified by automated tests. For normal purchased tickets use the durable
backend outbox and existing reprint operation; never bypass it.

## Automated validation and diagnostics

The standalone tests use injected sessions/in-memory streams, pixel bounds,
full byte-level raster reconstruction across many bands, and barriers/latches
for concurrency. Backend tests use Mockito for the jSerialComm boundary with
the existing startup Java-agent strategy, plus PostgreSQL 18 Testcontainers
for filtered concurrent claims, durable retries, lease expiry and port lanes.
Spring context tests cover default console and validated serial selection.
No real or virtual serial devices are required.

```text
gradlew.bat :tiket-print-serial:build --no-configuration-cache
gradlew.bat :tiket-backend:test --no-configuration-cache
bash scripts/ci-gradle.sh clean build --no-build-cache
```

Preserve the existing one-worker/in-process Kotlin compiler memory settings
when invoking Gradle directly on Windows.

Unknown POS: correct routing on the intended printer host.
Unavailable port: inspect cable, permissions, driver and competing processes.
Rejected parameters: verify device baud/parity/stop-bit support.
Native library failure: check OS/JDK architecture, temp extraction permissions
and jSerialComm native requirements. Never log raw receipt bytes to diagnose.
Oversized receipt: inspect input length and safe limits before raising them.
After restart inspect durable print status/attempts; uncertain output may
need operator reconciliation before reprinting.

Default containers, kind and Helm continue using console and need no serial
device. This backend is intended for a local JVM on the attached hardware.
No privileged containers, host-device mounts or Kubernetes USB configuration
are added. Hardware-attached container deployment requires a separate review
of device ownership, native libraries, scheduling and platform permissions.
Standalone release-JAR packaging inventory is deferred to a separate follow-up;
protected CI, publication, signing and security policy files are untouched.
This document covers implementation only; Phase 9 remains pending.
