# Phase 4 implementation and verification — 2026-10-26

**Partial implementation, not a playable shaderpack renderer. Do not mark milestones 8–10 complete.**
The repository already documented milestones 6–7 as incomplete when this work began. Native world
rendering and OIT remain active, and the final screen pass remains the built-in compositor.

## Implemented changes

| Area | Exact classes/files | Behavior |
|---|---|---|
| Pack material IDs | `pack/MaterialIdMap`, `pack/ShaderPackLoader` | Preprocess/load `block.properties` and `entity.properties`; resolve namespaces, state selectors and specificity; diagnose conflicting IDs. |
| Quad attributes | `render/QuadVertexData`, `render/IrisVertexFormats` | Quad UV center, geometry-derived tangent/handedness, degenerate-UV handling and signed 1/64-block offsets; four-component mid-block attribute includes emission. |
| Actual mesh format | `fabric/NativeVertexFormats`, `mixin/DefaultVertexFormatMixin` | Extend real BLOCK, ENTITY and ENTITY_GLINT_SPECIAL formats while preserving native field offsets; construct matching Vulkan input descriptions. BLOCK stride is 96 bytes. |
| Actual mesh writer | `mixin/BufferBuilderMixin` | Append attributes after complete quads, including the last quad at build; use the current native allocation after buffer growth. Skip quad computation with no active pack. |
| Block/fluid origin | `fabric/VertexDataContext`, `mixin/BlockVertexContextMixin`, `mixin/FluidVertexContextMixin` | Worker-local scopes supply actual registry block state, block/fluid render type, local center and emission. |
| Entity origin | `fabric/VertexContextCarrier`, `mixin/EntitySubmissionContextMixin`, `mixin/BlockEntitySubmissionContextMixin`, `mixin/FeatureSubmissionContextMixin`, `mixin/ModelVertexContextMixin`, `mixin/BlockModelVertexContextMixin` | Preserve the originating entity/block-entity ID through deferred feature submissions; restore contexts with try/finally. This covers model and block-model features, not every procedural feature renderer. |
| Pack changes | `fabric/LiveShaderPacks` | Resolve block states on installation, invalidate actual compiled chunk geometry on pack changes, and discard ID mappings on dimension changes/Off. |
| Draw observation | `fabric/LiveDrawState`, `mixin/FrontendRenderPassMixin` | Capture each actual backend indexed draw after its individual uploader in `drawMultipleIndexed`. Filter retained bindings against the bound pipeline's declared uniforms. Track individual chunk captures. Native draw commands are not canceled. |
| Graphics state | `pack/PackBlendOverrides`, `render/VulkanPipelineManager`, `render/VulkanCompositeRenderer` | Load global/per-target blend factors; preserve blend operations, write masks, topology, polygon mode, depth bias and base instance in supported engine commands; cache native pipelines by compiled-program identity and graphics state. |
| Feature negotiation | `mixin/VulkanFeatureSetsMixin` | Request supported `independentBlend`/`fillModeNonSolid` during actual device creation. Engine pipeline creation rejects those states if their logical-device features were not enabled. |
| Frame order | `render/VulkanRenderGraph` | Add frame-start, prepare and shadowcomp; move deferred before translucent geometry, after its depth snapshot. This corrects the engine graph, not Minecraft's active scheduling. |
| Build/mixin registration | `fabric/build.gradle`, `fabric/src/main/resources/iris-vulkan.mixins.json` | Compile against Fabric Loader's MixinExtras 0.5.5 and register required real 26.3 targets. |

Stable strides avoid mixing old and new formats in asynchronously built/uploaded chunks. Native
pipelines ignore the extra fields when shaders are off. The larger vertex storage/bandwidth cost
remains even when Off; this has not been performance-qualified for release. Sodium is not supported.

## Automated evidence

Baseline before Phase 4: **42 tests passed** (`phase4-baseline.log`). Current suite:

- **42 unit tests + 5 native GPU tests + 1 external benchmark = 48**, no failures/skips.
- Added `VertexDataTest`: tangent/UV/mid-block math, mirrored handedness, degenerate UVs, material
  selectors, conflicting IDs and per-target blend precedence/write-mask retention.
- Added `VulkanGpuTest.pipelineCachePreservesBlendOperationsWriteMasksAndBaseInstance`: reads pixels
  after reverse-subtract blending, masked channels and a nonzero base instance. Different states must
  produce different cached pipelines; repeated identical states reuse the same native pipeline.
- Added a full ordered-phase regression in `RenderGraphTest`, including rejection of deferred after
  translucent geometry. This is an engine scheduling test, not proof of live frame orchestration.
- Complementary Reimagined r5.9.3 still compiles 30 default programs in each of three dimensions.
  Compilation does not establish visual compatibility.

