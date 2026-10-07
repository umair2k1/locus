# Locus — Decisions Log

## D-1: detekt thresholds (TooManyFunctions=20, LongMethod=80) chosen as a generous baseline per NF-1's Compose/MVVM shape; tightened only if a real violation proves them too loose.

## D-2: tags stored as a comma-joined TypeConverter column, not a join table, until a prompt needs relational tag filtering.

## D-3: scheduled backup zips the entire chosen tree root, including .locus/trash and .locus/history — N-11 names no carve-out.

## D-4: llama.cpp pinned to tag v0.4.1 / commit b29c606e28a01b1bc8c1351026a0fa6e616bf6c4, resolved via GitHub Releases API on 2026-09-18.

## D-5: EmbeddingGemma-300M produces 768-d native embeddings; EmbeddingRunner reduces this to the S-2 specified fixed 512-d output via uniform binning / mean-pooling over the extra dimensions (for $i \in [0, 511]$, averaging dimensions $i + 512 \times k < D$) followed by L2-normalization, preserving energy across all native dimensions while matching the fixed 512-d vector contract.

## D-5: Phase 1's Chat/RAG routing has no local fallback leg yet (no local chat model exists until Phase 2); P-4 is completed in Prompt 52.

## D-6: One model resident at a time in LlamaRuntime (M-1). Loading a chat model unloads any resident embedding model and vice-versa. This is a deliberate v1 simplification matching M-1's "CPU-first" resource-conscious framing on mobile devices to constrain RAM usage, not a limitation of the M-1 architecture itself.

## D-7: Model benchmark storage is update-in-place per (modelId, device) pair in ModelMetaEntity, adhering to M-3's singular measured tok/s requirement rather than maintaining an unbounded historical log.

## D-6: catalog seed checksums resolved via the HF API on 2026-09-19 — see catalog/models.json for the pinned values. (Note: Qwen/Qwen3-1.7B-GGUF provides Qwen3-1.7B-Q8_0.gguf in the official repository; pinned live SHA-256 for Q8_0).

## D-8: I-1 inline AI actions target English-only for TRANSLATE per NF-4 non-goal (ruling out non-Latin / other-language UI; "translate" normalizes foreign snippets pasted by users into English rather than providing an unbounded language picker).

## D-9: Inline AI single-shot actions (I-1) route through RouteAndSend with TaskType.CHAT_RAG_QA as the closest existing P-4 routing row, since P-4 does not define a dedicated inline-action routing row.