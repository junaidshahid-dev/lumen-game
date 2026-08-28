# Lumen

A merge puzzle rendered in real 3D. Rounded glass tiles slide across a lit slab,
merges pop and throw sparks, and the whole board drifts gently with the tilt of
the phone.

Built as a native Android app in Kotlin with a hand-written OpenGL ES 3.0
renderer — no game engine, no WebView, no third-party graphics library.

## What's in it

| | |
|---|---|
| Renderer | OpenGL ES 3.0, three shader programs, meshes generated at runtime |
| UI | Canvas-drawn glass overlay layered on the GL surface |
| Modes | **Classic** ends when the board jams; **Zen** dissolves the weakest tile instead, so it never ends |
| Assists | One-step undo, best-score tracking, board restored after you leave |
| Feel | Tilt parallax, per-merge haptics, animated aurora background |
| Permissions | `VIBRATE` only — no internet, no analytics, no accounts |

## Layout

```
app/src/main/java/com/junaidshahid/lumen/
  Board.kt           pure merge rules, no Android dependencies
  Game.kt            modes, undo, persistence, animation timeline
  SceneRenderer.kt   the GL scene
  Mesh.kt            rounded-box generator (swept rounded-rect profile)
  Shaders.kt         GLSL ES 3.00 sources
  Particles.kt       fixed-capacity spark pool
  OverlayView.kt     Canvas UI
  GameSurfaceView.kt swipe + accelerometer input
  MainActivity.kt    wiring
app/src/test/        rules-layer unit tests
```

`Board.move` is deliberately free of randomness — spawning is a separate call —
so every rule is covered by exact unit tests rather than seeded ones.

## Building

There is no committed Gradle wrapper. CI provisions Gradle itself; locally you
need JDK 17, the Android SDK, and Gradle 8.10+:

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

Pushing to `main` runs the tests and publishes a debug APK as a workflow
artifact. A signed Play bundle is built only once these repository secrets
exist: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
