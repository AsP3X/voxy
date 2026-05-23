package me.cortex.voxy.client.mixin;

import net.neoforged.fml.loading.FMLLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import me.cortex.voxy.common.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean sodiumLegacy = true;
    /** Sodium 0.6 {@code SodiumOptionsGUI} without Caffeine 0.8 {@code VideoSettingsScreen}. */
    private static boolean sodiumLegacyOptionsGui;
    private boolean valkyrienSkiesInstalled;
    private boolean nvidiumInstalled;
    private boolean sodiumInstalled;
    private boolean irisInstalled;

    private static boolean modOnLoadingList(String id) {
        try {
            var ll = FMLLoader.getLoadingModList();
            if (ll == null) {
                return false;
            }
            for (var m : ll.getMods()) {
                if (id.equals(m.getModId())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = modOnLoadingList("valkyrienskies");
        nvidiumInstalled = modOnLoadingList("nvidium");
        sodiumInstalled = modOnLoadingList("sodium");
        irisInstalled = modOnLoadingList("iris");
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class")) {

            if (stream != null) {
                ClassReader reader = new ClassReader(stream);
                ClassNode node = new ClassNode();
                reader.accept(node, 0);

                for (MethodNode method : node.methods) {
                    if (method.name.equals("drawChunkLayer") && method.desc.contains("ChunkRenderMatrices")) {
                        sodiumLegacy = false;
                        break;
                    }
                }
            } else {
                Logger.error("SodiumWorldRenderer class not found");
            }
        } catch (Exception e) {
            Logger.error(e);
        }

        // Do not use Class.forName on Sodium GUI classes here — that loads the class while mixins are
        // still preparing and breaks Iris (MixinTargetAlreadyLoadedException on SodiumOptionsGUI).
        ClassLoader cl = ClientVoxyMixinPlugin.class.getClassLoader();
        sodiumLegacyOptionsGui = false;
        if (sodiumInstalled) {
            boolean hasModern = cl.getResource("net/caffeinemc/mods/sodium/client/gui/VideoSettingsScreen.class") != null;
            boolean hasLegacy = cl.getResource("net/caffeinemc/mods/sodium/client/gui/SodiumOptionsGUI.class") != null;
            sodiumLegacyOptionsGui = !hasModern && hasLegacy;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("sodium.legacy.MixinSodiumOptionsGUI")) {
            return sodiumInstalled && sodiumLegacyOptionsGui;
        }
        if (mixinClassName.contains(".sodium.") && !sodiumInstalled) {
            return false;
        }
        if (mixinClassName.contains(".iris.") && !irisInstalled) {
            return false;
        }
        return true;
    }

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (!sodiumInstalled) {
            return mixins;
        }
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add(sodiumLegacy ? "sodium.MixinSodiumWorldRendererVSLegacy" : "sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }

        if (sodiumLegacyOptionsGui) {
            mixins.add("sodium.legacy.MixinSodiumOptionsGUI");
        }

        return mixins;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
