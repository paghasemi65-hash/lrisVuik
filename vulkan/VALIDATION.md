# Validation record — 2026-10-26 (Asia/Tehran)

**Historical Phase 3 record below.** The later backup/debugging pass, current artifact hash,
42-test result, portable Khronos validation runs, and new unresolved native shutdown failure
are documented in [DEBUGGING.md](DEBUGGING.md). The earlier 38-test count, artifact hash,
and absence of validation layers below describe the pre-debugging build.

The final normal client run completed successfully, with the benchmark supplied explicitly:

```text
gradlew -PwithFabric -PclientVerification build gpuTest :fabric:runVerification -PbenchmarkPack=<ComplementaryReimagined_r5.9.3.zip>
BUILD SUCCESSFUL
```

Environment: Windows, Java 25.0.2, Gradle 9.4.0, LWJGL 3.4.3, NVIDIA GeForce GTX 1660 SUPER.
This machine's Java dependency downloads needed the existing Windows trusted certificate store:
`-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE`.
Certificate verification was not disabled, and the Gradle distribution checksum is pinned.

## Observed results

| Check | Result |
|---|---|
| Core Java compilation and JAR/source JAR | Passed |
| Fabric bridge compilation against Minecraft 26.3 and Loader 0.19.5 | Passed |
| Compiler, preprocessing, cache, layout, interfaces, concurrent AST isolation and flipping tests | 13 passed |
| Render graph ordering, uninitialized reads, and cycle tests | 2 passed |
| Deferred retirement ordering, frame-slot reuse, shutdown and destructor failure tests | 2 passed |
| Pack properties/options, folder/ZIP/dimension loading, fallback, item IDs, diagnostics and texture tests | 12 passed |
| Uniform snapshots, expressions/smoothing, errors and reversed projection reconstruction | 5 passed |
| Native Vulkan GPU tests | 3 passed |
| External Complementary benchmark test | 1 passed; 30 programs in each of three dimensions |
| Original test suite retained | All previous 26 retained; 34 CPU + 3 GPU + 1 external benchmark = 38 passing tests |
| Skipped or failed tests | 0 |
| OpenGL/GLFW imports or legacy GL calls in new module source | None found |
| In-game Fabric mixin application and world rendering | Passed in the live 26.3 Vulkan client |
| In-game folder/ZIP loader, profile/override, native allocation, resize/reload/reentry/Off | All 12 Phase 2 verification stages passed |
| Native client stability | Three Phase 3 startup access violations plus two historical Phase 2 failures remain unexplained; final debugger and normal runs passed |
| Khronos Vulkan validation layer | Not installed; not exercised |
| Sodium / NeoForge / real shaderpack gameplay compatibility | Not established |

The native test creates a Vulkan 1.3 device with dynamic rendering and Synchronization2 enabled,
allocates VMA targets, compiles SPIR-V, creates native graphics/compute pipelines, and:

1. Clears and copies a D32 depth image to a snapshot.
2. Draws a fullscreen triangle with one uniform value into a color image.
3. Records another draw using a different uniform snapshot, without changing the earlier draw.
4. Samples the first color image in two successive compute dispatches into a storage image.
5. Copies color and depth to host-visible memory, waits for completion, invalidates non-coherent
   memory, and checks every pixel and copied depth value.

Expected RGBA8 values `[32, 64, 96, 128]` are checked with a one-unit quantization tolerance.
All 64 copied D32 depth values equal `1.0`. This proves the tested operations work on this GPU;
it does not establish general shaderpack visual correctness or synchronization-validation cleanliness.

The second GPU test loads a fixture through the production pack loader and creates actual pack
resources on the device. It verifies half-float, packed R11G11B10, full-float, and unsigned-integer
formats; absolute/relative extents; mip blits; temporal retention across frame clears; disabled
flip overrides; 1D, 3D, cubemap and 2D-array uploads; shadow comparison sampling and integer clear
values. A real compute dispatch samples those resources, then every output pixel is checked against
`[102, 51, 204, 255]`. The test also uses the same descriptor and retirement implementation as runtime.

