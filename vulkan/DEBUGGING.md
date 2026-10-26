# Debugging before the next phase — 2026-10-26

The source and existing mod were backed up **before any debugging changes**. This pass fixes
confirmed input-validation bugs and adds native validation evidence. It does not finish Phase 3
or establish that the intermittent native crash is resolved.

## Verified backup

Location, relative to the directory containing the repository:
`Iris-Vulkan-backups/before-debug-20261026-110749/`.

- `source.zip`: 998 files, including untracked Vulkan sources, tests, build configuration,
  wrappers, documentation, and upstream source. Every archived entry was read back and checked
  against its SHA-256 in `manifest.json`.
- Separate copies of the engine JAR, engine sources JAR, and Fabric bridge JAR were verified
  against the originals. Original Fabric SHA-256:
  `cc796674f350dfaaa73b775c2164e89d9d56bbe591e5893fee5996b40ccdf80c`.
- Excluded: `.git`, `.gradle`, generated build/run directories, downloaded reference/game files,
  node_modules, logs, heap dumps, and crash dumps. This is a source/artifact backup, not a world save
  or Git-history backup. Restore the archive into an empty directory to inspect the original code.

## Confirmed fixes

| Code | Problem and correction |
|---|---|
| `render/VulkanRenderTargets` and `pack/TextureFormat` | An undersized upload recorded a buffer-to-image copy with too few staging bytes. Uploads now validate the exact uncompressed byte count, including depth, layers, and packed formats, using overflow-checked arithmetic before allocating or recording commands. Unknown upload formats fail explicitly. |
| `render/VulkanRenderTargets` | Attachment/transfer transitions accepted images lacking the required usage or aspect. These are rejected before barriers are recorded. Non-array views now require one layer; a cube view requires exactly six. Cube arrays remain supported. |
| `uniform/HalfLifeSmoother` | NaN/infinite frame times or half-lives passed the old sign-only checks and could poison subsequent frames. All timing values must now be finite and nonnegative before smoothing state changes. |

Tests: updated `VulkanGpuTest` and `UniformTest`; added `ImageValidationTest`. The focused upload
and smoothing tests each failed against the prior implementation before their respective fixes.
The GPU regression also verifies that rejected operations leave layout/initialization unchanged.
The existing valid 1D/3D/cubemap/array uploads and real depth readback still pass.

Audit findings that needed no code change: the shader reader already rejects geometry/tessellation
files, and Minecraft's whole-buffer `FrontendRenderPass.setUniform` delegates to the slice overload
already intercepted by the bridge. Neither was patched speculatively.

## Build and validation evidence

The complete baseline passed before edits. After fixes:

- **37 unit tests + 4 GPU tests + 1 external benchmark = 42 passing**, no failures or skips.
- Complementary Reimagined r5.9.3: 30 default programs compiled in each of the three dimensions.
- Fabric and engine artifacts built successfully. The Fabric JAR contains no verification classes,
  verification metadata, or verification mixins.
- Rebuilt Fabric SHA-256:
  `36eb11ebc9c592c65730ad832f7cfde5b3383caef09ab07bd55aa92e3729d653`.

Commands run from `vulkan/`, with Java 25 and the Windows trust store configured for this machine:

```powershell
.\gradlew.bat -PwithFabric build gpuTest benchmarkTest -PbenchmarkPack="C:/packs/ComplementaryReimagined_r5.9.3.zip" --no-daemon
.\gradlew.bat -PwithFabric -PclientVerification :fabric:runVerification -PbenchmarkPack="C:/packs/ComplementaryReimagined_r5.9.3.zip" --no-daemon
```

Replace the example path with the actual absolute benchmark path. Baseline/build logs: `debug-baseline.log`,
`debug-fixed-build.log`. Focused failing regressions: `debug-regression-before.log`,
`debug-smoothing-before.log`. These generated logs are ignored by Git.

### Portable Vulkan validation

Downloaded the official [Khronos Windows validation archive](https://github.com/KhronosGroup/Vulkan-ValidationLayers/releases/tag/nightly)
into ignored `reference/validation-layers/`; no driver, registry, or system installation changed.
Archive SHA-256: `0693dca1739ee70fbd8a1aca1c59dbef4eb2de95785bc49db4fcd062e50199d4`.
Manifest API version: 1.4.366. The nightly URL is mutable; the hash identifies the tested archive.

Process-local `VK_LAYER_PATH`, `VK_LAYER_SETTINGS_PATH`, and
`VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation` enabled the layer. Its settings enabled
`khronos_validation.validate_sync = true` and error/warning logging. The four GPU tests reported
`Khronos validation: true` and passed. The full live harness passed with the layer loaded, with
no `VUID` or `SYNC` errors in `debug-validation-client.log`. The loader log confirms both instance
and device validation layers were inserted. `debug-validation-gpu.log` records the GPU task.

The live harness exercised the real world, main color/depth targets, HUD/screenshots, atlas/lightmap,
window resizing, resource reloads, world reentry, pack changes, Complementary compilation and live
uniform evaluation, then clean shutdown. This remains built-in compositor output, not selected-pack
world effects. Validation covers those executed paths on the GTX 1660 SUPER only.

## Native failure remains open

Two baseline live runs completed (one traced, one normal). The first rebuilt normal run completed
all in-game checks and logged zero pending Iris retirements, then exited during shutdown with
**NTSTATUS 0xC0000374 (heap corruption)**: `debug-client-fixed.log`. This is a failed client run,
even though result JSONs had already been written. Gradle correctly rejected the process exit.

A subsequent traced run exited 0 (`debug-client-stack.log`), and the validation-enabled run also
exited 0. Windows Application logs supplied no fault-module record. The native tracer observed no
fatal exception during that successful traced run. These successful runs do not explain the failure
or resolve the older startup access violations (`0xC0000005`).

An additional run traced first-chance access faults in native DLLs as well as fatal heap exceptions:
`debug-client-faults.log`. It also exited 0 without a reported native fault. Its final reports recorded
1,649 compositor frames, seven generations, zero pending retirements, 1,027 live uniform samples,
1,022 history comparisons, 9,938 object draws and 5,207 terrain draws. The benchmark report explicitly
retains `worldEffectsRendered:false`.

Layer diagnostics confirmed injected Overwolf overlay/capture and WEOBS capture DLLs, in addition
to NVIDIA driver layers. Their presence alone is not proof of causation. No overlay was uninstalled
or globally disabled. Driver, native client/library, and integration causes remain unexcluded.

Do not describe this artifact as crash-free or ready for release, and do not mark milestones 6–7
complete on the strength of compilation or compositor screenshots.
