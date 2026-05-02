# Design: GPU Sync Fence Polling for frexStillHasWork

**Date:** 2026-05-02
**Scope:** `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`
**Status:** Approved

## Problem

`VoxyRenderSystem.frexStillHasWork()` calls `GL11.glFinish()` at the end of every iteration of `AbstractRenderPipeline.innerPrimaryWork()`. This blocks the render thread until all previously submitted GPU commands complete, serializing CPU and GPU work every frame and causing frame-time spikes.

## Goal

Replace the blocking `glFinish()` with non-blocking OpenGL sync fence polling so the render thread can check GPU completion status without yielding. Preserve all existing CPU work-state logic exactly.

## Approach

### Single Stored Fence, Poll-and-Replace

Store one `GLsync` object in `VoxyRenderSystem`. The fence lifecycle is:

1. **Create:** A new fence is inserted via `glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)` at the end of `frexStillHasWork()` **only when** the method determines there is still CPU work remaining.
2. **Poll:** On the next call to `frexStillHasWork()`, if a fence exists, poll it with `glClientWaitSync(fence, 0, 0)` (zero timeout, non-blocking).
3. **Delete:** If the fence is signaled (`GL_ALREADY_SIGNALED` or `GL_CONDITION_SATISFIED`), delete it with `glDeleteSync` and clear the field.
4. **Cleanup:** If a fence survives until `shutdown()`, delete it there.

This ensures:
- The fence tracks only the GPU work dispatched **during** the loop iterations where work was present.
- When the loop exits (no more CPU work), no new fence is created, so there is no lingering sync object.
- The existing `glFinish()` behavior is replicated as a non-blocking poll: the loop still iterates while GPU work is pending, but the render thread is not blocked.

## Data Flow

```
innerPrimaryWork() loop iteration:
  nodeManager.tick()
  nodeCleaner.tick()
  glMemoryBarrier(...)
  traversal.doTraversal(viewport)
  frexStillHasWork()?  <-- query boundary

frexStillHasWork():
  if (fence != null):
    ret = glClientWaitSync(fence, 0, 0)
    if (ret == SIGNALED):
      glDeleteSync(fence)
      fence = null
  UploadStream.INSTANCE.tick()
  modelService.tick(100_000_000)
  stillHasWork = nodeManager.hasWork() || renderGen.getTaskCount()!=0 || !modelService.areQueuesEmpty()
  if (stillHasWork && fence == null):
    fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
  return stillHasWork
```

## Changes

### VoxyRenderSystem.java

Add fields:
```java
private GLsync gpuFence;
```

Modify `frexStillHasWork()`:
- Poll existing `gpuFence` at entry.
- Delete and clear if signaled.
- Keep existing `UploadStream.INSTANCE.tick()` and `modelService.tick()` calls.
- Keep existing boolean expression for return value.
- If returning `true` and `gpuFence == null`, create new fence.

Modify `shutdown()`:
- If `gpuFence != 0`, call `glDeleteSync(gpuFence)`.

### No other files changed

`UploadStream`, `DownloadStream`, `GlFence`, `AbstractRenderPipeline`, and `AsyncNodeManager` require no modifications.

## Error Handling

- `glFenceSync` can return `0` on failure. Treat as "no fence" and proceed without it (degrades to current CPU-only behavior).
- `glClientWaitSync` can return `GL_WAIT_FAILED`. Log an error and delete the fence to prevent repeated failure.
- `glDeleteSync` on `0` is a no-op in OpenGL; no special guard needed beyond a null check.

## Testing

1. **Functional:** Run with FREX inactive (`VoxyClient.isFrexActive() == false`). `frexStillHasWork()` should return `false` immediately, fence never created.
2. **Functional:** Run with FREX active, idle world. Loop should exit after one iteration; fence created but deleted on next frame before being polled.
3. **Stress:** Rapidly fly through world loading/unloading chunks with FREX active. Verify no visual corruption, flickering, or LOD pop-in.
4. **Performance:** Profile frame times with GPU profiler. Verify `glFinish()` stall is gone and frame-time spikes are reduced.
5. **Resource leak:** Run extended session (>30 min). Verify no sync object exhaustion or driver errors.

## Risks

See `2026-05-01-gpu-sync-fence-risk-analysis.md` for full risk assessment. Key mitigations:
- Correctness risk is low due to existing `GlFence`, `glMemoryBarrier`, and `RESULT_CACHE` synchronization.
- Semantic throttle change is acceptable because the fence still tracks GPU completion non-blockingly.
- Resource leak prevented by deleting signaled fences and cleanup in `shutdown()`.
