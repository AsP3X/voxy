# Plan: Replace GL11.glFinish with GPU Fence Polling

## Context
In `VoxyRenderSystem.frexStillHasWork()` (around line 458), a `GL11.glFinish()` call blocks the entire render thread until all previously submitted GPU commands complete. This is done to ensure correctness when FREX is active, but it serializes the CPU with the GPU every frame and can cause significant frame-time spikes.

## Proposed Change
Replace `GL11.glFinish()` with an OpenGL sync fence (`GL_SYNC_GPU_COMMANDS_COMPLETE`) and poll it with `glClientWaitSync` using a zero timeout. This allows the render thread to check whether GPU work is finished without blocking.

### Details
1. Create a `GLsync` object at the start of the frame or when needed.
2. In `frexStillHasWork()`, call `glClientWaitSync(sync, 0, 0)` instead of `glFinish()`.
3. If the fence is not yet signaled, return `true` (still has work) without blocking.
4. If the fence is signaled, delete it and continue.
5. Handle cleanup on shutdown / pipeline teardown.

### Risk / Trade-off
- **Reward:** Removes a full GPU-CPU serialization point, improving frame stability.
- **Risk:** The current code may rely on `glFinish()` implicitly for buffer reuse or state ordering. Need to verify that:
  - `modelService.tick()` and `UploadStream.INSTANCE.tick()` do not require completion.
  - No resources are read back on the CPU before the GPU is done.

### Files
- `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`
- Potentially `src/main/java/me/cortex/voxy/client/core/rendering/util/UploadStream.java`

### Next Step
Validate with GPU profiler that `glFinish` is indeed a hotspot, then implement the fence path behind a runtime toggle if needed.
