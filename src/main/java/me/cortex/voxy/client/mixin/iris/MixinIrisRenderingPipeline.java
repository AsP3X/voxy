package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline implements IGetVoxyPatchData, IGetIrisVoxyPipelineData {
    @Shadow @Final private CustomUniforms customUniforms;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;
    @Unique IrisShaderPatch patchData;
    @Unique
    IrisVoxyRenderPipelineData pipeline;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/transform/ShaderPrinter;resetPrintState()V", shift = At.Shift.AFTER))
    private void voxy$injectPatchDataStore(ProgramSet programSet, CallbackInfo ci) {
        if (IrisUtil.SHADER_SUPPORT) {
            this.patchData = ((IGetVoxyPatchData) programSet).voxy$getPatchData();
        }
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;createSetupComputes([Lnet/irisshaders/iris/shaderpack/programs/ComputeSource;Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;Lnet/irisshaders/iris/shaderpack/texture/TextureStage;)[Lnet/irisshaders/iris/gl/program/ComputeProgram;"))
    private void voxy$injectPipeline(ProgramSet programSet, CallbackInfo ci) {
        if (this.patchData != null) {
            this.pipeline = IrisVoxyRenderPipelineData.buildPipeline((IrisRenderingPipeline)(Object)this, this.patchData, this.customUniforms, this.shaderStorageBufferHolder);
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void voxy$destroyPipeline(CallbackInfo ci) {
        if (this.pipeline == null) {
            return;
        }
        if (Minecraft.getInstance().levelRenderer instanceof IGetVoxyRenderSystem rendererGetter) {
            var renderer = rendererGetter.voxy$getRenderSystem();
            if (renderer != null && renderer.isUsingPipelineData(this.pipeline)) {
                rendererGetter.voxy$shutdownRenderer();
            }
        }
        this.pipeline = null;
    }

    @Inject(method = "beginLevelRendering", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;activeTexture(I)V", shift = At.Shift.BEFORE), remap = false)
    private void voxy$injectViewportSetup(CallbackInfo ci) {
        // Only ensure the renderer exists here. Viewport dimensions are set up later in
        // MixinDefaultChunkRenderer.doRender (via setupViewport) so that they are read from
        // the GL viewport that Iris has already established for its render target, which is the
        // same viewport that renderOpaque will capture for dims. Calling setupViewport here
        // (before Iris sets its render-target viewport) caused viewport.width to exceed the
        // actual render-target width, producing scaleFactor > 1 in setup_stencil_depth and
        // incorrectly allowing Voxy to overwrite vanilla terrain across most of the screen.
        if (IrisUtil.CAPTURED_VIEWPORT_PARAMETERS != null) {
            var rendererGetter = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
            if (rendererGetter.voxy$getRenderSystem() == null && this.pipeline != null) {
                rendererGetter.voxy$createRenderer();
            }
        }
    }

    @Override
    public IrisShaderPatch voxy$getPatchData() {
        return this.patchData;
    }

    @Override
    public IrisVoxyRenderPipelineData voxy$getPipelineData() {
        return this.pipeline;
    }
}
