#!/usr/bin/env bash
# Only called by trusted publishing workflows after validation.
set -euo pipefail
: "${VERSION:?}" "${GH_TOKEN:?}" "${TIKET_COSIGN_PRIVATE_KEY:?}" "${TIKET_COSIGN_PASSWORD:?}"
case "${CHANNEL:?Missing publication channel}" in
 snapshot)
  [[ "$GITHUB_EVENT_NAME" == push && "$GITHUB_REF" == refs/heads/master && "$GITHUB_WORKFLOW_REF" == Wiesmak/tiket/.github/workflows/snapshot.yml@refs/heads/master ]]
  policy=development ;;
 release)
  [[ "$GITHUB_EVENT_NAME" == push && "$GITHUB_REF" =~ ^refs/tags/v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ && "$GITHUB_WORKFLOW_REF" == Wiesmak/tiket/.github/workflows/release.yml@refs/tags/v* ]]
  policy=release ;;
 *) echo "Invalid publication channel" >&2; exit 1 ;;
esac
export COSIGN_PASSWORD="$TIKET_COSIGN_PASSWORD"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
umask 077
printf '%s' "$TIKET_COSIGN_PRIVATE_KEY" > "$tmp/cosign.key"
cosign public-key --key "$tmp/cosign.key" > "$tmp/cosign.pub"
mkdir -p dist
cp "$tmp/cosign.pub" dist/cosign.pub
image=ghcr.io/wiesmak/tiket
chart=ghcr.io/wiesmak/charts/tiket
printf '%s' "$GH_TOKEN" | docker login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
printf '%s' "$GH_TOKEN" | helm registry login ghcr.io -u "$GITHUB_ACTOR" --password-stdin
# Fail closed for unexpected registry responses. Never overwrite an existing version.
for repo in wiesmak/tiket wiesmak/charts/tiket; do
 token=$(curl --fail --silent --show-error -u "$GITHUB_ACTOR:$GH_TOKEN" "https://ghcr.io/token?service=ghcr.io&scope=repository:$repo:pull" | jq -er .token)
 status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" -H 'Accept: application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.list.v2+json' "https://ghcr.io/v2/$repo/manifests/$VERSION")
 test "$status" = 404 || { echo "Refusing publication: $repo:$VERSION status $status"; exit 1; }
done
timeout --signal=TERM --kill-after=30s 25m bash scripts/ci-gradle.sh build :tiket-backend:bootJar "-PreleaseVersion=$VERSION"
bash scripts/ci-gradle.sh --stop
cp tiket-{backend,core,contracts,print-api,print-console}/build/libs/*.jar dist/
buildLabels=()
if [[ "$CHANNEL" == snapshot ]]; then
 buildLabels+=(--label "org.opencontainers.image.description=DEVELOPMENT SNAPSHOT — NOT PRODUCTION APPROVED")
fi
docker build "${buildLabels[@]}" --build-arg VERSION="$VERSION" --build-arg REVISION="$GITHUB_SHA" --build-arg CREATED="$(date -u +%FT%TZ)" -t "$image:$VERSION" .
bash scripts/container-smoke.sh "$image:$VERSION"
node scripts/security/scan-image.mjs "$policy" "$image:$VERSION" dist
if [[ "$CHANNEL" == snapshot ]]; then
 printf '%s\n' 'DEVELOPMENT SNAPSHOT — NOT PRODUCTION APPROVED' > dist/NOTICE.txt
fi
node scripts/generate-sbom.mjs "$image:$VERSION" dist
docker run --rm -v "$PWD/dist:/scan" aquasec/trivy:0.67.2 fs --scanners license --format json --output /scan/licenses.json /scan
docker push "$image:$VERSION"
imageDigest=$(docker buildx imagetools inspect "$image:$VERSION" --format '{{json .Manifest.Digest}}' | jq -er .)
cp -R charts/tiket "$tmp/chart"
if [[ "$CHANNEL" == snapshot ]]; then
 printf '\nannotations:\n  tiket.dev/approval: "DEVELOPMENT SNAPSHOT — NOT PRODUCTION APPROVED"\n' >> "$tmp/chart/Chart.yaml"
fi
helm package "$tmp/chart" --version "$VERSION" --app-version "$VERSION" --destination dist
helm push "dist/tiket-$VERSION.tgz" oci://ghcr.io/wiesmak/charts
chartDigest=$(docker buildx imagetools inspect "$chart:$VERSION" --format '{{json .Manifest.Digest}}' | jq -er .)
certArgs=()
if test -n "${TIKET_SIGNING_CERT:-}"; then
 printf '%s' "$TIKET_SIGNING_CERT" > "$tmp/cert.pem"
 openssl x509 -in "$tmp/cert.pem" -checkend 0 -noout
 openssl x509 -in "$tmp/cert.pem" -pubkey -noout | openssl pkey -pubin -outform DER > "$tmp/cert-public.der"
 openssl pkey -pubin -in "$tmp/cosign.pub" -outform DER > "$tmp/key-public.der"
 cmp "$tmp/cert-public.der" "$tmp/key-public.der"
 certArgs+=(--certificate "$tmp/cert.pem")
 if test -n "${TIKET_SIGNING_CERT_CHAIN:-}"; then
  printf '%s' "$TIKET_SIGNING_CERT_CHAIN" > "$tmp/chain.pem"
  openssl verify -purpose any -CAfile "$tmp/chain.pem" "$tmp/cert.pem"
  certArgs+=(--certificate-chain "$tmp/chain.pem")
 fi
fi
for artifact in "$image@$imageDigest" "$chart@$chartDigest"; do
 cosign sign --yes --key "$tmp/cosign.key" "${certArgs[@]}" "$artifact"
 cosign verify --key "$tmp/cosign.pub" "$artifact" | jq -c . >> "$tmp/verification.jsonl"
done
jq -s . "$tmp/verification.jsonl" > dist/oci-verification.json
jq -n --arg version "$VERSION" --arg sha "$GITHUB_SHA" --arg image "$image@$imageDigest" --arg chart "$chart@$chartDigest" --slurpfile security dist/vulnerability-summary.json '{version:$version,revision:$sha,image:$image,chart:$chart,verified:true,security:$security[0]}' > dist/provenance.json
(cd dist && sha256sum *.jar *.tgz *.json > SHA256SUMS)
cosign sign-blob --yes --key "$tmp/cosign.key" --bundle dist/SHA256SUMS.sigstore.json dist/SHA256SUMS
cosign verify-blob --key "$tmp/cosign.pub" --bundle dist/SHA256SUMS.sigstore.json dist/SHA256SUMS
