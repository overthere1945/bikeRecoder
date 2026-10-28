# Global constraints (V1.3.1) — copied from plan + controller rulings
- Version 1.3.1, versionCode 10301 (verPatch = 1), debug `1.3.1-dev`, screen `V1.3.1`.
- All user-facing text Korean, spec copy verbatim: "위성 지도를 받지 못해 일반 지도로 만들었습니다", "위성 지도 데이터 약 N MB가 듭니다 (Wi-Fi 권장)", attribution "© 브이월드 · OpenStreetMap".
- VWorld key: secrets.json `vworldApiKey` → BuildConfig.VWORLD_API_KEY. Never in logs/exceptions/commits; tests use fake `TESTKEY` (device tests may use the real BuildConfig key).
- VWorld URL `https://api.vworld.kr/req/wmts/1.0.0/{key}/Satellite/{z}/{y}/{x}.jpeg`, tile 256 px, zoom 6–19.
- Pixel ratio stays 1 (MapLibreOfflineController.VIDEO_PIXEL_RATIO).
- Threads (V1.3.0): MapSnapshotter create/start/cancel/projection on Main; only software bitmaps on the frame Canvas (V7).
- 1u = short side / 360 (also at 720p).
- Device rules: S22 R5CT43TFS9A only, one class per run, wake first, `</dev/null`; never the S25 R3CY903ZYQV; never change phone settings.
## Rulings
- P1 (Task 1): CameraPath.overview's single-point/tiny zoom cap = ZOOM_SLOW (17.4), replacing the removed ZOOM.
- P2 (Task 3): update SettingsScreenTest "V1.3.0" → "V1.3.1" and run it on the S22.
- C1 (Tasks 4, 6): call CameraPath.cameras(..., heightPx = actual frame height) — the default 1920 is wrong for landscape (1080) and 720p fallback.
- C2 (Task 4): rewrite androidTest FrameRendererTest.reusedFramesTakeOneSnapshot for the new reuseMap (only the outro hold reuses; overview, photo and rise frames take snapshots), and pass heightPx = H to CameraPath.cameras at FrameRendererTest:44.
- K1 (Tasks 4–6): any app log/exception text that may contain a tile URL must replace the VWorld key with "***" (helper in VideoMapStyle, e.g. VideoMapStyle.redact(text, key)).
- L1 (Task 4): in VideoMapStyle set v-road text-size 24 and add "text-pitch-alignment": "viewport" to both label layers (keep symbol-placement line on roads); update VideoMapStyleTest.
- C3 (Task 6): VideoJob must pass FrameRenderer.introCamera(inputs, w, h) as `overview` and FrameRenderer.outroCamera(inputs, w, h) as `outro` to CameraPath.cameras(..., heightPx = h), using the encoder's final w/h; update VideoJobTest:394-406 to match.