The third GPU test uploads 64 distinct D32 depth samples spanning `[0,1]`, executes the same
depth-conversion pipeline used in the live compositor, reads the destination attachment back and
checks every row/sample against `1 - input` with `1e-6` tolerance. This checks orientation as well
as endpoints and intermediate values. Projection tests independently verify reconstruction with
the corresponding converted matrix/inverse; actual shaderpack SSR/shadows are not rendered yet.

## Live Minecraft verification

The separate verification mod launched Minecraft 26.3 / Fabric Loader 0.19.5 with the Vulkan
backend explicitly selected. The GPU reported NVIDIA GeForce GTX 1660 SUPER, driver 621.01.
The actual RenderPearl API uses Vulkan 1.2 plus enabled KHR dynamic-rendering/synchronization2
features; this path uses the appropriate dispatch and compiler target.

Observed results from the final run:

- Required mixins applied with no mixin warnings, missing targets, or injection failures.
- Real instance, physical/logical device, queue, command pool/buffer, SDL3 window, Vulkan surface,
  acquired swapchain, submission serials and frame slots were logged.
- 1,665 final passes recorded into Minecraft's live main color target.
- The native screenshot path captured the final image and HUD. Setting exposure to 0.25 produced
  a measured world-region brightness ratio of **0.2516687658329395**. All **74/74** bright hotbar
  border pixels remained unchanged within the configured five-unit tolerance.
- SDL window resized to 1000x700, then 1100x760 during pack verification; swapchain and live targets were replaced.
- Native resource reload completed and recreated the compositor.
- World unload retired the compositor; world reopen created a new generation and recorded
  at least 120 additional final passes before shutdown.
- Seven compositor generations in total; clean queue-idle shutdown with **zero pending retirements**.
- `fabric/run-verification/iris-phase1-result.json` contains
  `{"passed":true,"stages":10,"renderedFrames":1665,"generations":7}`.

Phase 2 created a folder fixture and a distinct ZIP fixture in the verification game's shaderpacks
directory. Their programs compile using the live MC/OS/vendor/depth macros. The test selected a
profile, overrode an option, checked the resulting compiled values and actual VkImage/VkImageView
handles, formats, dimensions, mip counts, custom uploads and sampler allocation. Resize, native
resource reload, switching packs and leaving/reopening the world produced **eight GPU resource
installations**. A deliberately broken pack produced `final.fsh:4` with transformed source and
shaderc's undeclared-identifier error, without disabling native rendering. Off released the pack.
`iris-phase2-result.json` reports `passed:true`, `stages:12`, `installations:8` and success for folder,
ZIP, profile/overrides, resize, reload, reentry, diagnostics and Off. The native Shaderpacks screen
was captured as `screenshots/phase2-shaderpacks.png` and visually inspected.

## Phase 3 verification and compatibility boundary

The final normal run (no native debugger attached) also wrote:

- `iris-phase3-uniforms.json`: **1,034 live samples**, **1,029 previous-frame history checks**,
  **10,104 object draws** and **3,443 terrain draws** correlated with actual uploaded transforms.
  It compared the camera position, viewport extent, world clock, projection inverse and previous
  frame values against Minecraft's state through resize, reload and world reentry.
- `iris-phase3-benchmark.json`: Complementary Reimagined r5.9.3 **loaded**, **30 programs**, live
  custom uniforms evaluated, and explicitly **`worldEffectsRendered:false`**. Actual resident
  targets included 11 color targets, two depth targets, two shadow depth and two shadow color
  targets, plus the pack's noise/cloud-water textures. This was the ninth installation after
  the eight Phase 2 fixture installations. Off retired it before clean shutdown.
- `screenshots/phase3-native-textures.png`: actual terrain atlas and world lightmap sampled by
  Vulkan descriptor sets, with the native crosshair/hotbar preserved; visually inspected.

