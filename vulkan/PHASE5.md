# Phase 5 — partial implementation and live verification

Phase 5 is **not complete** and Complementary Reimagined is **not playable through the selected
pack's world shaders**. The Phase 4 prerequisites remain missing: native draw replacement,
procedural/instanced vertex adaptation, per-pass material/texture bindings, and the complete
world frame scheduler. No OIT pass is suppressed in this implementation.

## Milestone status

| Milestone | Implemented here | Still required |
|---|---|---|
| 11: world shadows | Live celestial shadow camera, orthographic projection/inverses, camera-relative texel snapping, interval handling, CPU light-frustum/distance tests | Feed off-camera terrain/entity/block-entity casters to real selected-pack shadow pipelines, polygon bias, opaque/translucent shadow depth boundary and filtering validation |
| 12: transparency | Verification that native OIT stays active through loader switches/failures | A ready world pipeline, actual sorted translucent replacement, depth-copy boundaries, and conditional OIT ownership handoff |
| 13: controls | Rebindable K/R, Video Settings entry, draft Apply/Done UI, config migration/per-pack options, async latest-request loading, transactional GPU resource replacement | Swap an executable **world pipeline**, not just the currently implemented compiled program/resource state |
| 14: playability | Expanded validation-enabled client lifecycle/control tests, dimension transitions and fullscreen | Render/visually validate Complementary world effects and shadows, resolve intermittent native crashes, verify complete pass synchronization and long-session resource stability |

## Shadow camera

`render/ShadowCamera` uses JOML **1.10.9**, matching the actual 26.3 manifest. It receives the
live camera position and world-space sun/moon direction including the selected pack's
`sunPathRotation`. It resolves pack scalar expressions for `shadowDistance`, `shadowIntervalSize`,
`shadowMapResolution`, `shadowNearPlane`, and `shadowFarPlane`. Invalid settings and perspective
`shadowMapFov` fail explicitly rather than silently using the orthographic camera.

The anchor is calculated in double precision, including at large negative world coordinates.
It snaps to the requested world interval and light-space texel grid before forming the
camera-relative view matrix. Projection uniforms retain the shaderpack's OpenGL [-1,1] depth
convention, matching the existing vertex epilogue conversion. Projection extent remains the
pack's requested extent; caster-distance selection is clamped to the live render distance.

`LiveUniformProvider` now supplies `shadowModelView`, `shadowModelViewInverse`, `shadowProjection`,
`shadowProjectionInverse`, and `shadowLightPosition` from these inputs. Live tests check inverses
and distance limits across dimension/viewport changes. Unit tests check light direction,
near/far endpoints, polar directions, camera-motion stability and AABB culling.

The CPU frustum method is tested but **not connected to world-caster extraction**. It represents
the linear orthographic frustum; a pack that distorts its shadow projection needs conservative
caster bounds before using this method to reject world geometry. Allocated shadow textures
still do not contain a rendered shaderpack world-shadow pass.

## Controls and replacement fixes

- `OptionsKeyMappingMixin` appends native `KeyMapping`s before `Options.load()`. Minecraft's
  Controls screen and `options.txt` handle rebinding/persistence through its SDL-backed input.
  `ShaderControls` consumes K/R at `GameRenderer.render()` entry; GUI/text-entry clicks are
  discarded. Requests are committed outside native dynamic rendering, after the world pass.
- `VideoSettingsScreenMixin` adds a normal scrolling Video Settings button. The existing
  Options shortcut remains. `ShaderPackScreen` stages selections/profiles/options until Apply
  or Done. Escape returns without applying pending edits. Apply can retry an unchanged failed
  selection. Newly selected packs must be applied/compiled before their option declarations
  can be edited.
- `PackConfiguration` uses `config/iris.properties` (`shaderPack`, `enableShaders`) and separate
  `shaderpacks/<pack-name>.txt` option files (`iris.profile` stores the selected profile).
  It retains unrelated main settings, remembers the last successful selection while Off,
  migrates the old config without deleting it, validates names and atomically replaces each
  file. It does not claim a cross-file filesystem transaction.
- `LiveShaderPacks.install` prepares GPU resources and validates custom-uniform dependencies
  before committing the new state. Failure closes only the candidate resources and preserves
  the resident program/resources and persisted selection. Superseded background completions
  cannot reactivate a pack after Off. Old resources use the existing submission-indexed
  retirement queue. Dimension changes still discard resources belonging to the previous world.
- `IrisVulkanLifecycle` separates compositor retirement from pack retirement on resize/reload.
  Previously these resets destroyed the pack resources before replacement preparation could
  succeed. Resize/reload now leave the resident pack available while preparing a replacement;
  world unload/shutdown still release it.

These controls operate the implemented loader/resource state. They do not claim that merely
having compiled programs means a selected-pack world pipeline is ready to take over OIT.

## Real client API findings

