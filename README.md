# Locus

Locus is a local-first, privacy-respecting Android notes application with on-device GGUF AI inference, hybrid RAG (BM25 keyword + 512-d vector embeddings), an autonomous notes agent, and scheduled executive digests.

## Features

- **Local-First Architecture**: Notes stored as human-readable Markdown (`.md`) files accessed via Android Storage Access Framework (SAF). Zero proprietary lock-in.
- **On-Device AI Inference**: Powered by embedded `llama.cpp` JNI runtime (ARMv8.6-A/vdot/vmmla optimized) running local quantized GGUF models on CPU.
- **Privacy-First AI & RAG**: Hybrid search combines Room FTS keyword indexing with 512-d cosine vector similarity. Note embeddings and vector indices never leave your device.
- **Autonomous Notes Agent**: Multi-turn tool calling (read, search, write, edit, move, rename, trash) governed by three safety tiers, visual diffs, and prompt-injection defense.
- **AI Dashboard & Scheduling**: Executive digests, semantic topic clusters, cross-note action items, and natural-language parsed reminders computed locally on a configurable schedule.
- **Import & Export**: Seamless Google Keep Takeout import (preserving text, checklists, tags, colors, and timestamps), library zip export/import, and audit journal JSON export.

---

## Build Flavors & Instructions

Locus is built with two Gradle distribution flavors:

### 1. `oss` (Open Source Core)
Contains 100% open-source code without third-party subscription integrations. Fully compliant with permissive open-source guidelines.
```bash
# Debug build
./gradlew :app:assembleOssDebug

# Release build
./gradlew :app:assembleOssRelease
```

### 2. `full` (Extended Flavor)
Includes all `oss` capabilities plus experimental subscription adapters for cloud-assisted workflows.
```bash
# Debug build
./gradlew :app:assembleFullDebug

# Release build
./gradlew :app:assembleFullRelease
```

---

## ⚠️ Terms of Service Disclaimer (P-3)

> **Important**: Subscription adapters are unofficial, experimental, may violate the relevant provider's Terms of Service depending on jurisdiction/plan, and may stop working without notice — use at your own risk.

Subscription-adapter code is structurally isolated within the `app/src/full` source set and can be excised entirely without affecting the standalone operation of the core application.

---

## Contributing & Model Catalog

Contributions are welcome! Please follow these guidelines:

1. **Model Catalog Submissions**:
   - Proposed GGUF model additions to `catalog/models.json` must follow the pull request template at [`.github/PULL_REQUEST_TEMPLATE/catalog.md`](.github/PULL_REQUEST_TEMPLATE/catalog.md).
   - All catalog entries require verified Hugging Face repository sources, pinned commit hashes, and SHA-256 checksums.
   - Catalog PRs are subject to review by designated maintainers per [`.github/CODEOWNERS`](.github/CODEOWNERS) and must not modify code outside the `catalog/` directory.

2. **Quality Gates**:
   Before submitting code, ensure all pre-push quality checks pass:
   ```bash
   ./gradlew spotlessCheck
   ./scripts/import-hygiene.sh
   ./gradlew detekt
   ./gradlew :app:assembleOssDebug :app:assembleFullDebug
   ./gradlew test
   ```

---

## License

Locus is open-source software licensed under the [Apache License, Version 2.0](LICENSE).
