# LFM2.5-350M local arm

BrainCode uses the LiquidAI `LFM2.5-350M-Q4_K_M.gguf` model only as an optional on-device conversational advisor.

- Runtime: `dev.ffmpegkit-maintained:llama-android:0.1.1` (MIT; llama.cpp based).
- Model: Liquid AI LFM2.5-350M GGUF Q4_K_M.
- Model artifact is downloaded outside the APK and verified by SHA-256 before loading.
- Pinned SHA-256: `7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4`.
- The model is never used to generate user-facing prose or authorize execution. Its trusted outputs are limited to structured advisory data and literal entity spans.

The model is distributed under the LFM Open License v1.0. The license permits commercial use subject to its annual-revenue threshold; entities at or above USD 10 million annual revenue need a separate commercial license from Liquid AI. Redistribution must retain the license and attribution notices.

Source license and model artifacts:
- https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF
- https://www.liquid.ai/lfm-license
