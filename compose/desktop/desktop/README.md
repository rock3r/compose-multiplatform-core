# Compose for Desktop.

## Basic information

Desktop port samples and support files. macOS, Windows and Linux JVM platforms are currently
supported. See https://github.com/jetbrains/compose-multiplatform for information, documentation and
tutorials.

## Building

Desktop port requires build in Kotlin Multiplatform mode, so when building please specify
`-Pandroidx.compose.multiplatformEnabled=true` flag.


## Running an example

To run an example:

```shell
./gradlew :compose:desktop:desktop:desktop-samples:run -Pandroidx.compose.multiplatformEnabled=true
```

## JBR Skia Interop PoC

The experimental JBR Skia interop switch is available only for the Swing-backed
ComposePanel path:

```shell
./gradlew :compose:desktop:desktop:desktop-samples:runSwingJbrSkiaInterop
```

This task enables both `compose.swing.render.on.graphics=true` and
`compose.swing.render.on.jbr.skia=true`. If a compatible Skiko/JBR interop
runtime is unavailable, Compose keeps using the existing SwingGraphics renderer
and emits a structured fallback marker:

```text
SKIKO_JBR_INTEROP_FALLBACK reason=...
```

For a repeatable old/new smoke report:

```shell
compose/desktop/desktop/samples/scripts/jbr-skia-interop-report.sh
```
