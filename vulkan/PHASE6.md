# Phase 6: compatibility, performance and dual-loader development artifacts

This phase is **partial**. It does not establish broad shaderpack playability or complete milestone 16.
Earlier milestones still lack selected-pack world draw routing, actual shadow casters and the full
live frame sequence. Those missing paths also prevent a truthful 100% completion or production release claim.

Before editing, source (including untracked files) and the built mod were backed up in
`Iris-Vulkan-backups/before-phase6-20261026-152350/` beside the repository. The source ZIP contains
1,031 entries; its contents were read back and the copied artifact hashes recorded in `manifest.json`.
The starting build, 49 unit tests, five GPU tests and three-dimension benchmark test all passed.

The final `phase6-final-build.log` records successful dual-loader builds, packaging and **61 tests**:
54 unit, six native GPU (Khronos validation enabled on GTX 1660 SUPER), and one benchmark that compiles
30 Complementary programs for each vanilla dimension. No unit/GPU/benchmark failures or skips were
reported. New coverage includes source-binding reconciliation, matrix compatibility, unbound-block
rejection, multidimensional GPU reads, ring growth/reuse and pipeline-cache integrity/persistence.

| Milestones | Current status |
|---|---|
| 1–5 | Implemented development bridge, lifecycle, target handoff, loader and supported pack resources |
| 6–7 | Partial: live providers and benchmark compilation; complete pack rendering/translation unverified |
| 8 | Partial: vanilla extended meshes; Sodium data path absent |
| 9–10 | Incomplete: selected-pack world routing and complete live frame scheduling |
| 11–12 | Incomplete: world shadow casters and sorted transparency/OIT handoff |
| 13 | Loader/resource controls implemented; full world-pipeline switching still depends on routing |
| 14 | Incomplete: interactive shaderpack visual/stability qualification |
| 15 | Partial: additional translations, with advanced features listed below still missing |
| 16 | Incomplete: API audit and switching fix; required terrain-bindings test fails |
| 17 | GPU optimizations, both loader builds and development packaging implemented; production qualification incomplete |

## Milestone 15

Implemented:

- AST removal/reassignment of source `set`, `binding` and loose-uniform `location` qualifiers.
  Image format and access qualifiers survive; all stages use the deterministic engine descriptor ABI.
- Additional inverse, transpose and inverse-transpose compatibility matrices, derived from the existing
  live model-view/projection values. `gl_TextureMatrix[0..7]` remains supported.
- Multidimensional numeric uniform arrays with literal dimensions. The std140 ABI retains dimensions
  in GLSL and exposes flat element order to host writers. The GPU test consumes `vec4 weights[4][256]`
  and checks actual pixels, including data preserved while subsequent passes allocate another page.
- ABI cache version bump. Explicit UBO/SSBO blocks without a host allocator now fail with a named
  diagnostic before they can reach Vulkan with unbound or colliding descriptors.

Still missing: nested uniform structs, constant-expression array dimensions, SSBO allocation/bindings,
geometry/tessellation stages and interfaces, remaining lighting builtins, PBR atlas integration and
live SSR/GTAO/TAA/volumetric validation. Rejecting unsupported buffer blocks is not SSBO support.
Complementary Reimagined r5.9.3 remains the compilation target, not a claim of rendered world effects.

## Milestone 16

Inspected the actual Sodium **0.9.3-beta.1+mc26.3 Fabric** artifact, downloaded from its official
Modrinth distribution. SHA-1: `1e6aec741b4134258ba1d7d458bae96a2e8e187e`.

Verified integration points:

- `ChunkVertexType.getVertexFormat()` returns RenderPearl `VertexFormat`.
- `ChunkVertexEncoder.write(long,int,Vertex[],int)` emits packed quads.
- `CompactChunkVertex` uses a 20-byte packed format, distinct from the extended vanilla format.
- `ChunkBuildBuffers` and `ChunkMeshBufferBuilder` allocate/upload using that format's stride.
- `DefaultChunkRenderer.render(...)` receives native `RenderPass`, samplers, globals, buffers and
  `OitStage`. Region draws use 20-byte push constants for camera-relative offsets, age and region ID.