The external `benchmarkTest` separately compiled all 30 default programs for each of the Overworld,
Nether and End using the production loader. Pack identity: Modrinth `Bqen1mJX`, r5.9.3;
SHA-512 `45304b1d7862afdb2177b7e6226fc9c6737911d17a4ad46cc9c519ca4deb935f05072cd337cd8b19a15bb8062b57b54eceec7bdee4cd572d94858e2946d37d85`.
The ZIP was obtained from the official project's distribution and is not bundled. This test excludes
third-party renderer entry points and does not enable unsupported optional SSBO/material features.

Live testing found and fixed: invalid initial camera matrices; sky draw state being absent when
vanilla skips sky extraction; and concurrent AST construction mixing pack and compositor roots.
The AST race has a concurrent CPU regression test. It is separate from the native startup failures.

Milestones **6–7 remain incomplete**: selected-pack world pass routing, full per-object/instanced
chunk state, material/PBR atlases, geometry/tessellation stages, and actual shadow/SSR/fog visuals
are not implemented or verified. Frame data and native texture imports do not by themselves prove
shaderpack gameplay compatibility. See README for the exact remaining integration work.

Screenshots: `fabric/run-verification/screenshots/phase1-normal.png`, `phase1-dark.png`, and
`phase1-resized-reloaded.png`. Logs: `fabric/run-verification/logs/latest.log`. These generated
files/worlds are ignored by Git. The test exercises the native screenshot implementation used
by F2; it does not simulate a physical F2 key press.

Known environment messages remain: Windows OSHI/Perflib counter lookup failures, stale Medal
implicit-layer manifest paths, offline services/chat-key warnings, and a native missing
`minecraft:end_of_frame` post-effect warning. They did not prevent verification. No system
registry, overlay installation, drivers, or validation layers were modified to hide these messages.
The unsupported-capability toast path is implemented but was not triggered on this supported GPU.
Backend/device recreation is handled by detach/attach; the client test exercises initial device
creation and shutdown, with repeated compositor/target recreation on the same logical device.

### Unresolved native stability observation

Two historical Phase 2 client runs exited with `NTSTATUS 0xC0000005`: one during initial resource loading,
the other during the Phase 1 resource-reload check. Three Phase 3 launches also exited with this
code during startup, before world entry. These occurred before selecting the benchmark pack.
No Java fatal-error report or fault-module record was available. A temporary native debugger attached
to the verification process observed a later complete successful run with native exit code 0;
the final Phase 3 debugger run observed native exit `0x0`, and the subsequent normal run also
completed all checks. This does **not** identify or fix the failures.
Driver, host-client, and integration causes have not been ruled out. Keep this as a stability issue
before release; do not describe these runs as proof of crash-free gameplay. No system registry,
driver, overlay, or security configuration was changed during the investigation.

## Artifacts

- `build/libs/iris-vulkan-engine-0.1.0-dev+mc26.3.jar`
- `build/libs/iris-vulkan-engine-0.1.0-dev+mc26.3-sources.jar`
- `fabric/build/libs/iris-vulkan-fabric-bridge-0.1.0-dev+mc26.3.jar`
- HTML reports: `build/reports/tests/test/index.html` and `build/reports/tests/gpuTest/index.html`

The packaged Fabric JAR contains the live lifecycle/compositor, shaderpack screen/loader, and nested engine/compiler
dependencies. Inspection confirmed it contains no verification classes, mod metadata, or mixins.
SHA-256: `cc796674f350dfaaa73b775c2164e89d9d56bbe591e5893fee5996b40ccdf80c`.

After the final normal client run, the verification task's report cleanup/check also included the
optional benchmark JSON to prevent stale reports. No runtime Java code changed after that run.

The Fabric artifact is a **development compositor and pack loader/resource manager**, not a working Iris shaderpack gameplay replacement.
Its metadata and README state that limitation. See README's remaining integration work for the
later shaderpack/world-pipeline phases.
