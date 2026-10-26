package net.irisshaders.iris.vulkan.shader;

import org.lwjgl.system.MemoryStack;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.concurrent.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** One native compiler per job, bounded work queue, atomic and content-validated cache entries. */
public final class SpirvCompiler implements AutoCloseable {
    private static final int MAGIC = 0x07230203;
    private final Path cache;
    private final ExecutorService workers;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();
    private final String fingerprint;
    public enum Target {
        VULKAN_1_2(shaderc_env_version_vulkan_1_2, shaderc_spirv_version_1_5),
        VULKAN_1_3(shaderc_env_version_vulkan_1_3, shaderc_spirv_version_1_6);
        final int environment, spirv;
        Target(int environment, int spirv) { this.environment = environment; this.spirv = spirv; }
    }
    private final Target target;
    public SpirvCompiler(Path minecraftDirectory, int threads) throws IOException {
        this(minecraftDirectory, threads, Target.VULKAN_1_3);
    }
    public SpirvCompiler(Path minecraftDirectory, int threads, Target target) throws IOException {
        this.target = java.util.Objects.requireNonNull(target);
        if (threads < 1 || threads > 32) throw new IllegalArgumentException("Compiler threads");
        cache = minecraftDirectory.resolve("cache/iris_vulkan_spirv");
        Files.createDirectories(cache);
        workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), Thread.ofPlatform().daemon().name("iris-shaderc-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        try (var stack = MemoryStack.stackPush()) {
            var version = stack.mallocInt(1); var revision = stack.mallocInt(1);
            shaderc_get_spv_version(version, revision);
            fingerprint = ShaderVulkanTransformer.ABI_VERSION + ":lwjgl-3.4.3:shaderc:spv=" + version.get(0) + ":rev=" + revision.get(0) + ":" + target + ":debug:main";
        }
    }
    public CompletableFuture<byte[]> compile(ShaderStage stage, String sourceName, String source) {
        String key = sha256((fingerprint + "\0" + stage + "\0" + sourceName + "\0" + source).getBytes(StandardCharsets.UTF_8));
        var created = new CompletableFuture<byte[]>();
        var existing = pending.putIfAbsent(key, created);
        if (existing != null) return existing.thenApply(byte[]::clone);
        try {
            workers.execute(() -> {
                try { created.complete(loadOrCompile(key, stage, sourceName, source)); }
                catch (Throwable t) { created.completeExceptionally(t); }
                finally { pending.remove(key, created); }
            });
        } catch (RejectedExecutionException e) { pending.remove(key, created); created.completeExceptionally(e); }
        return created.thenApply(byte[]::clone);
    }
    private byte[] loadOrCompile(String key, ShaderStage stage, String name, String source) throws IOException {
        Path file = cache.resolve(key + ".spv");
        if (Files.isRegularFile(file)) {
            byte[] stored = Files.readAllBytes(file);
            if (stored.length >= 64) {
                byte[] bytes = java.util.Arrays.copyOfRange(stored, 32, stored.length);
                if (valid(bytes) && MessageDigest.isEqual(java.util.Arrays.copyOf(stored, 32), digest(bytes))) return bytes;
            }
        }
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("Cannot initialize shaderc");
        long options = 0, result = 0;
        byte[] bytes;
        try {
            options = shaderc_compile_options_initialize();
            if (options == 0) throw new IllegalStateException("Cannot initialize shaderc options");
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, target.environment);
            shaderc_compile_options_set_target_spirv(options, target.spirv);
            shaderc_compile_options_set_source_language(options, shaderc_source_language_glsl);
            shaderc_compile_options_set_generate_debug_info(options);
            // The CharSequence overload uses LWJGL's small thread-local stack. Real packs exceed it.
            var sourceBytes=org.lwjgl.system.MemoryUtil.memUTF8(source,false);
            var nameBytes=org.lwjgl.system.MemoryUtil.memUTF8(name);
            var entryBytes=org.lwjgl.system.MemoryUtil.memUTF8("main");
            try { result = shaderc_compile_into_spv(compiler, sourceBytes, stage.shadercKind, nameBytes, entryBytes, options); }
            finally { org.lwjgl.system.MemoryUtil.memFree(entryBytes);org.lwjgl.system.MemoryUtil.memFree(nameBytes);org.lwjgl.system.MemoryUtil.memFree(sourceBytes); }
            if (result == 0) throw new IllegalStateException("shaderc returned no result: " + name);
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalArgumentException(name + " [" + stage + "]\n" + shaderc_result_get_error_message(result));
            ByteBuffer nativeBytes = shaderc_result_get_bytes(result);
            bytes = new byte[nativeBytes.remaining()]; nativeBytes.get(bytes);
            if (!valid(bytes)) throw new IllegalStateException("Invalid SPIR-V from shaderc");
        } finally {
            if (result != 0) shaderc_result_release(result);
            if (options != 0) shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
        Path tmp = Files.createTempFile(cache, key, ".tmp");
        try {
            var stored = ByteBuffer.allocate(32 + bytes.length).put(digest(bytes)).put(bytes).array();
            Files.write(tmp, stored);
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
        return bytes;
    }
    private static boolean valid(byte[] bytes) {
        return bytes.length >= 20 && bytes.length % 4 == 0 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() == MAGIC;
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest(bytes)); }
    @Override public void close() { workers.close(); }
}
