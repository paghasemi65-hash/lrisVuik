# lrisVuik

Minecraft 26.3 Vulkan shader-engine development project, based on
[Iris Shaders](https://github.com/IrisShaders/Iris).

**Development snapshot: shaderpack world rendering is unfinished.** The current
Fabric and NeoForge builds launch and exercise the live Vulkan bridge, loader,
resource lifecycle and built-in compositor. Selected shaderpack world draws,
world shadow casters, full frame scheduling and the transparency/OIT handoff
still require implementation. Sodium shader rendering is not supported yet.
Compiling Complementary Reimagined does not establish playable shader effects.

## Source layout

- `vulkan/`: Minecraft 26.3 / Java 25 engine, Fabric and NeoForge adapters,
  unit/GPU tests, client verification harness and phase documentation.
- `common/`, `fabric/`, `neoforge/`: preserved upstream Iris OpenGL source;
  these root projects target the older upstream environment.
- `development-builds/`: current Fabric/NeoForge development JARs and checksums.
- [Vulkan build, installation and status](vulkan/README.md).
- [Latest implementation and validation report](vulkan/PHASE6.md).
- [Original upstream README and credits](README-UPSTREAM.md).

## Build the Vulkan mod

Use a Java 25 JDK and run from `vulkan/`:

```powershell
.\gradlew.bat -PwithFabric -PwithNeoForge build
.\gradlew.bat -PwithFabric -PwithNeoForge packageDevelopmentRelease
```

Linux/macOS use `./gradlew`. Native GPU/client and external shaderpack benchmark
checks are documented in the Vulkan README. The latest recorded local suite
passed 61 tests; this snapshot has not been newly rebuilt for repository upload.

Install only the matching loader's development JAR in a separate Minecraft 26.3
Vulkan profile. Do not install upstream Iris alongside it. See the Vulkan README
for tested loader versions, limitations and diagnostics.

## Provenance and licensing

Upstream source is preserved from Iris commit
`bff1e69cb6c5519d8745784aa9c8b92984de67e7`. Keep the upstream copyright notices,
[LICENSE](LICENSE) and [dependency license notice](LICENSE-DEPENDENCIES).
Development of the Vulkan module was assisted by OpenAI Codex.

This snapshot includes the complete project source, build scripts and tests.
Local Minecraft binaries, decompiled reference inputs, third-party shaderpacks,
world saves, caches and crash logs are excluded. Original Git history is not
included in the source snapshot. Existing upstream GitHub workflows are preserved;
their root builds concern upstream Iris, not the separate Vulkan module.
