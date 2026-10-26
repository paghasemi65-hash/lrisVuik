# Crash Assistant investigation — 2026-10-26

Installed [CrashAssistant-fabric-26.2-26.3-1.11.14.jar](https://www.curseforge.com/minecraft/mc-mods/crash-assistant/files/8934748)
in both local development client directories, `fabric/run/mods/` and
`fabric/run-verification/mods/`. Fabric Loader 0.19.5 discovered the mod and launched its
separate diagnostic process in the actual Minecraft 26.3 verification client. The normal
`runClient` directory has the same JAR installed but was not separately launched in this investigation.
The diagnostic mod is not included in the Iris distribution or Gradle dependencies.

Downloaded from the official CurseForge CDN:
`https://edge.forgecdn.net/files/8934/748/CrashAssistant-fabric-26.2-26.3-1.11.14.jar`.
SHA-256: `dd76ebfebbcc3d9457c7b7a37509ef5aeb0d69edc323060a8fc7c31b8d6f97dd`.
Its metadata requires Fabric Loader >=0.19.3, Minecraft >=26.2 and Java >=25.

## Observations

Both runs used Oracle Java 25.0.2, the existing full client verification harness,
Complementary Reimagined r5.9.3, and process-local Khronos core/synchronization validation.

1. `crash-assistant-oracle.log`: the client failed with a Java `IllegalStateException`
   in `VertexVerification.finish`, wrapped by `ClientVerification.frame` (exit -1).
   The combined assertion required terrain meshes, entity meshes, batched chunk draw capture,
   and GPU vertex readback. GPU readback had explicitly passed earlier in this run, but the
   assertion did not record individual counters, so the missing condition cannot be identified
   retrospectively. This was not the previously observed native `0xC0000005` failure.
   Crash Assistant detected the termination, opened its GUI, collected latest/debug logs and
   `crash-2026-10-26_12.48.28-client.txt`, and logged completion of its analysis.
   Its application log did not identify an additional specific crash cause.
2. `crash-assistant-oracle-repeat.log`: after adding failure diagnostics, the same workload
   passed and exited 0. Results: 64,512 terrain vertices, 17,856 entity vertices, 6,353 batched
   chunk draws, successful native GPU attribute readback, 1,597 compositor frames, seven
   resource generations, and zero pending retirements. Crash Assistant recognized normal
   shutdown. This is a successful repeat, not evidence that the intermittent failure is fixed.

Neither run reproduced the older native crash. Its cause remains unresolved; see
[PHASE4.md](PHASE4.md) and [DEBUGGING.md](DEBUGGING.md). No selected-pack world effects are
claimed: the benchmark result still records `worldEffectsRendered:false`.

## Diagnostic improvement and preserved evidence

Changed only development verification code:
`fabric/src/verification/java/net/irisshaders/iris/vulkan/verify/VertexVerification.java`.
It now writes the actual counters and `nativeGpuReadback` status to
`iris-phase4-vertices.json` before asserting, including when verification fails, and includes
the same values in the exception. All four original success requirements remain enforced.
The repeat client run recompiled and exercised this change successfully.

Prior logs and result files were preserved before running. Before the verification-code edit,
its source and the built Iris JAR were also copied alongside the first failure's evidence.
Local evidence directories (relative to the repository root):

- `reference/crash-assistant/20261026-124722/`: pre-investigation logs/results.
- `reference/crash-assistant/oracle-failure-20261026-124901/`: first crash, Crash Assistant
  application log, client logs/results, pre-edit verification source and Iris JAR.
- `reference/crash-assistant/oracle-pass-20261026-125056/`: successful repeat logs/results.

These generated files and the diagnostic mod remain in ignored local directories. No logs
were uploaded, no automatic fixes were applied, and no system Java/driver settings changed.
The production Iris JAR is unchanged, SHA-256:
`b52c7d584bfa87f29b713ea0c1cb03e0fe0a57a8765eb0cd489950b2356636f7`.

## Future local runs

The existing `runClient` / `runVerification` commands in [README.md](README.md) load the
installed diagnostic JAR automatically. On an unexpected exit, inspect the client directory's
`logs/crash_assistant/crash_assistant_app.log`, `logs/latest.log`, and newest `crash-reports/`
file. Preserve these before restarting because the next launch replaces current logs and may
close the preceding Crash Assistant window. A native failure may still provide no Minecraft
crash report or JVM fatal-error file; the existing native trace remains relevant in that case.
