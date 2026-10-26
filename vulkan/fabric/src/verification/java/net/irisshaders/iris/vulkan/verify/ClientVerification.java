package net.irisshaders.iris.vulkan.verify;

import net.irisshaders.iris.vulkan.fabric.IrisVulkanLifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

/** Uses real world loading, SDL resize, resource reload, screenshot readback and client shutdown. */
public final class ClientVerification {
    private static final long START = System.nanoTime();
    private static int stage;
    private static long frame, checkpoint, generation;
    private static long framesBeforeReentry;
    private static String world;
    private static CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
    private static boolean finished;
    private static boolean packsVerified;
    public static void frame(Minecraft mc) {
        if (finished) return;
        if ((System.nanoTime() - START) / 1_000_000_000L > 600) throw new IllegalStateException("Iris client verification timed out at stage " + stage);
        if (!mc.isGameLoadFinished() || mc.gui.overlay() != null) return;
        frame++;
        var d = IrisVulkanLifecycle.diagnostics();
        require(d.attached() && !d.disabled(), "Live Vulkan bridge unavailable: " + d);
        require(d.sdlWindow() != 0 && d.surface() != 0 && d.commandPool() != 0 && d.queue() != 0, "Missing real native handles");
        UniformVerification.frame(mc);
        try {VertexVerification.frame(mc);}catch(Exception failure){throw new IllegalStateException(failure);}
        switch (stage) {
            case 0 -> {
                // The isolated harness may have been interrupted while a fixture pack was selected.
                try {var packs=net.irisshaders.iris.vulkan.fabric.LiveShaderPacks.active();if(packs!=null&&!packs.selection().pack().isEmpty())packs.select(new net.irisshaders.iris.vulkan.pack.ShaderPackLoader.Selection("","",java.util.Map.of()));}
                catch(Exception failure){throw new IllegalStateException("Could not reset verification pack selection",failure);}
                mc.options.pauseOnLostFocus = false; mc.options.renderDistance().set(4);
                mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
                world = "iris-phase1-" + System.currentTimeMillis();
                next();
                mc.createWorldOpenFlows().createFreshLevel(world,
                    new LevelSettings(world, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(263, false, false), WorldPresets::createTestWorldDimensions, new TitleScreen());
            }
            case 1 -> {
                if (mc.level == null || mc.gui.screen()!=null || d.renderedFrames() < 180) return;
                pending = screenshot(mc, "phase1-normal.png"); next();
            }
            case 2 -> {
                if (!pending.isDone()) return; pending.join();
                IrisVulkanLifecycle.exposure(0.25f); next();
            }
            case 3 -> {
                if (mc.gui.screen()!=null || frame - checkpoint < 30) return;
                pending = screenshot(mc, "phase1-dark.png"); next();
            }
            case 4 -> {
                if (!pending.isDone()) return; pending.join();
                compareScreenshots(mc);
                IrisVulkanLifecycle.exposure(1f);
                generation = d.generations(); mc.getWindow().setWindowed(1000, 700); next();
            }
            case 5 -> {
                if (d.generations() <= generation || frame - checkpoint < 60) return;
                generation = d.generations(); pending = mc.reloadResourcePacks(); next();
            }
            case 6 -> {
                if (!pending.isDone() || d.generations() <= generation || frame - checkpoint < 60) return;
                pending.join(); pending = screenshot(mc, "phase1-resized-reloaded.png"); next();
            }
            case 7 -> {
                if (!pending.isDone()) return; pending.join();
                generation = d.generations(); mc.disconnect(new TitleScreen(), false); next();
            }
            case 8 -> {
                if (mc.level != null || frame - checkpoint < 30) return;
                framesBeforeReentry = d.renderedFrames();
                next(); mc.createWorldOpenFlows().openWorld(world, () -> { throw new IllegalStateException("Could not reopen verification world"); });
            }
            case 9 -> {
                if (mc.level == null || d.generations() <= generation || d.renderedFrames() - framesBeforeReentry < 120) return;
                require(d.completedSubmission() > 2 && d.swapchain() != 0, "No real submission/presentation progress");
                next();
            }
            case 10 -> {
                IrisVulkanLifecycle.showNativeTextures(true);next();
            }
            case 11 -> {
                if(frame-checkpoint<30)return;
                pending=screenshot(mc,"phase3-native-textures.png");next();
            }
            case 12 -> {
                if(!pending.isDone())return;pending.join();IrisVulkanLifecycle.showNativeTextures(false);next();
            }
            case 13 -> {
                if(!packsVerified){if(!PackVerification.frame(mc,world))return;packsVerified=true;}
                try{if(!ControlsVerification.frame(mc))return;}catch(Exception failure){throw new IllegalStateException(failure);}
                try { UniformVerification.finish(mc);VertexVerification.finish(mc); } catch(Exception failure) { throw new IllegalStateException(failure); }
                next(); finished = true; mc.stop();
            }
            default -> throw new IllegalStateException("Verification stage " + stage);
        }
    }
    private static void next() {
        LoggerFactory.getLogger("IrisVerification").info("Phase 1 verification stage {} complete: {}", stage, IrisVulkanLifecycle.diagnostics());
        stage++; checkpoint = frame;
    }
    private static CompletableFuture<Void> screenshot(Minecraft mc, String name) {
        var result = new CompletableFuture<Void>();
        Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, message -> {
            if (Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots").resolve(name))) result.complete(null);
            else result.completeExceptionally(new IllegalStateException(message.getString()));
        });
        return result;
    }
    private static void compareScreenshots(Minecraft mc) {
        try {
            var dir = mc.gameDirectory.toPath().resolve("screenshots");
            var normal = ImageIO.read(dir.resolve("phase1-normal.png").toFile());
            var dark = ImageIO.read(dir.resolve("phase1-dark.png").toFile());
            require(normal != null && dark != null && normal.getWidth() == dark.getWidth(), "Invalid native screenshots");
            double before = 0, after = 0;
            for (int y = normal.getHeight()/4; y < normal.getHeight()/2; y++) for (int x = normal.getWidth()/4; x < normal.getWidth()*3/4; x++) {
                before += brightness(normal.getRGB(x,y)); after += brightness(dark.getRGB(x,y));
            }
            require(before > 0 && after / before < 0.7, "Final-pass exposure did not reach native screenshot: ratio=" + after/before);
            int scale = mc.getWindow().getGuiScale(), white = 0, unchanged = 0;
            for (int y = normal.getHeight()-22*scale; y < normal.getHeight(); y++)
                for (int x = normal.getWidth()/2-91*scale; x < normal.getWidth()/2+91*scale; x++) {
                    int a = normal.getRGB(x,y), b = dark.getRGB(x,y);
                    if ((a & 255) > 210 && ((a >>> 8) & 255) > 210 && ((a >>> 16) & 255) > 210) {
                        white++;
                        if (Math.abs((a & 255)-(b & 255)) < 5 && Math.abs(((a >>> 8)&255)-((b >>> 8)&255)) < 5
                            && Math.abs(((a >>> 16)&255)-((b >>> 16)&255)) < 5) unchanged++;
                    }
                }
            require(white > 30 && unchanged > white*0.95, "Hotbar was modified by world final pass: " + unchanged + "/" + white);
            LoggerFactory.getLogger("IrisVerification").info("HUD white border pixels preserved: {}/{}", unchanged, white);
            LoggerFactory.getLogger("IrisVerification").info("Native screenshot world brightness ratio={}", after/before);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static int brightness(int pixel) { return (pixel & 255) + ((pixel >>> 8) & 255) + ((pixel >>> 16) & 255); }
    public static void closed(Minecraft mc) {
        require(finished && stage == 14, "Client closed before verification finished");
        var d = IrisVulkanLifecycle.diagnostics();
        require(!d.attached() && d.retirements() == 0, "Device shutdown did not drain and detach: " + d);
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("iris-phase1-result.json"),
                "{\"passed\":true,\"stages\":10,\"renderedFrames\":" + d.renderedFrames() + ",\"generations\":" + d.generations() + "}");
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