Build log: `phase4-final-build.log`. All GPU tests enabled portable Khronos synchronization validation.
The final Fabric packaging check also passed (`phase4-final-package.log`); all 20 registered production
mixins are present and verification classes/resources are absent. Artifact:
`fabric/build/libs/iris-vulkan-fabric-bridge-0.1.0-dev+mc26.3.jar`, SHA-256
`b52c7d584bfa87f29b713ea0c1cb03e0fe0a57a8765eb0cd489950b2356636f7`.

## Live verification

The final run (`phase4-client-batches.log`) passed on **Temurin 25.0.4.1+1**, Minecraft 26.3,
Fabric 0.19.5, LWJGL 3.4.3, GTX 1660 SUPER/driver 621.01. It completed resize, resource reload,
world exit/reentry, folder/ZIP/options/Off checks, Complementary loading, HUD/screenshots and shutdown.
No Vulkan VUID/SYNC errors were reported. It recorded 1,542 compositor frames, seven compositor
generations and zero pending Iris retirements at shutdown.

New development-only classes: `verify/VertexVerification`, `verify/mixin/VertexVerificationMixin`,
`verify/mixin/TerrainPathVerificationMixin`, `verify/mixin/DrawVerificationMixin`; updated
`verify/ClientVerification`, verification mixin JSON and Gradle result checks.

- Observed **53,248 real terrain vertices** and **17,856 entity vertices** while packs were active.
- Copied one quad from an actual worker-built terrain mesh. Checked its UV center, tangent basis,
  handedness, consistent block center, registry emission range and consistent material ID.
- Uploaded those actual bytes through Minecraft's buffer API, bound `NativeVertexFormats.layout`
  to an engine Vulkan pipeline, and read back a 4×4 attribute-validation draw after the host's actual
  submission completed. This diagnostic viewport is a test, not selected-pack world output.
- Alternated the real native indirect/non-indirect terrain paths in the development mod. Every
  `drawMultipleIndexed` batch asserts that the number of captured chunk uploads equals its actual
  draw count. **5,009 individual chunk draws** were observed and checked.
- `fabric/run-verification/iris-phase4-vertices.json` records `passed:true` and explicitly retains
  `worldRoutingComplete:false`. Verification classes/mixins are excluded from the distributed JAR.

The screenshot harness now waits for the loading-terrain screen to close before comparing world
exposure. A failed traced run had compared two loading screens; its assertion was not weakened.
The isolated harness also starts with Off so an interrupted previous fixture does not contaminate
the next baseline screenshot. The normal player's game configuration is not touched.

## Native failures and Java comparison

Oracle 25.0.2 runs still failed with `0xC0000005` during resource reload. These are failures even
where earlier checks passed. `phase4-client-trace2.log` and ignored
`reference/phase4-native-trace.txt` captured a second-chance/unhandled access violation in
`jvm.dll+0x9E7B97`; the stack-address scan identified other JVM offsets. This identifies a fault
location, not a Java/driver/mod root cause. The earlier shutdown heap-corruption report remains open.

A portable [official Temurin 25 distribution](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1)
was downloaded and SHA-256 checked inside ignored `reference/java25-validation/`. Archive SHA-256:
`00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283`.
Installed Java, drivers, system environment and overlays were not modified. The same workload
passed twice on that runtime (`phase4-client-temurin.log`, `phase4-client-batches.log`), including
native mesh readback. This narrows the investigation but does not prove a general crash fix.

## Work still required for milestones 8–10

1. Consume the native vertex layouts in the selected pack's actual world pipelines, including
   procedural/instanced sky, clouds, particles and all entity features; add Sodium separately.
2. Implement native-category to gbuffers selection, full per-draw state/alpha-test handling, and
   texture/uniform reconciliation. Pipeline state support and observation hooks do not route draws.
3. Replace OIT together with the actual sorted translucent terrain/entity/particle path and restore
   native behavior on Off. Simply suppressing the OIT composite would lose geometry.
4. Connect actual prepare/shadow/shadowcomp/opaque/depth1/deferred/translucent/depth2/hand/composite/final
   recording to Minecraft's frame loop. The native classic path currently records opaque and
   translucent commands inside one render pass; depth-copy barriers/postprocessing require real
   pass boundaries, not an injection that records transfers inside active dynamic rendering.
5. Extract/cull real shadow-casting world geometry in light space, including off-camera casters.
   Reusing only camera-visible geometry would produce incomplete shadows.
6. Finish Phase 3 material textures and pack-specific input translation, then visually verify
   Complementary's complete output. Preserve temporal targets and hand off final output before GUI.

The pre-debug source/JAR backup remains at
`../../Iris-Vulkan-backups/before-debug-20261026-110749/`; see `DEBUGGING.md` for its verified manifest.
