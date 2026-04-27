package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "renderLevel", at = @At("HEAD"), order = 100)
    private void voxy$injectIrisCompat(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f modelViewMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        if (IrisUtil.irisShaderPackEnabled()) {
            var renderer = ((IGetVoxyRenderSystem) this).voxy$getRenderSystem();
            if (renderer != null) {
                // Only capture camera/matrix state for renderer lifecycle tracking.
                // The glViewport override that used to live here (using getMainRenderTarget()
                // dimensions) has been removed: setupViewport is now called at CUTOUT time, so
                // it reads whatever viewport Iris has already established for its render target.
                // Forcing the viewport to mainRenderTarget.height here caused a mismatch when
                // mods like CubesWithoutBorders set the window 1 px taller than the monitor
                // (e.g. height+1 on Windows) because Iris would subsequently set a viewport
                // based on its own render-target dimensions, not the inflated framebuffer size.
                var pos = camera.getPosition();
                // ChunkRenderMatrices: projection then model-view (same as pre-1.21 PoseStack.last().pose())
                IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = new IrisUtil.CapturedViewportParameters(
                        new ChunkRenderMatrices(projectionMatrix, modelViewMatrix), pos.x, pos.y, pos.z);
            }
        }
    }
}