The official 26.3 client confirms `LevelRenderer.addMainPass` records solid and classic
transparency into the **same native RenderPass**. `executeOit` instead runs after that pass
closes. `GameRenderer.useImprovedTransparency` also controls sorting during chunk preparation.
Therefore suppressing only the OIT composite, or injecting depth-copy barriers at the classic
transparency call without closing the native pass, would be incorrect. A replacement must
control extraction, sorted geometry preparation, all category draws and actual render-pass
boundaries together. This patch does not install a dummy readiness flag or cancel native draws.

## Verification and failures

The pre-edit baseline passed **48 tests**: 42 unit, five native GPU, one external benchmark
test compiling 30 Complementary Reimagined r5.9.3 programs in each vanilla dimension.
New tests cover four shadow-camera cases and three configuration cases.

Final clean rerun (`phase5-final-build.log`): **55 tests passed**, with zero failures/errors/skips:
49 unit tests, five native GPU tests and one three-dimension benchmark test. Both Gradle modules
built successfully. All 22 registered production mixin classes are present in the packaged JAR;
verification classes/resources are absent. Final development artifact:
`fabric/build/libs/iris-vulkan-fabric-bridge-0.1.0-dev+mc26.3.jar`, SHA-256
`8adaae1bbbf97a3b5428120301f91929907cac91c33e27956a5f0c7b17cd0530`.

The expanded client run `phase5-client-oit.log` exited 0 on portable Temurin 25.0.4.1+1,
Minecraft 26.3, Fabric 0.19.5, LWJGL 3.4.3, GTX 1660 SUPER/driver 621.01, with Crash Assistant
1.11.14 and Khronos core/synchronization validation enabled. No VUID/SYNC errors were reported.

`ControlsVerification` exercises real registered key mappings and native GUI callbacks, including:

- Off/on and R replacement; ten consecutive frames of reload requests followed by Off;
  waiting for stale completions and then restoring the remembered selection.
- A real shaderc failure and a CPU-compiled pack whose viewport-sized GPU allocation preparation
  fails. Both retain the prior `Loaded` and `PackGpuResources` identities, evaluated uniforms,
  and the persisted successful selection.
- Video Settings → Shaderpacks, draft selection, Apply and Done.
- Server-driven teleportation to the Nether, End and Overworld, with matching resident pack
  dimension and live uniforms after each transition.
- Actual fullscreen resize from 1100×760 to 1920×1080 and back; native resource reload via
  `Minecraft.reloadResourcePacks()` (the underlying F3+T operation, not a simulated F3+T chord).
- Native OIT explicitly enabled during these checks and its original option restored afterward.
  The first expanded run exposed that the saved option was Off; the test now enables it and
  still requires OIT to have actually been active.

Successful run: 2,355 compositor frames, 15 compositor generations, 21 pack installations,
96,256 terrain vertices, 49,056 entity vertices, 5,883 batched chunk draws, successful native
GPU vertex readback, and **zero pending Iris retirements at shutdown**. These numbers do not
establish absence of all memory leaks. `iris-phase5-controls.json` records the exercised checks
and explicitly retains `worldEffectsRendered:false`. Complementary is compiled/evaluated during
the earlier harness stage; the switching/dimension stress checks use the existing small fixture pack.

One preceding run (`phase5-client.log`) exited during initial resource reload with native
**0xC0000409**, before reaching the title screen/world/control tests. Crash Assistant captured
the logs but no Minecraft crash report or JVM fatal-error file identified a cause. Evidence is
preserved in `reference/crash-assistant/phase5-startup-failure-20261026-131101/`. This occurred
on Temurin too, so Java selection is not a demonstrated fix for native instability. The traced
repeat reached the later OIT test-setup assertion; it did not reproduce that native fault.
The earlier Oracle access violation and shutdown heap-corruption observations remain unresolved.

## Changed files and backup

Engine: `build.gradle`, `render/ShadowCamera`, `pack/PackConfiguration`, `ShadowCameraTest`,
`PackConfigurationTest`.

Fabric production: `LiveUniformProvider`, `LiveShaderPacks`, `IrisVulkanLifecycle`,
`ShaderPackScreen`, `ShaderControls`, `GameRendererMixin`, `OptionsKeyMappingMixin`,
`VideoSettingsScreenMixin`, mixin JSON and `assets/iris_vulkan_engine/lang/en_us.json`.

Development verification: `ControlsVerification`, `UniformVerification`, `ClientVerification`,
and `fabric/build.gradle` (required result-file check). These classes remain excluded from the
production JAR. Crash Assistant remains a local test-client mod rather than a bundled dependency.

Before edits, 1,021 source entries were archived and read back, and the built mod copied to
`../../Iris-Vulkan-backups/before-phase5-20261026-125642/`, with SHA-256 manifest. Earlier backups
and crash evidence remain intact.
