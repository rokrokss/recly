#!/usr/bin/env bash
# docs/recly.md §15 "On-device speaker diarization": the Core ML models the iPhone and Mac apps ship
# for FluidAudio's offline diarizer — pyannote speaker-diarization-community-1 as converted by Fluid
# Inference (CC-BY-4.0, THIRD-PARTY-NOTICES.md). Fetched at build time into the RecKitSpeakers target,
# where Package.swift copies them into the app; the apps never download them. Not checked in, like the
# XCFramework `build-core.sh` stages: ~21 MB of weights stay out of the repository's history.
#
# Pinned to one Hugging Face commit, every file by size and SHA-256. A file already there with the
# right hash is kept, so a second run costs nothing and needs no network.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dest="$repo_root/apple/RecKit/Sources/RecKitSpeakers/Models/speaker-diarization"
repo="FluidInference/speaker-diarization-coreml"
# FluidAudio v0.17.5's pinned revision for this repository (ModelNames.swift `Repo.diarizer`).
revision="df2625ac79a7ac6b65ad868fee6d80f320da4232"

# sha256 · bytes · path
files="
c3189a64946c75bc24fcb98afe89ad78c52bdbadfdf65e857fb1b81e2cc9fbb2 5959360 Segmentation.mlmodelc/weights/weight.bin
d37e4ce30b406a6b34f765f769b9baed3178cc0c2b2e299c641daa43a052dd3f 43063 Segmentation.mlmodelc/model.mil
88dbf0b07208fe142e1729c2b4c974ad3599fcb2ae5d5f18fce782b225384124 3410 Segmentation.mlmodelc/metadata.json
ea51481b8bd3e496ad3cf16f066ddaa37f20e8772eaac76b3393c28de20e06bc 812 Segmentation.mlmodelc/coremldata.bin
64265f8e7ad41a5f68d630c15288c2499cca5892ad49e20096819cdeac004cdb 243 Segmentation.mlmodelc/analytics/coremldata.bin
9e83fdd3ea78064b078069e4d9141603c61c47a27fd19e7e3142ff7476f8db36 1776896 FBank.mlmodelc/weights/weight.bin
27aaeb21569e81bdbe2eef87789f50a37cfea800039bd134448a9417de2f30ed 15667 FBank.mlmodelc/model.mil
2623785f5d186893b82d01e84aa33a7704ef763c3309e02055f22dc9d871ce9a 3409 FBank.mlmodelc/metadata.json
57ac436bb0671cbb5527a339134d695f752eb77f7a18966b93c6835335595759 853 FBank.mlmodelc/coremldata.bin
0e8bd3a8b82ac123580989f490e4d9245127c535857630b543311268accc3f0a 243 FBank.mlmodelc/analytics/coremldata.bin
99356b2985b8d43880a657024d941d450b38820451ccff903f76ed4e52d1868b 13412288 Embedding.mlmodelc/weights/weight.bin
22fa958aef72a561c21f874a07cbdcd30fdf40ee961c0bc2fb67c119273b46d3 78432 Embedding.mlmodelc/model.mil
1854371eb6b438fb8aeac96afb45c999af7902581c06afdfcd7ff3cb1ce66be5 2818 Embedding.mlmodelc/metadata.json
4a705bac27d151d9642f37609296042a15602a42253039e0921dc9e75da7e004 704 Embedding.mlmodelc/coremldata.bin
8d6706436639b53830b4dbe8aaf9c9a843f7f582d63e16f3cb8bb7c6ccd58682 243 Embedding.mlmodelc/analytics/coremldata.bin
80f7d229202636d372428c90596f11a91545f07da77259f07153aaf225914a36 200192 PldaRho.mlmodelc/weights/weight.bin
83aee2e5310d19b5f202aea97d07a0e12102556d1b32ef3ed08b36f7f9725041 7613 PldaRho.mlmodelc/model.mil
b314cf25a93e46b4076883a6f5a2f8848b73c3851bd9d36074d067f35a1c7945 2749 PldaRho.mlmodelc/metadata.json
4d9741477f721c79b09fcdfe455110c4b7d4272e2de3496bf1729d966d3ee418 763 PldaRho.mlmodelc/coremldata.bin
8940ea6044dbcbefa22da8cc41e0b485e1fb5ed89aecaf37c6e0c483a97ddcd7 243 PldaRho.mlmodelc/analytics/coremldata.bin
38ee28d4269c076cef254ee760bbd811f0738a92e0f01f9699ad372828c5de8f 89416 plda-parameters.json
0964a66893fa5c3a574758257d30d2944669f44eeed5784b30ec3bab902012b3 1925 NOTICE.md
"

fetched=0
while read -r sha size path; do
  [[ -n "$path" ]] || continue
  target="$dest/$path"
  if [[ -f "$target" ]] && [[ "$(shasum -a 256 "$target" | cut -d' ' -f1)" == "$sha" ]]; then
    continue
  fi
  mkdir -p "$(dirname "$target")"
  curl -fsSL --retry 3 -o "$target.part" "https://huggingface.co/$repo/resolve/$revision/$path"
  actual="$(shasum -a 256 "$target.part" | cut -d' ' -f1)"
  if [[ "$actual" != "$sha" || "$(stat -f %z "$target.part")" != "$size" ]]; then
    rm -f "$target.part"
    echo "fetch-speaker-models: $path does not match its pinned hash" >&2
    exit 1
  fi
  mv "$target.part" "$target"
  fetched=$((fetched + 1))
done <<< "$files"

echo "fetch-speaker-models: $dest ($fetched fetched)"
