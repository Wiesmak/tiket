# Verify delivery artifacts
PR and development snapshots report **all HIGH findings as warnings**. CRITICAL findings and scanner errors block development publication. Tagged releases block both HIGH and CRITICAL. No exception inventory weakens these gates. Signed snapshots are **DEVELOPMENT SNAPSHOT — NOT PRODUCTION APPROVED**.

Image and chart signing and verification are mandatory. The shared trusted publication workflow requires validation and protected environments; documentation CI has no publishing permission or signing secrets. A registry push before a later signing failure does not constitute a verified release.

Obtain the expected public key through a trusted channel. Verify image and chart by immutable digest:
```sh
cosign verify --key trusted-cosign.pub ghcr.io/wiesmak/tiket@sha256:<image-digest>
cosign verify --key trusted-cosign.pub ghcr.io/wiesmak/charts/tiket@sha256:<chart-digest>
cosign verify-blob --key trusted-cosign.pub --bundle SHA256SUMS.sigstore.json SHA256SUMS
sha256sum --check SHA256SUMS
helm pull oci://ghcr.io/wiesmak/charts/tiket --version <verified-version>
```
Match the pulled chart to the verified digest and checksum evidence before installing. Configure `image.digest` in reviewed values. Do not trust an artifact merely because its tag or bundled public key looks familiar.

Verified hosted example: [Snapshot run 43080182942](https://github.com/Wiesmak/tiket/actions/runs/43080182942), version `0.0.0-dev.3.3.g1397b83c`, reports successful publication/signature verification in its downloaded provenance:
- Image: `sha256:47ec73c82a9d34487fcd4422bb6a9841d0e8da29059e4b1f23dbfc69d630b73f`
- Chart: `sha256:f4b519395a91225fe3a123dddd28ab076d766c50cf679bd4c217967c903ceccc`
- 32 HIGH, 0 CRITICAL; development WARN_HIGH, release FAIL.

This predates the serial merge. The merged commit's [Snapshot run 43120071980](https://github.com/Wiesmak/tiket/actions/runs/43120071980) has also completed successfully, publishing `0.0.0-dev.4.1.g94533897`:

- Image: `ghcr.io/wiesmak/tiket@sha256:518c0fca8974fc8bfda71b535a4cdd8fa82dc68d9bab715f42e11a8a75dbf2de`
- Chart: `ghcr.io/wiesmak/charts/tiket@sha256:4eb55f0cc75ccdf872f8f54e3333dcb53439e87a8ed1754886ece54de8a42489`
- Downloaded provenance reports verified signatures, 32 HIGH, 0 CRITICAL and WARN_HIGH; strict release evaluation remains FAIL.

These are signed development examples, not official releases or production approval. No official GitHub releases were listed at this audit. Verification above was performed by the hosted publication workflow; an independent local Cosign trust audit is not claimed.

Publication also produces SBOMs, license reports and signed checksums/provenance. License scanning does not establish redistribution rights. Retain digest/key identities during key rotation, distribute new keys through the trusted channel and preserve historical verification keys. Multi-artifact registry publication is not transactional.
