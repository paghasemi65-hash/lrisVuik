# Minecraft 26.3 API audit

Inspected on 2026-10-25 using the official unobfuscated client binary:

- Minecraft version metadata: `https://piston-meta.mojang.com/v1/packages/61fff1316bd50312624a858bbb5eb18fa45208ed/26.3.json`
- Client SHA-1, verified locally: `e877b6a07acd633fb3bb475002175cec036e7b87`
- Iris upstream commit: `bff1e69cb6c5519d8745784aa9c8b92984de67e7`
- [Official 26.3 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)
- [Sodium release notes](https://github.com/CaffeineMC/sodium/releases/tag/mc26.3-0.9.2)

The downloaded game and inspection sources are under ignored `../reference/`; they are not part
of the source or binary distribution.

## Verified bridge signatures

`com.mojang.renderpearl.backend.vulkan.VulkanDevice` owns:

```text
private final VulkanInstance instance
private final VkDevice vkDevice
private final long vma
private final VulkanQueue graphicsQueue
private final VulkanCommandEncoder commandEncoder
public void close()
```

`VulkanInstance.vkInstance()` exposes the LWJGL instance. `VkDevice.getPhysicalDevice()` supplies
the matching physical device. `VulkanQueue.queueFamilyIndex()` supplies the graphics queue family.

`VulkanCommandEncoder` owns:

```text
public static final int MAX_SUBMITS_IN_FLIGHT
private long currentSubmitIndex
private long completedSubmitIndex
private VkCommandBuffer currentCommandBuffer
private VulkanRenderPass currentRenderPass
private VkCommandBuffer commandBuffer()
public void queueForDestroy(Destroyable)
```

The audited 26.3 implementation has two submits in flight and chooses a command pool using
`currentSubmitIndex % 2`. Its submission code waits for the earlier submission before resetting
the reused command pool. The bridge reads the actual constant and counters instead of inventing
a frame counter from swapchain image indices. A swapchain image index is **not** a uniform-ring slot.

`VulkanGpuSurface` owns `swapchainImageFormat`, `swapchainWidth`, `swapchainHeight`, and
`currentImageIndex`; `acquireNextTexture()` is the capture point for a successfully acquired surface.

`VulkanFeatureSets.REQUIRED_FEATURESET` includes the dynamic rendering and synchronization2
feature structures. The host creates a Vulkan 1.2 instance/device with the KHR extensions enabled.
The bridge checks physical feature support AND the actual enabled feature set, then dispatches
KHR entry points for this device. A physical GPU advertising 1.3+ does not upgrade the host API.

## Phase 1 live hooks (verified 2026-10-26)

- `VulkanDevice.<init>` RETURN captures the device only after its encoder/pools exist.
- `VulkanCommandEncoder.submit()V` RETURN collects retired objects using `completedSubmitIndex`.
- `VulkanDevice.close()V` HEAD retires the engine before allocator destruction.
- `VulkanCommandEncoder.destroy()V`, immediately after `VulkanQueue.waitIdle()V`, drains the
  retirement queue and detaches the bridge before RenderPearl destroys the VMA allocator/device.
- `VulkanGpuSurface.<init>(VulkanDevice,long)` RETURN captures the real SDL window and VkSurfaceKHR.
  `acquireNextTexture()V` RETURN captures acquired format/extent/index and swapchain replacement.
- `GameRenderer.render()V`, before `FogRenderer.endFrame()V`, is after native world post-effects
  and before the depth clear and `GuiRenderer.render()V`.
- `GameRenderer.setLevel(ClientLevel)`, `resize(int,int)`, `onResourceManagerReload(ResourceManager)`
  and `close()` provide lifecycle boundaries. Reload callbacks request a reset; GPU mutation is
  deferred to the render thread.
- Main views are concrete `VulkanGpuTextureView` instances. Their textures have one layer/mip,
  render-attachment, sampled, copy-source and copy-destination usage. The observed color format
  is `VK_FORMAT_R8G8B8A8_UNORM`.
- RenderPearl uses `GENERAL` for native image operations/descriptors and rendering attachments.
  Iris temporarily transitions its borrowed images, then restores `GENERAL` using conservative
  ALL_COMMANDS / MEMORY_READ|MEMORY_WRITE dependencies on the same graphics queue.
- Native depth is reversed-Z (cleared to 0). Phase 1 preserves it; the older non-reversed
  `DepthConvention` helper is not applied to Minecraft's imported depth/projection.

## Audited but not hooked

Phase 2 additionally hooks `VulkanFeatureSets.optionalFeatureSets()Ljava/util/Set;` at RETURN.
It adds individual optional `FeatureSet` entries using `VK10_FEATURES_STRUCT` and actual field
names. RenderPearl checks physical support and enables the supported features during device
creation. The live log confirms the image-cube-array, storage-image, and descriptor indexing
feature requests were negotiated. No feature is merely assumed enabled from physical support.

`OptionsScreen.init()V` RETURN adds the native Shaderpacks button. The screen uses 26.3's
`GuiGraphicsExtractor` rendering API and `Minecraft.gui.setScreen`. Texture identifiers are read
through the active `ResourceManager.open(Identifier)`. Dimension selection uses the actual
`ClientLevel.dimension().identifier()`. Resolved frame fog color comes from
`GameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData.color`.

The following world routing entry points remain unhooked:

- `net.minecraft.client.renderer.oit.OitRenderPassProvider.createRenderPass(...)`
- `net.minecraft.client.renderer.oit.OitStage`: `DEPTH_BOUNDS`, `TRANSMITTANCE`, `ACCUMULATE`
- `net.minecraft.client.renderer.state.OptionsRenderState.improvedTransparency`
- `LevelRenderer` has OIT execution and main-pass scheduling methods; `GameRenderer` has the
  separate hand rendering path. These require coordinated replacement, not cancellation of the
  OIT composite alone.

No mixin targets a guessed `Blaze3DVulkan`, `RenderPearlContext`, or GLFW window method.
The existing SDL window/surface lifecycle remains owned by Minecraft.

## Phase 3 API audit

The same official 26.3 JAR was inspected directly. The following are real targets, all exercised
by the development client harness (not names inferred from earlier versions):

- `GameRenderer.renderLevel()V` invokes
  `ProjectionMatrixBuffer.getBuffer(Matrix4f):GpuBufferSlice` after applying bobbing/portal effects.
  `GameRendererMixin` captures that argument without replacing it. Frame view rotation comes from
  `gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix`.
- `Projection.getMatrix(Matrix4f)` swaps `zNear`/`zFar` and uses the backend's zero-to-one flag.
  The resulting reversed projection converts with `P_gl.z = P_host.w - 2 * P_host.z`.
  Native `VulkanRenderPass` uses a positive-height viewport. Imported depth requires `1 - depth`;
  the live conversion pass uses pixel coordinates, avoiding an implicit UV flip.
- `Camera.attributeProbe().getValue(EnvironmentAttributes.*, partialTick)` supplies celestial
  angles, moon phase, sky color and cloud height even when no sky draw is extracted.
  `SkyRenderState.skyColor` can be null during legitimate world frames; it is not an unconditional
  uniform source. Initial camera projections can also be non-finite, so the provider defers capture.
- `DynamicGpuData.writeTransform(Transform)`, `writeTransforms(Transform[])`,
  `writeTerrainTransform(Matrix4fc,int,int)` and `writeChunkSections(ChunkSectionInfo[])` expose the
  actual CPU values uploaded into returned buffer slices. `reset()` bounds their lifetime.
- Both `ProjectionMatrixBuffer.getBuffer(Matrix4f)` and `getBuffer(Projection)` are captured,
  including native cached projection reuse. `FrontendRenderPass.setUniform` captures real slices,
  views and samplers; `validateDraw(Collection,boolean,boolean)` correlates them at the draw boundary.
  These are observation hooks, not cancellations or replacements of the native draw.
- `GameRenderer.levelLightmap()` returns a `GpuTextureView` directly. The terrain atlas's real
  texture/view/sampler comes from `TextureManager.getTexture(TextureAtlas.LOCATION_BLOCKS)`.
  `VulkanGpuTextureView.baseMipLevel()/mipLevels()` define the imported view; the atlas includes mips.
- The official GLSL compiler injects `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`. Shaderpack preprocessing
  also exposes the specification's `RENDERPEARL_IS_ZERO_TO_ONE` compatibility macro; neither macro
  changes the need for explicit reversed-depth conversion.

Dependency audit: glsl-transformer 3.0.0-pre3's `Root.activeBuildRoots` is a **static deque**, not
thread-local. Concurrent background and render-thread AST construction corrupted stage ownership
in a live recreation test. `ShaderVulkanTransformer` now serializes AST work across instances;
native shaderc compilation remains independently asynchronous. A concurrent unit test guards this.

## Phase 4 API audit

Verified against the same actual 26.3 executable and exercised in the client:

- `DefaultVertexFormat.<clinit>` builder results 0, 2 and 3 are BLOCK, ENTITY and
  ENTITY_GLINT_SPECIAL. Each retains its original offsets with appended attributes. Native
  pipeline creation obtains the resulting actual stride; native shaders ignore unused attributes.
- `BufferBuilder.beginVertex():long` reserves its complete `vertexSize`. `endLastVertex():void`
  runs before the next reserve and on build. Quad backfilling uses the current last vertex pointer,
  not pointers saved before a possible native allocation move. Fast BLOCK/ENTITY writers still
  write their original prefix and use the extended size for reservation.
- `ModelBlockRenderer.tesselateBlock(BlockQuadOutput,float,float,float,BlockAndTintGetter,BlockPos,
  BlockState,BlockStateModel,long)` and `FluidRenderer.tesselate(BlockAndTintGetter,BlockPos,
  FluidRenderer.Output,BlockState,FluidState)` supply actual chunk emission context.
- `EntityRenderDispatcher.submit(EntityRenderState,CameraRenderState,double,double,double,
  PoseStack,SubmitNodeCollector)` and `BlockEntityRenderDispatcher.submit(BlockEntityRenderState,
  PoseStack,SubmitNodeCollector,CameraRenderState)` provide entity identity at feature submission.
  Model/BlockModel `Submit` records retain this context until their geometry preparation.
- `FrontendRenderPass.drawMultipleIndexed` calls each draw's uniform uploader, then directly invokes
  `RenderPassBackend.drawIndexed(IIIII)V`. It bypasses `FrontendRenderPass.drawIndexed` and validates
  only once before the loop. The earlier `validateDraw` observation point was insufficient. The
  new wrapper observes the actual backend call after each uploader; a development assertion checks
  every chunk batch against its draw count. Retained bindings are filtered by the active pipeline.
- `LevelRenderer.render` chooses between real `prepareChunkRenders` and
  `prepareChunkRendersIndirect`. The development harness alternates these paths; production selection
  remains unchanged. Native classic transparency follows opaque rendering inside the same active
  render pass, so future deferred/depth transfers must split that pass correctly.

These observations do not implement the missing world replacement or OIT bypass.