- `ShaderChunkRenderer` owns material/OIT pipeline variants and native binding groups.

The live coexistence test found a real switching bug: `LiveShaderPacks.install` invalidated terrain
after world drawing, causing Sodium to wait on a fence for the current, unsubmitted command buffer.
Terrain invalidation is now queued and performed at the next `Minecraft.runTick` entry, before
extraction/uploads, with a world-identity check. `GameRenderer.render` entry was tested and is too late:
Sodium has already uploaded new staging data by that point. Crash Assistant and the original crash report are preserved under
`reference/phase6-sodium-fence-failure/`. No logs were uploaded.

After the fix, Sodium completed the pack-loader and all 18 control/lifecycle stages, including
dimensions, fullscreen and resource reloads. The harness then **failed** with `Phase 3: Missing actual
object/terrain upload bindings`, correctly exposing the unimplemented Sodium provider. Evidence is in
`reference/phase6-sodium-missing-bindings/`. The assertion was not disabled. The temporary Sodium JAR
was hash-checked and removed from the default development client after testing.

Sodium's extended encoder, block/fluid context, custom terrain pipelines, region uniforms, shadow
draws and sorted translucent routing are **not implemented**. A coexistence run cannot substitute
for these requirements; Sodium is not advertised as supported. Sodium itself is not bundled.

## Milestone 17

Implemented GPU work:

- `FrameDescriptors` owns reusable mapped uniform pages per submission slot. Dynamic offsets obey
  `minUniformBufferOffsetAlignment`; ranges obey `maxUniformBufferRange`. Growth retains pages already
  referenced by recorded commands. Reuse requires completed submissions; destruction is deferred.
- `VulkanCompositeRenderer` binds dynamic offsets for graphics and compute, including zero-uniform cases.
- `VulkanPipelineManager` shares descriptor layouts with identical bindings and retains its full
  graphics-state pipeline cache. Shared layouts are destroyed once, after GPU completion.
- `VulkanPipelineCache` supplies native graphics/compute creation with a real `VkPipelineCache`.
  Device/driver/UUID-specific cache files under `cache/iris_vulkan/` use SHA-256 payload integrity,
  native header validation and atomic replacement. Corrupt/foreign files are ignored. A cache I/O
  failure is logged and does not disable rendering. GPU tests create, persist and reload the cache.

Implemented loaders:

- Fabric 0.19.5 / Loom 1.15.4 and NeoForge **26.3.0.98** / ModDevGradle **2.0.148**, Java 25.
- Both compile the same Minecraft bridge source against their own actual runtime. Its Java package
  retains the historical `.fabric` name but imports no Fabric API.
- NeoForge has a client-only `@Mod` entrypoint and real `RegisterKeyMappingsEvent` registration.
  It excludes Fabric's Options key-registration mixin to avoid duplicate keys. Renderer/world/reload
  lifecycle hooks are shared, not duplicated through a second event path.
- Each artifact bundles the engine, glsl-transformer, ANTLR and JCPP using its loader's nested-JAR
  format. Minecraft supplies its matching LWJGL/native libraries, JOML and Gson.
- `packageDevelopmentRelease` checks loader metadata, all declared mixin classes, bundled libraries,
  absence of verification/game classes, and emits both JARs plus `SHA256SUMS.txt` in `build/releases/`.
  This packages development artifacts and does not publish anything.

### NeoForge validation fixes

The first live NeoForge run completed its functional checks but validation reported **1,754 leaked
objects** at device destruction. Named-object inspection and exact dependency source/bytecode showed:

1. NeoForge adds `withStencilPipeline` and `withDepthStencilPipeline` to `VulkanRenderPipeline`, but
   26.3.0.98 omits them from `destroy()`. `StencilPipelineRetirementMixin` destroys these variants in
   the existing completed-submission destruction path. A bytecode guard skips the repair when the
   native destroy method already accesses those fields.
