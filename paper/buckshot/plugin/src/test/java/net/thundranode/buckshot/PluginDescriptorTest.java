package net.thundranode.buckshot;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorTest {

    @Test
    void multiverseEstChargeAvantLaReconstructionDeLaTable() throws Exception {
        InputStream pluginYml = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        assertNotNull(pluginYml);

        PluginDescriptionFile description = new PluginDescriptionFile(pluginYml);

        assertTrue(description.getSoftDepend().contains("Multiverse-Core"),
                "la table ne peut pas être reconstruite avant le chargement des mondes Multiverse");
    }
}
