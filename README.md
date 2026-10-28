# Pro Native Camera

> A high-performance, manual Android camera engine built directly on the low-level **Android Camera2 API** and **Jetpack Compose**. Designed for filmmakers, mobile photographers, and camera enthusiasts who demand uncompromised image fidelity.

---

## Overview

Most modern Android smartphone camera apps rely on heavy computational post-processing pipelines that force aggressive noise reduction, artificial edge sharpening, and plastic skin smoothing. 

**Pro Native Camera** is built from the ground up to bypass intrusive OEM image processing. By tapping directly into raw sensor streams and hardware control blocks via the **Camera2 API**, it provides an organic, cinema-grade camera pipeline delivering clean, unadorned video and authentic computational RAW-like stills.

---

## Core Features

### Pro Video Engine
* **High-Bitrate HEVC (H.265) Encoding:** Record pristine cinema video with customizable bitrates (up to 100 Mbps) and support for AVC (H.264) and AV1.
* **10-Bit HLG HDR Video:** Dynamic range expansion using Android 13+ `DynamicRangeProfiles.HLG10` with automatic hardware capability probing and seamless 8-bit SDR fallback.
* **Conflict-Free Hardware EIS:** Intelligently isolates Electronic Image Stabilization (`CONTROL_VIDEO_STABILIZATION_MODE_ON` / `PREVIEW_STABILIZATION`) by disengaging competing Optical Image Stabilization (OIS) actuators, eliminating jitter and gimbal conflicts.
* **Cinema Cadence & Framerates:** Native support for 24p cinema cadence, 30p, and 60p capture across 4K UHD, 1440p QHD, and 1080p FHD resolutions.

### Computational Photography (Deep Fusion HDR)
* **Universal 3-Frame HDR Burst:** Applies precision bracketed capture (`-2 EV`, `0 EV`, `+2 EV`) across **both back and front cameras**, completely eliminating blown-out skies in selfies and high-contrast scenes.
* **Sub-Sampled Micro-Motion Alignment:** Fast block-matching alignment between bracketed frames eliminates handshake ghosting and edge fringing.
* **Mathematical Luminance Fusion:** Pure algorithmic dynamic range expansion that recovers clipped highlights and lifts facial shadows without synthetic AI hallucination.
* **Face Priority Metering:** Integrates hardware face detection (`STATISTICS_FACE_DETECT_MODE`) to calculate dynamic `MeteringRectangle` weights (`1000` weight priority), guaranteeing optimal subject exposure in backlit environments.
* **Maximum Quality Storage:** Compresses blended computational buffers at **100% JPEG quality** directly into the system `MediaStore` (`DCIM/MyNativeCamera`).

### Full Hardware Manual Controls
* **Manual ISO & Shutter Speed:** Granular exposure controls with nearest-stop interpolation and seamless auto-exposure matching.
* **Manual Focus (Diopters):** Direct physical lens displacement control in diopters (`dpt`) alongside tap-to-focus and continuous tracking.
* **Planckian Kelvin White Balance:** True color temperature calibration mapping Kelvin values to hardware `RggbChannelVector` gains and 3x3 `ColorSpaceTransform` matrices (`COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`).
* **Exposure Compensation (EV):** Real-time EV stepping (`-2.0 EV` to `+2.0 EV`) with automatic step size detection.

### Distraction-Free Cinematic UI
* **Clean Dark Aesthetic:** Designed strictly in **Deep Space Black (`#000000`)** and **Slate Navy Blue (`#0A2342` / `#1D4ED8`)** to maximize preview visibility and prevent screen glare during production.
* **Modern Bottom Control Deck:** Centered shutter/record button with haptic feedback, one-tap camera flip (`Icons.Default.FlipCameraAndroid`), instant photo/video mode toggle, and live gallery thumbnail viewer.
* **Scrollable Settings Modals:** Vertical-scrolling bottom sheets for resolution, cadence, audio sources, and color profiles.

---

## Tech Stack

| Technology | Purpose |
| :--- | :--- |
| **Kotlin** | Modern, concise, type-safe programming language |
| **Jetpack Compose** | Declarative modern UI architecture |
| **Android Camera2 API** | Direct low-level HAL and sensor control |
| **Kotlin Coroutines & Flow** | Asynchronous execution and reactive state management |
| **MediaRecorder & MediaCodec** | Low-latency hardware-accelerated video encoding |
| **Android MediaStore API** | Scoped-storage compliant media publishing |

---

## Installation & Usage (For Android Phones)

> [!IMPORTANT]
> **Physical Device Required:** Android Studio emulators cannot simulate hardware camera sensors, RAW Bayer streams, YUV burst image readers, or 10-bit HDR video encoders. A physical Android smartphone is strictly required to run this application.

### Step-by-Step Setup

1. **Enable Developer Options & USB Debugging on your phone:**
   * Go to **Settings > About Phone**.
   * Tap **Build Number** 7 times until you see the message `"You are now a developer!"`.
   * Go to **Settings > System > Developer Options** (or search for **Developer Options**).
   * Enable **USB Debugging** (and optionally **Wireless Debugging**).

2. **Clone the Repository:**
   ```bash
   git clone https://github.com/<your-username>/pro-native-camera.git
   cd pro-native-camera
   ```

3. **Open the Project in Android Studio:**
   * Launch **Android Studio** (Ladybug or newer recommended).
   * Select **Open** and choose the `pro-native-camera` directory.
   * Allow Gradle to sync dependencies.

4. **Connect Your Phone:**
   * Connect your phone to your computer using a USB cable.
   * If prompted on your phone screen, select **"Always allow from this computer"** and tap **Allow**.
   * Verify your device appears in the device selector dropdown in the top toolbar of Android Studio.

5. **Build & Run:**
   * Select the `app` run configuration.
   * Click the green **Run** button (or press `Shift + F10`).
   * Android Studio will compile, assemble, and deploy the debug APK directly to your phone.
   * Grant **Camera**, **Microphone**, and **Storage/Photos** permissions when prompted on first launch.

---

## Architecture Highlights

```
com.mynativecamera.app
├── audio/            # Audio capture engine, noise suppression, and real-time metering
├── camera/           # Low-level Camera2 engine, capability probing, and bracket fusion
│   ├── CameraEngine.kt               # Central Camera2 capture session & request manager
│   ├── CameraCapabilities.kt         # Hardware sensor characteristics & profile query
│   ├── ComputationalImageProcessor.kt # 3-frame YUV exposure fusion & alignment algorithms
│   └── VideoConfig.kt                # Video resolution, codec (HEVC/AVC), and bitrate selector
├── recording/        # MediaStore persistence (video chunks & 100% JPEG photos)
└── ui/               # Jetpack Compose UI components & state holders
    ├── CameraScreen.kt               # Main camera layout & layer orchestration
    ├── CameraViewModel.kt            # Unidirectional UI state management
    ├── components/                   # Viewfinder, HUD, Controls, and Pro Modals
    └── theme/                        # Navy Blue & Deep Black cinematic theme tokens
```

---

## License

This project is licensed under the [MIT License](LICENSE).
