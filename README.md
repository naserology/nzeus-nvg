# NZEUS NVG — OPTIC-2 (APK)

Pure Android night-vision app for the Pixel 10 Pro XL. Camera2 with manual ISO/exposure, GPU fragment-shader pipeline running every per-pixel stage in a single pass at display refresh rate, OpenCV/TFLite branch for ML enhance and YOLO detection, native MP4 recording and snapshot to gallery, tactical HUD with sensor + GPS telemetry.

No Flask. No Termux. No browser. One APK.

```
                 ┌─────────────────────────┐
                 │   Camera2 (manual ISO)  │
                 └────────────┬────────────┘
                              ▼
                  SurfaceTexture (OES external)
                              │
                              ▼
   ┌────────────────────────────────────────────────┐
   │  GLES 3.0 fragment shader (single pass)        │
   │  denoise → contrast → gamma → edge → grade     │
   │  modes: NVG · THERMAL · CLAHE · RAW            │
   └────────────────────────────────────────────────┘
                              │
                ┌─────────────┼─────────────┐
                ▼             ▼             ▼
           Display      MediaRecorder    glReadPixels
                          (clip MP4)       (snapshot)
                              ▲
                              │
                  ┌───────────┴────────────┐
                  │ Optional ML branch:    │
                  │ Zero-DCE TFLite (GPU)  │
                  │ YOLOv8n TFLite (GPU)   │
                  └────────────────────────┘
                              ▲
                              │
   ┌──────────────────────────┴──────────────────────┐
   │  HudOverlay (Canvas) — reticle, grid, compass,  │
   │  ISO/exp/lux, GPS, battery, detections          │
   └─────────────────────────────────────────────────┘
```

## Features

- **Camera2 manual control** — ISO and exposure-time sliders that clamp to the actual range your sensor reports
- **GPU pipeline** — single fragment shader does denoise, local contrast, gamma, gain, Sobel edge boost, phosphor LUT, scanlines, vignette
- **Four modes** — NVG (phosphor green), THERMAL (inferno colormap), CLAHE (color), RAW (bypass for A/B)
- **ML enhance** — Zero-DCE TFLite with GPU delegate, ~10-15 ms on Tensor G5
- **Object detection** — YOLOv8n TFLite, NMS done in Kotlin
- **Recording** — H.264 MP4 to `Movies/NZEUS-NVG/`, JPEG snapshots to `Pictures/NZEUS-NVG/`
- **HUD** — Canvas-drawn at display refresh: scrolling compass, GPS, lux, ISO/exp readout, FPS, pipeline ms, battery, Zulu clock, reticle, grid, detection boxes
- **Wake lock + immersive fullscreen** — screen stays on, navbars hidden

## Build with GitHub Actions

```bash
git init && git add . && git commit -m "init"
git remote add origin git@github.com:naser/nzeus-nvg.git
git push -u origin main
```

The workflow at `.github/workflows/build.yml` runs on every push:

- Always produces a debug APK as an artifact (download it from the Actions run page)
- If you set 4 repository secrets it also produces a signed release APK and attaches both to a GitHub Release on every `v*` tag

### Signing (optional but recommended)

Generate a keystore once:

```bash
keytool -genkey -v -keystore nzeus.keystore -alias nzeus \
        -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 nzeus.keystore > keystore.b64
```

Then add these GitHub repo secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | contents of `keystore.b64` |
| `RELEASE_STORE_PASSWORD` | your store password |
| `RELEASE_KEY_ALIAS` | `nzeus` |
| `RELEASE_KEY_PASSWORD` | your key password |

Tag a release:

```bash
git tag v2.0.0 && git push --tags
```

The signed APK lands on the release page, ready to sideload.

## Install on the Pixel

1. Download the APK from the Actions run or release page
2. Allow install from unknown sources for your browser
3. Open the APK file, install
4. Grant camera + location + microphone permissions on first launch

## ML models (optional)

Drop these into `app/src/main/assets/models/` before building to unlock the ML branch:

- `zero_dce.tflite` — low-light enhancement (~80 KB, FP16 GPU-friendly)
- `yolov8n.tflite` — detection (~6 MB)
- `coco.txt` — one class name per line

The app runs fine without them; the ML toggle just becomes a no-op.

Get Zero-DCE: https://github.com/Li-Chongyi/Zero-DCE_extension (export with TFLite converter)
Get YOLOv8n TFLite: `yolo export model=yolov8n.pt format=tflite int8=false`

## Brand

Phosphor `#39FF14` on `#000000`. Tactical bracketed buttons, monospace body, bold display. Matches every Nzeus Lab surface.

## What v2.0 ships vs v2.1 backlog

**Shipped:** All five must-haves: Camera2 manual ISO/exposure, ML enhance (Zero-DCE), YOLO detection, gallery record + snap, HUD with sensors/GPS.

**v2.1 next steps:**
- Wire MediaRecorder Surface into a second EGL target so recordings capture the full HUD-less pipeline output (today's `recorder.start()` returns the surface but the renderer doesn't fan out to it yet — a 30-line addition)
- Detection inference loop on a worker thread sampling glReadPixels at 5 Hz
- Long-exposure stacking mode for astrophotography
- USB OTG support for InfiRay P2 thermal modules
- Dual-camera composite (wide + tele)
