#!/usr/bin/env bash
set -euo pipefail
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$HOME/.local/bin"
export PATH="$HOME/.local/bin:$PATH"
echo "$HOME/.local/bin" >> "$GITHUB_PATH"
curl -fsSLo "$tmp/kind" https://kind.sigs.k8s.io/dl/v0.33.0/kind-linux-amd64
curl -fsSLo "$tmp/kind.sum" https://kind.sigs.k8s.io/dl/v0.33.0/kind-linux-amd64.sha256sum
kind_sha=$(awk '{print $1}' "$tmp/kind.sum")
[[ "$kind_sha" =~ ^[a-f0-9]{64}$ ]]
echo "$kind_sha  $tmp/kind" | sha256sum --check
install "$tmp/kind" "$HOME/.local/bin/kind"
curl -fsSLo "$tmp/helm.tar.gz" https://get.helm.sh/helm-v4.1.3-linux-amd64.tar.gz
curl -fsSLo "$tmp/helm.sum" https://get.helm.sh/helm-v4.1.3-linux-amd64.tar.gz.sha256sum
helm_sha=$(awk '{print $1}' "$tmp/helm.sum")
[[ "$helm_sha" =~ ^[a-f0-9]{64}$ ]]
echo "$helm_sha  $tmp/helm.tar.gz" | sha256sum --check
tar -xzf "$tmp/helm.tar.gz" -C "$tmp"
install "$tmp/linux-amd64/helm" "$HOME/.local/bin/helm"
curl -fsSLo "$tmp/kubectl" https://dl.k8s.io/release/v1.35.0/bin/linux/amd64/kubectl
curl -fsSLo "$tmp/kubectl.sum" https://dl.k8s.io/release/v1.35.0/bin/linux/amd64/kubectl.sha256
kubectl_sha=$(awk '{print $1}' "$tmp/kubectl.sum")
[[ "$kubectl_sha" =~ ^[a-f0-9]{64}$ ]]
echo "$kubectl_sha  $tmp/kubectl" | sha256sum --check
install "$tmp/kubectl" "$HOME/.local/bin/kubectl"