2. Earlydisplay 12.0.12 omits final GPU buffers from `SimpleBufferBuilder.close()` and its backend
   cleanup. `EarlyDisplayBufferRetirementMixin` tracks only buffers created by the RenderPearl loading
   backend and closes still-open ones when that backend closes. This releases `shared_error` and
   `ScreenSize UBO`, without owning the shared index buffer or Minecraft device.

The repaired NeoForge run completed **2,328 compositor frames, 15 generations**, all three dimensions,
fullscreen/window changes, reload/toggle/failed-compilation tests, and native vertex readback, with
no Vulkan VUID/synchronization errors and zero pending Iris retirements. The corresponding Fabric
run completed **2,274 frames, 15 generations** with no Vulkan validation errors. These runs validate
the development bridge and built-in compositor, not a shaderpack world renderer.

After the final terrain-invalidation hook change, both clients were run again independently without
Sodium. `phase6-final-fabric.log` passed with **2,393 frames / 15 generations**;
`phase6-final-neoforge.log` passed with **2,369 frames / 15 generations**. Both completed all functional
reports, clean shutdown and the validation-log check. Final evidence is archived locally in
`reference/phase6-final-validation/`. The Sodium failure remains separate and is not counted as a pass.

Both client verification tasks now fail on logged `VUID-` or `SYNC-HAZARD` messages, including errors
reported during shutdown after functional result JSONs were written. Expected bad-pack diagnostics
are still tested. The machine's stale Medal layer-manifest and Windows performance-counter warnings
are recorded separately; successful checks do not prove the older intermittent native crash resolved.

## Build and installation

From `vulkan/`, with a Java 25 JDK:

```powershell
.\gradlew.bat -PwithFabric -PwithNeoForge build gpuTest benchmarkTest -PbenchmarkPack="C:/packs/ComplementaryReimagined_r5.9.3.zip"
.\gradlew.bat -PwithFabric -PwithNeoForge packageDevelopmentRelease
.\gradlew.bat -PwithFabric -PclientVerification :fabric:runVerification -PbenchmarkPack="C:/packs/ComplementaryReimagined_r5.9.3.zip"
.\gradlew.bat -PwithNeoForge -PclientVerification :neoforge:runVerification -PbenchmarkPack="C:/packs/ComplementaryReimagined_r5.9.3.zip"
```

Install the loader matching the selected development JAR in a separate Minecraft 26.3 profile.
Copy only that loader's JAR from `build/releases/` into its `mods/` directory. Select the Vulkan
graphics backend. Put pack ZIPs/folders in `<gameDir>/shaderpacks/`; Video Settings → Shaderpacks
loads/configures packs. K toggles pack resources and R reloads. Current controls do not produce
shaderpack world effects. Upstream Iris must not be installed beside this development engine.
NeoForge versions below 26.3.0.98 are not claimed tested or accepted by this artifact.

For diagnostics, retain `logs/latest.log`, `crash-reports/`, Shaderpacks → Diagnostics output and
Crash Assistant's local log. Do not treat a successful pack compile as visual compatibility. A
pipeline-cache problem can be isolated by renaming `<gameDir>/cache/iris_vulkan/` while the client
is closed; SPIR-V cache files live separately in `cache/iris_vulkan_spirv/`.

## Files changed

- Build: `settings.gradle`, `build.gradle`, `fabric/build.gradle`, and the new `neoforge/` module.
- Shader ABI: `ShaderVulkanTransformer.java`, `Std140Layout.java`.
- GPU resources: `FrameDescriptors.java`, `VulkanCompositeRenderer.java`, `VulkanPipelineManager.java`,
  new `VulkanPipelineCache.java`.
- Live bridge: `LiveMainTargetCompositor.java`, `LiveShaderPacks.java`, `GameRendererMixin.java`,
  `MinecraftFrameBoundaryMixin.java` and both loaders' shared mixin lists.
- NeoForge: `IrisVulkanNeoForge.java`, `NeoForgeCompatibilityPlugin.java`, two compatibility mixins,
  loader/mixin metadata, and separate verification-mod metadata.
- Tests: `ShaderTests.java`, `VulkanGpuTest.java`, new `render/PipelineCacheTest.java`.
- Documentation: this file and `README.md`.
