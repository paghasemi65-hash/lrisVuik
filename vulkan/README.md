# Iris Vulkan engine development module

**Status: development engine, not a completed shader mod. Phase 6 adds dynamic uniform rings, shared descriptor layouts, persistent driver pipeline caches, more GLSL translations, and a real NeoForge build/client adapter. Selected-pack world routing, shadow-caster draws, the full live frame sequence, advanced shader features and Sodium shader routing remain incomplete. The project is not 100% complete across the 17 milestones. See [PHASE6.md](PHASE6.md) for verification, compatibility fixes and remaining work.**

This module lives inside a checkout of [Iris](https://github.com/IrisShaders/Iris), based on commit
`bff1e69cb6c5519d8745784aa9c8b92984de67e7`. The existing `common`, `fabric`, and `neoforge` projects
remain the upstream OpenGL implementation for 26.1.2. They are not called by this Vulkan module.
The separate module makes its compiler and GPU operations testable without falsely relabelling
the old renderer as a Vulkan rewrite.

The pre-next-phase debugging pass is recorded in [DEBUGGING.md](DEBUGGING.md). Source and built
JARs were backed up and hash-verified before edits. Texture uploads now reject incorrect payload
sizes, incompatible image uses, and invalid view layer counts; smoothing rejects non-finite times
without corrupting its history. That debugging pass had **42 passing tests**. Portable Khronos core and
synchronization validation also passed the GPU tests and full client harness. Native stability
remains unresolved: a shutdown heap-corruption exit occurred despite completed in-game checks.

[Crash Assistant is installed in the local development clients](CRASH_ASSISTANT.md). It caught
a vertex-verification assertion failure; a repeat passed. Failure reports now retain individual
verification counters. The earlier native crash remains unresolved.

Phase 5's final suite has **55 passing tests**. Its expanded live control/lifecycle run passed
with Vulkan validation enabled, including native OIT, all three dimensions and fullscreen.
This validates the implemented paths, not selected-pack world/shadow rendering; the separate
native startup failure is retained in [PHASE5.md](PHASE5.md).

The development Fabric artifact attaches to the actual 26.3 Vulkan backend. It loads and compiles
selected packs and allocates their real GPU resources. It **does not yet replace world rendering,
execute selected pack passes in the world, or advertise Sodium compatibility**. K toggles the existing
pack loader/resources and R recompiles them; these controls do not imply working shaderpack world effects.
Do not install it alongside upstream Iris; its development metadata explicitly prevents that combination.

Phase 6's final dual-loader build and packaging pass has **61 passing tests**: 54 unit tests,
six validation-layer GPU tests, and one Complementary Reimagined benchmark compiling 30 programs
in each of three dimensions. Installable **development** JARs and SHA-256 checksums are generated
in `build/releases/`. See [PHASE6.md](PHASE6.md) for loader-specific installation and remaining requirements.

## Build and verify

Java 25 is required. Phase 4's successful live vertex readback used portable Temurin **25.0.4.1+1**;
Oracle 25.0.2 still reproduced a native reload crash. Phase 5 also observed a native startup failure
on Temurin before a subsequent successful run. See [PHASE5.md](PHASE5.md) for evidence and limits.
Run from this directory with `JAVA_HOME` pointing to a Java 25 JDK:

```powershell
.\gradlew.bat build
.\gradlew.bat gpuTest
.\gradlew.bat -PwithFabric :fabric:build
.\gradlew.bat -PwithNeoForge :neoforge:build
.\gradlew.bat -PwithFabric -PwithNeoForge packageDevelopmentRelease
.\gradlew.bat -PwithFabric :fabric:runClient
.\gradlew.bat -PwithNeoForge :neoforge:runClient
.\gradlew.bat -PwithFabric -PclientVerification :fabric:runVerification
.\gradlew.bat -PwithNeoForge -PclientVerification :neoforge:runVerification
.\gradlew.bat compilePack -Ppack="C:/path/to/shaderpack.zip" -PgameDir="C:/path/to/.minecraft"
```

Use `./gradlew` on Linux/macOS. The wrapper pins Gradle 9.4.0 and its official SHA-256.
The engine uses LWJGL **3.4.3**, matching the official Minecraft 26.3 launcher manifest, plus
`glsl-transformer:3.0.0-pre3` and JCPP. Minecraft provides the LWJGL natives to the Fabric artifact;
the standalone test runtime resolves the matching platform natives. No GLFW or OpenGL dependency
is declared in this module.

`gpuTest` creates a headless Vulkan 1.3 device, records graphics and compute work, and reads pixels
back. It enables Khronos validation when installed, but does not install drivers or SDKs.
The library requires dynamic rendering and Synchronization2 to be **enabled on the logical device**;
availability in the physical device is insufficient. RenderPearl 26.3 includes those features in
its required feature set. The actual host uses Vulkan 1.2 plus `VK_KHR_dynamic_rendering` and
`VK_KHR_synchronization2`; the runtime dispatches the KHR entry points and compiles Vulkan 1.2 /
SPIR-V 1.5 for that device. Core Vulkan 1.3 / SPIR-V 1.6 remains supported by the standalone engine.
The cache key distinguishes both targets. Physical features are queried through
`VkPhysicalDeviceFeatures2` and checked against RenderPearl's enabled feature set.

`runClient` explicitly selects Vulkan. `runVerification` starts a separate development mod in
`fabric/run-verification`, creates a disposable test world, checks final output with native
screenshots, resizes the SDL window, reloads resources, leaves/reopens the world, and shuts down.
Its result is `fabric/run-verification/iris-phase1-result.json`; screenshots and logs are beside it.
Phase 2 verification also writes `fabric/run-verification/iris-phase2-result.json`, selects folder
and ZIP fixture packs, changes options, checks native resource recreation, and tests diagnostics
and Off. The verification classes, fixtures, and mixins are excluded from the distributed Fabric JAR.

## Phase 1 milestones 1–3

1. **Live bridge verified.** Required mixins apply to the official 26.3 client without mixin
   warnings or injection failures. Captured handles include instance, physical/logical device,
   graphics queue, rotating command pool, active command buffer, SDL3 window, Vulkan surface,
   swapchain format/extent/image, and submission-derived frame slot. The client owns submission,
   presentation, the allocator, and native device/window destruction.
2. **Lifecycle implemented.** `IrisVulkanLifecycle` creates the compositor on the render thread,
   consumes reset requests from world load/unload, resize/swapchain replacement and the existing
   `ResourceManagerReloadListener`, and retires resources before renderer/device shutdown.
   `DeferredGpuRetirementQueue` keys destruction by the actual GPU submission serial, preventing
   frame-slot reuse from destroying an in-flight object. Completion comes from RenderPearl's
   timeline-backed counter. Shutdown drains only after the native graphics queue's `waitIdle`.
   Missing capabilities disable the Iris pass, log details, and display an in-game error toast.
3. **Live screen output verified.** `LiveMainTargetCompositor` borrows Minecraft's main color/depth
   views each frame after world post-effects and before `FogRenderer.endFrame`, depth clearing,
   and GUI rendering. It copies the live color to an owned sampling snapshot, samples live depth,
   and draws the final fullscreen pass directly into the main color attachment. Both borrowed
   targets return to `GENERAL` with Synchronization2 memory dependencies before native rendering
   resumes. No host image/view is destroyed. The default final pass preserves color; diagnostic
   exposure and raw reversed-Z depth display are available through the lifecycle controller.

The final pass remains a built-in compositor; the selected pack does not replace it. Native world rendering and
OIT remain active. No standalone test device or synthetic test texture supplies runtime input.
The owned snapshot is necessary to avoid sampling the image being written by the final pass.

### Exact Phase 1 classes and build files

| Area | Added or modified classes/files |
|---|---|
| Core bridge and native calls | `VulkanContextBridge`, `VulkanDispatch`, `DeferredGpuRetirementQueue` |
| Compiler and images | `SpirvCompiler`, `VulkanRenderTargets`, `VulkanCompositeRenderer` |
| Client integration | `RenderPearlHost`, `VulkanCapabilities`, `IrisVulkanLifecycle`, `LiveMainTargetCompositor` |
| Required Fabric mixins | `VulkanDeviceMixin`, `VulkanSurfaceMixin`, `VulkanCommandEncoderAccessor`, `VulkanCommandPoolAccessor`, `VulkanCommandEncoderMixin`, `GameRendererMixin` |
| Verification | `RetirementQueueTest`, development-only `ClientVerification` and `VerificationMixin` |
| Configuration | `fabric/build.gradle`, `iris-vulkan.mixins.json`, verification `fabric.mod.json` / `iris-verification.mixins.json`, repository `.gitignore` |

See [VALIDATION.md](VALIDATION.md) for earlier results and [DEBUGGING.md](DEBUGGING.md) for the
subsequent portable Khronos validation run. Those checks cover this GPU, driver, and exercised
paths; they do not establish validation cleanliness across drivers or unimplemented pack passes.

## Phase 2 milestones 4–5 — complete

4. **In-game loader.** Put a folder or ZIP with a top-level `shaders/` directory in
   `<gameDir>/shaderpacks/`. Open **Options → Video Settings → Shaderpacks** (the Options shortcut
   also remains), select a pack and press **Apply**, then use **Options** to choose a profile or
   cycle source-declared values. Edits remain pending until **Apply** or **Done**; Escape cancels
   pending edits. Since Phase 5, selection is stored in `<gameDir>/config/iris.properties`, and
   options/profile in `<gameDir>/shaderpacks/<pack-name>.txt`. The older `iris-vulkan.properties`
   is read for migration when the new config does not exist. **Off** releases the selected pack
   at the next closed world-pass boundary; K restores the remembered successful selection.
   CPU parsing, include preprocessing, texture decoding, and SPIR-V compilation run in background
   workers. Only the latest successfully compiled request is installed on the render thread. A failed
   CPU load/compilation preserves the previous configuration. Phase 5 also allocates and validates
   replacement resources before retiring the old configuration, so failed allocation preserves it.
   Both failure types expose errors in **Diagnostics** and a toast; failed requests do not overwrite
   the persisted successful selection. R reloads the selected pack asynchronously.

   The loader evaluates Java property escapes/continuations, conditional macros, value macros,
   inherited profiles, disabled programs, and `program.<name>.enabled` expressions. Source options
   are applied before preprocessing, including in included files; conflicting source defaults are
   left unchanged rather than incorrectly rewritten across dimensions. It injects Minecraft version,
   OS, real GPU vendor/renderer, Iris, and `RENDERPEARL_IS_ZERO_TO_ONE` macros. Legacy `world0`,
   `world-1`, `world1` and `dimension.properties` mappings select whole dimension program sets.
   Standard gbuffers/shadow fallback chains resolve complete program pairs. Missing assets and
   unsupported GLSL constructs fail explicitly. Shaderc diagnostics include filename, stage, exact
   **transformed GLSL** line, surrounding lines, and original compiler output; these are not presented
   as original-source line numbers. Include preprocessing errors identify their included source file.

5. **Pack resource configuration.** `PackResourcePlan` derives requested targets and per-pass resource
   policies from the selected, preprocessed programs and properties. `PackGpuResources` allocates
   exact Vulkan formats and absolute/relative dimensions using Minecraft's VMA allocator. This covers
   `colortex0..15`, `depthtex0..2`, `shadowtex0..1`, `shadowcolor0..1`, custom `image.*` resources, and
   `colorimg0..5` storage aliases of the corresponding color images. Color targets have independent
   ping-pong views; descriptor sets already recorded for a pass remain immutable after flips.

   Clear policies/colors, first-use initialization of persistent temporal buffers, `Flip` comments,
   `flip.<pass>.<buffer>` overrides, per-pass mipmap requests, and shadow mipmap/filter directives are
   implemented. Mipmap blits track each mip's layout and read/write dependencies. Custom textures
   support PNG/`.mcmeta`, raw 1D/2D/3D/cube/array payloads, actual resource-manager assets, and real
   deterministic noise generation or pack-provided `noisetex`. Vulkan samplers honor wrap, separate
   min/mag filters, mip filters, integer formats, and shadow comparison. Unsupported device formats,
   filters, image shapes, or required features produce errors; no dummy image or format substitute
   is installed. Optional image/storage capabilities are requested through RenderPearl's real
   physical-feature negotiation before logical-device creation.

   Live resource installations, clears, uploads, resize/reload/world cleanup and deferred destruction
   are wired into the lifecycle. `beforePass`, `descriptors`, and `afterPass` implement the pass-time
   mip/descriptor/flip contract, exercised by the native compute integration test. The later world
   scheduler must call them around actual shaderpack passes; this phase does not replace vanilla
   terrain, entities, shadows, or OIT. Pack targets have their configured initial contents until those
   passes are connected; they are not substitutes for Minecraft's world inputs.

### Exact Phase 2 classes and build files

| Area | Added or modified classes/files |
|---|---|
| Pack loading | `ShaderPackLoader`, `PackOptions`, `PackProperties`, `PackExpression`, `ProgramFallbacks`, `CompilationDiagnostic`, `ShaderPackSource` |
| Resource configuration and assets | `PackResourcePlan`, `TextureFormat`, `TextureData` |
| Native GPU resources | `PackGpuResources`, `VulkanSamplers`, `VulkanRenderTargets`, `DescriptorFlipper`, `FrameDescriptors`, `VulkanCompositeRenderer` |
| Compiler diagnostics | `ShaderVulkanTransformer` stage-aware failures |
| Live lifecycle and UI | `LiveShaderPacks`, `ShaderPackScreen`, `IrisVulkanLifecycle`, `VulkanContextBridge` |
| Required mixins | `VulkanFeatureSetsMixin`, `OptionsScreenMixin`, `iris-vulkan.mixins.json` |
| Verification | `PackLoaderTest`, expanded `VulkanGpuTest`, development-only `PackVerification`, `ClientVerification` |
| Build and metadata | `build.gradle` (PNG metadata/Gson), `fabric/build.gradle`, Fabric `fabric.mod.json` |

The `compilePack` command reads a directory or ZIP with a top-level `shaders/` folder. It compiles
top-level graphics/compute programs and reports failures. It is a compatibility diagnostic,
not a shaderpack renderer. It does not yet evaluate shader options, dimension overrides, custom
uniform expressions, or Iris fallback-program rules. No pack is silently declared compatible
after a failed stage.

## Phase 3 milestones 6–7 — partial, with an explicit compatibility boundary

The first target is **Complementary Reimagined r5.9.3**, Modrinth version `Bqen1mJX`, downloaded
from the project's official distribution. Its unchanged default configuration compiles **30 programs
per dimension** in `world0`, `world-1`, and `world1`, targeting the live client's Vulkan 1.2 / SPIR-V 1.5.
This result establishes compilation, not rendered shaderpack compatibility. Distant Horizons,
Colorwheel and Voxy entry points require their respective integrations and are excluded from vanilla
program discovery. Optional custom-image/SSBO/material-attribute features are not advertised.
The pack ZIP is an external test input, not part of this repository or the distributed JAR.

```powershell
.\gradlew.bat benchmarkTest -PbenchmarkPack="C:/path/ComplementaryReimagined_r5.9.3.zip"
.\gradlew.bat -PwithFabric -PclientVerification :fabric:runVerification -PbenchmarkPack="C:/path/ComplementaryReimagined_r5.9.3.zip"
```

Implemented and exercised:

- `LiveUniformProvider` captures the actual world projection at its GPU upload boundary, after
  hurt/view bobbing and portal transforms. Camera and previous-frame matrices/positions, clock,
  celestial environment attributes, light levels, weather/wetness, effects, held-item mappings,
  viewport dimensions and resolved fog come from the live client. History resets on world changes.
  Invalid initial camera frames are deferred; no identity matrix or synthetic camera is substituted.
  Environment attributes remain available when vanilla omits its sky draw state.
- `UniformValue`, `UniformSnapshot`, `HalfLifeSmoother` and `CustomUniforms` implement immutable
  pass values, std140 writes, dependency/cycle diagnostics, lazy conditionals, scalar/vector math,
  swizzles and independent per-call-site smoothing in Iris's decisecond units. A missing requested
  provider is an error. Pack `item.properties` supplies held-item IDs; unmapped/empty items use Iris's
  absent-ID sentinel. `LiveShaderPacks` evaluates selected-pack expressions every captured frame and
  exposes `uniformsFor` for a pass plus its actual draw values. Pack swaps wait for a matching capture.
- `LiveDrawState` records real `DynamicGpuData` uploads and correlates native buffer slices and
  texture/sampler bindings at `FrontendRenderPass.validateDraw`. Compatibility matrix builtins use
  separate per-draw uniforms rather than incorrectly treating every object as the world camera.
  This observation hook does not replace the draw or complete instanced/indirect terrain routing.
- `NativeTextureImports` shares layout tracking across aliases, imports the real mipmapped terrain
  atlas and world lightmap, and restores `GENERAL` before returning to Minecraft. The live final pass
  binds and samples them using native samplers; its diagnostic atlas/lightmap screen is captured by
  the native screenshot path with HUD preserved. `bindDraw` resolves base-texture aliases from the
  actual `Sampler0` and `Sampler2` bindings. It supplies no fake normal/specular textures.
- `VulkanDepthConverter` writes `1 - nativeDepth` into an owned depth attachment using a real
  fullscreen Vulkan depth pass. The live compositor consumes this converted depth. Projection and
  inverse-projection conversion use the matching reversed-Z formula. GPU readback tests cover both
  endpoints, intermediate nonlinear values, and row orientation; CPU tests reconstruct view depth.
- Translation now handles legacy fog fields, modern implicit line inputs, uniform initializers
  through mandatory host UBO population, last-active draw-buffer directives and source option defaults
  omitted from UI ranges. Vertex/fragment interfaces preserve explicit locations and interpolation,
  reject missing producers and incompatible links, and support explicit-location matches with different
  names. Large shaders use heap-sized native UTF-8 buffers. AST transformation is serialized because
  glsl-transformer 3.0.0-pre3 has a shared non-thread-local build-root stack; shaderc work remains async.

Still required before marking **6–7 complete**:

- Route native terrain/entities/particles/hand and shadow draws into selected pack programs, with
  real per-object IDs, render-stage state, complete instanced chunk data and the extended vertex ABI.
  The frame provider and draw capture exist; the scheduler must consume them for those passes.
- Stitch/upload real resource-pack PBR normal/specular atlases and bind per-material textures.
  Wire selected-pack descriptors around every pass, including pre-translucent/pre-hand depth snapshots
  and actual rendered shadow resources. Allocated pack targets alone do not provide those contents.
- Implement geometry/tessellation-stage translation and execution where a pack requests them.
  Complementary's tested default programs use vertex/fragment/compute stages.
- Validate Complementary's actual world output: SSR/GTAO/fog, shadow matrices, orientation, motion
  history, and vertex/base-instance behavior. No screenshot of the built-in compositor is claimed
  as evidence that these effects render correctly. Resolve the intermittent native startup crash.

### Exact Phase 3 classes and build files

| Area | Added or modified classes/files |
|---|---|
| Uniform model and expressions | `UniformValue`, `UniformSnapshot`, `HalfLifeSmoother`, `CustomUniforms` |
| Live providers and bindings | `LiveUniformProvider`, `LiveDrawState`, `NativeTextureImports`, `LiveShaderPacks`, `LiveMainTargetCompositor`, `IrisVulkanLifecycle` |
| Required mixins | `GameRendererMixin`, `DynamicGpuDataMixin`, `ProjectionMatrixBufferMixin`, `FrontendRenderPassMixin`, `iris-vulkan.mixins.json` |
| Translation and loader | `ShaderVulkanTransformer`, `SpirvCompiler`, `DepthConvention`, `ShaderPackSource`, `ShaderPackLoader`, `PackOptions`, `ItemIdMap` |
| Native resources | `VulkanRenderTargets`, `VulkanDepthConverter` |
| Verification and build | `UniformTest`, `ShaderTests`, `PackLoaderTest`, `VulkanGpuTest`, `BenchmarkPackTest`, `UniformVerification`, `ClientVerification`, `PackVerification`, both `build.gradle` files |

## Phase 4 milestones 8–10 — partial

8. **Native mesh writing and Vulkan ABI implemented and exercised.** `DefaultVertexFormatMixin`
   extends actual BLOCK, ENTITY and ENTITY_GLINT_SPECIAL formats. `BufferBuilderMixin` writes
   quad-average UVs, geometry-derived tangents/handedness, normals, material IDs and signed
   1/64-block offsets with registry emission in `.w`. Real block/fluid tessellation provides block
   state and coordinates. Entity/block-entity submission scopes carry IDs through deferred model
   and block-model feature preparation. No replacement terrain or dummy world data is used.
   `NativeVertexFormats.layout` describes these exact buffers to Vulkan. Development-only verification
   copies a real terrain quad and checks its attributes through an actual Vulkan pipeline/readback.
   Selected-pack world pipelines do not consume these meshes yet; procedural feature coverage and
   Sodium's distinct mesh ABI remain open.

   The mesh stride stays stable across pack toggles so asynchronous chunk uploads cannot mix two
   incompatible layouts. Native shaders ignore appended fields. Expensive quad computation is skipped
   when no pack is active, but larger mesh storage remains. Pack changes invalidate compiled geometry.

9. **Capture/state prerequisites implemented; routing incomplete.** `FrontendRenderPassMixin` now
   captures each backend draw inside `drawMultipleIndexed`, after its chunk uniform uploader, and
   filters bindings against the active native pipeline to avoid stale uniforms/textures. This observes
   real draws; it does not replace them. `PackBlendOverrides` parses whole-pass/per-target factors.
   `VulkanPipelineManager` caches pipelines by program identity and complete supported graphics state,
   including blend operations, write masks, topology, polygon mode and depth bias. The recorder preserves
   `firstInstance`. GPU tests verify base-instance, blending and write-mask behavior. Actual native
   category-to-gbuffers replacement, alpha-test shader variants and OIT/sorted-translucent integration
   are not implemented.

10. **Engine ordering corrected; live scheduling incomplete.** `VulkanRenderGraph` now includes
    frame-start, prepare and shadowcomp phases, with deferred correctly between the pre-translucent
    depth snapshot and translucent geometry. An automated full-sequence regression checks that order.
    This graph is not yet connected to Minecraft's main/hand render loops. There is no claim that pack
    shadows, depth snapshots, deferred/composite or final output execute as a complete live frame.

The regression suite has **48 passing tests** (42 unit, 5 native GPU, 1 external benchmark).
See [PHASE4.md](PHASE4.md) for exact changes, client evidence and unresolved runtime failures.

## Implementation in the requested order

1. **Build** — `build.gradle`, `fabric/build.gradle`: Java 25, Fabric 0.19.5, Minecraft 26.3,
   Vulkan/shaderc/VMA/SDL bindings, a standalone core, and an optional Fabric bridge build.
2. **Context** — `VulkanContextBridge` and the Fabric mixins borrow the existing instance,
   physical/logical device, allocator, current command buffer, graphics queue family, submission
   counters, and swapchain format/extent/image index. The engine never owns the window or device.
   Recording inside an existing vanilla render pass fails explicitly. Submission remains owned
   by Minecraft; the engine retires resources against the host's completed submission serial.
3. **Compiler** — `ShaderPackSource`, `ShaderVulkanTransformer`, `Std140Layout`, `DescriptorSchema`,
   `SpirvCompiler`: conditional includes and macro preprocessing; AST semantic transformations;
   deterministic program-wide UBO and descriptor ABI; native shaderc compilation targeting Vulkan
   1.2/SPIR-V 1.5 or 1.3/SPIR-V 1.6; bounded asynchronous compilation; content-checked, atomically written SHA-256
   cache entries under `cache/iris_vulkan_spirv/`.
4. **Resources** — `VulkanRenderTargets`, `DescriptorFlipper`, `FrameDescriptors`: exact supported
   format mapping and device format checks; VMA images and views; depth copies; selective ping-pong
   state; distinct immutable descriptor sets and uniform bytes per recorded pass. Pools are reused
   only after the submission that used them has completed.
5. **Execution** — `VulkanPipelineManager`, `VulkanRenderGraph`, `VulkanCompositeRenderer`,
   `VulkanShadowRenderer`: dynamic graphics/compute pipelines; explicit resource/phase dependencies;
   attachment selection; graphics and indexed geometry draws; compute dispatch; Synchronization2
   barriers, including same-layout storage-image hazards; opaque/translucent shadow recording.
   Actual world geometry and frame data must be supplied by an integration layer that is still missing.

## Depth and resource contract

- Shaderpack projection uniforms retain OpenGL `[-1,1]` clip-space semantics. The vertex
  entry-point wrapper converts only the final `gl_Position.z` to `(z+w)/2`, including after early
  returns from the pack's main function, for non-reversed internal pack depth attachments.
  `DepthConvention` has explicit conversions for both normal and reversed zero-to-one host matrices.
  For RenderPearl, `P_gl` row Z is `P_host` row W minus twice row Z. Do not convert an already GL matrix.
- Sampled depth remains the usual `[0,1]` nonlinear depth. Pack reconstruction such as `depth*2-1`
  remains paired with an OpenGL-style inverse projection. RenderPearl's reversed depth is converted
  with `VulkanDepthConverter`; directly pairing an unconverted host depth texture with `P_gl` is wrong.
- Internal pack images use a consistent UV/row convention; the recorder exposes a final-output Y
  flip. The live final pass uses `texelFetch` with framebuffer pixel coordinates and requires no Y
  flip. Shadow coordinates, SSR, GTAO, and volumetric fog have not been verified in a real pack.
- All work currently uses the graphics queue. Cross-queue ownership transfers are not implemented.
- A sampler cannot alias a current color/depth attachment. Sampled/storage aliases are rejected.
  Ping-pong descriptors keep earlier draws immutable; compute storage descriptors use `GENERAL`.
- `copyDepth` copies identical single-sample depth formats with `vkCmdCopyImage`, avoiding optional
  depth-blit support. It restores the source for depth rendering and the destination for sampling.
- Pack images support mip chains, 1D/2D/3D, cube and array views; render targets remain single-sample.
  Borrowed main targets retain their single mip; native atlas imports track the actual mip chain.
- Uniform bytes use persistently mapped, aligned VMA pages per frame-in-flight slot. Each pass binds
  its own dynamic uniform offset; slot memory and descriptor pools reset only after completion.
  Descriptor-set layouts and pipelines are cached. Driver pipeline bytes are checksummed and atomically
  persisted under `cache/iris_vulkan/`, keyed by device, driver and pipeline-cache UUID.
- `gl_InstanceID` becomes `gl_InstanceIndex`; the recorder now preserves `firstInstance` explicitly.
  Vulkan's index includes the base instance. Translating native instanced/procedural world draws into
  a pack's expected per-object semantics still requires integration; no general compatibility is claimed.

## Remaining integration work

These items are required before this can satisfy the full rewrite specification:

- Wire shaderpack pass graphs and full world replacement into the now-live lifecycle and final-output
  boundary. Phase 2 loads/compiles the selected pack and installs its GPU resources; the world still
  uses native terrain/entities and the built-in final compositor.
- Consume the implemented frame/custom uniforms, captured model-view state, live lightmaps and terrain
  atlases in actual selected-pack draws; finish material data and per-pass providers. Consume stored blend/alpha/pass-state directives in the
  world scheduler. Noise/custom textures, dimensions, options, fallback resolution, image policies,
  and resize/reload allocation are implemented; they do not supply missing world draw inputs.
- Implement and test OIT bypass **together with** sorted terrain/entity/particle routing. Restore native
  OIT when the shader toggle turns off. K/R controls are implemented; native OIT remains active.
- Connect the implemented vanilla terrain/entity mesh data and `NativeVertexFormats.layout` to selected
  gbuffers pipelines, including native instanced/procedural paths. Extend and verify Sodium 0.9.2's own
  packed layout independently; it cannot use the vanilla layout unchanged.
- NeoForge packaging, key registration and the shared renderer lifecycle now exist; both development
  artifacts require the still-missing world renderer before they can become playable shader releases.
- Complete GLSL compatibility: user-defined uniform structs, constant-expression
  array sizes, storage buffers, geometry/tessellation stages, remaining
  compatibility builtins and shader-specific patches. Numeric multidimensional arrays with literal
  dimensions and loose-uniform/sampler/image binding reconciliation are implemented.
  Unsupported constructs fail rather than producing a guessed host memory layout.
- Run real shaderpacks in Minecraft with Vulkan synchronization validation, render captures, repeated
  reloads/toggles/resizes, multiple frames in flight, and representative NVIDIA/AMD/Intel devices.

See [VALIDATION.md](VALIDATION.md) for observed test results and [API_AUDIT.md](API_AUDIT.md)
for exact upstream integration evidence. Original Iris license files remain in the repository;
`glsl-transformer` retains its own AGPL license.

Successful client runs coexist with native `0xC0000005` failures during startup/resource reload and a
prior `0xC0000374` shutdown. Phase 4 captured an unhandled fault inside Oracle 25.0.2's `jvm.dll`, then
passed on portable Temurin 25.0.4.1. This does not establish the root cause or resolve every native
stability concern. See the validation records.
