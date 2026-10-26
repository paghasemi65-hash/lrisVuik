package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.vulkan.pack.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PackConfigurationTest {
    @TempDir Path game;
    @Test void perPackSettingsAndOffSurviveRestart() throws Exception {
        var config=new PackConfiguration(game);
        var a=new ShaderPackLoader.Selection("Pack A.zip","high",Map.of("QUALITY","2"));
        var b=new ShaderPackLoader.Selection("Pack B","low",Map.of("QUALITY","1"));
        config.save(new PackConfiguration.State(a,true));config.save(new PackConfiguration.State(b,true));
        assertEquals(a,config.forPack(a.pack()));assertEquals(b,config.read().selection());
        config.save(new PackConfiguration.State(a,false));
        var restored=new PackConfiguration(game).read();assertFalse(restored.enabled());
        assertEquals("",restored.selection().pack());assertEquals(a,restored.remembered());
    }
    @Test void migratesLegacyWithoutDeletingItOrLosingUnrelatedSettings() throws Exception {
        Files.createDirectories(game.resolve("config"));
        Path legacy=game.resolve("config/iris-vulkan.properties");
        Files.writeString(legacy,"pack=Example.zip\nprofile=high\noption.QUALITY=2\n");
        var config=new PackConfiguration(game);var state=config.read();config.save(state);
        assertEquals(state,config.read());assertTrue(Files.exists(legacy));
        Files.writeString(game.resolve("config/iris.properties"),"unrelated=value\n",StandardOpenOption.APPEND);
        config.save(new PackConfiguration.State(state.remembered(),false));
        assertTrue(Files.readString(game.resolve("config/iris.properties")).contains("unrelated=value"));
    }
    @Test void rejectsEscapingNamesBeforeWriting() throws Exception {
        var config=new PackConfiguration(game);
        for(String name:new String[]{"../escape","a/b","a\\b","C:escape",".."})
            assertThrows(java.io.IOException.class,()->config.save(new PackConfiguration.State(new ShaderPackLoader.Selection(name,"",Map.of()),true)));
        assertFalse(Files.exists(game.resolve("config/iris.properties")));
    }
}
