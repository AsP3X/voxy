package me.cortex.voxy.client.mixin.sodium.legacy;

import me.cortex.voxy.client.config.sodium06.VoxySodium06OptionPages;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(SodiumOptionsGUI.class)
public class MixinSodiumOptionsGUI {
    @Shadow
    @Final
    private List<OptionPage> pages;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$appendVoxyPage(Screen parent, CallbackInfo ci) {
        this.pages.add(VoxySodium06OptionPages.voxyPage());
    }
}
