# Risk Analysis: GPU Sync Fence Polling (2026-05-01-gpu-sync-fence)

## Proposed Change
Replace `GL11.glFinish()` in `VoxyRenderSystem.frexStillHasWork()` (line 458) with an OpenGL sync fence (`GL_SYNC_GPU_COMMANDS_COMPLETE`) polled via `glClientWaitSync` with zero timeout.

## Current Behavior

`frexStillHasWork()` is called as the loop condition in `AbstractRenderPipeline.innerPrimaryWork()`:

```java
do {
    DownloadStream.INSTANCE.tick();
    this.nodeManager.tick(...);
    this.nodeCleaner.tick(...);
    glMemoryBarrier(...);
    this.traversal.doTraversal(viewport);
} while (this.frexStillHasWork.getAsBoolean());
```

Inside `frexStillHasWork()`:
1. `UploadStream.INSTANCE.tick()` — commits uploads, polls `GlFence` objects, frees allocations
2. `modelService.tick(100_000_000)` — processes model bakery texture/buffer uploads
3. `GL11.glFinish()` — **blocks render thread until ALL GPU work completes**
4. Returns CPU-side work state: `nodeManager.hasWork() || renderGen.getTaskCount()!=0 || !modelService.areQueuesEmpty()`

**Critical observation:** `glFinish()` does **not** affect the return value. The checks are purely CPU-side counters and atomic references. The `glFinish()` is a side effect that serializes the CPU with the GPU at the end of every loop iteration.

## Existing Synchronization Infrastructure

The codebase already uses proper fine-grained GPU synchronization:

- **`UploadStream`** — uses `GlFence` (wraps `GL_SYNC_GPU_COMMANDS_COMPLETE`) per-frame to track allocation lifetimes. Has its own `glFinish()` fallback when the buffer is exhausted (`rawUploadAddress()` retry loop).
- **`DownloadStream`** — uses `GlFence` for async CPU readback of GPU buffers.
- **`nodeManager.tick()`** — inserts `glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_UNIFORM_BARRIER_BIT)` after compute dispatches.
- **`AsyncNodeManager`** — worker thread blocks if both `RESULT_CACHE_1_HANDLE` and `RESULT_CACHE_2_HANDLE` are full (only 2 cached result slots), bounding how far ahead the CPU can run.
- **`traversal.doTraversal()`** — downloads the request queue via `DownloadStream` (async); results are processed in a later `DownloadStream.tick()`.

## Risk Assessment

### 1. Correctness Risk: LOW

**Why low:** None of the state queried by `frexStillHasWork()` depends on GPU completion.

- `nodeManager.hasWork()` checks `workCounter` and `RESULT_HANDLE` — both CPU atomics.
- `renderGen.getTaskCount()` is a CPU task queue counter.
- `modelService.areQueuesEmpty()` checks the model factory inflight count.

GPU resources are already protected:
- `UploadStream` allocations are fenced independently.
- `nodeManager.tick()` dispatches compute on fresh `SyncResults` data; the worker thread reuses buffers only after the render thread has consumed them (cache slot availability).
- `glMemoryBarrier` in `nodeManager.tick()` and `traversal.doTraversal()` ensures command-stream ordering for SSBO/texture access.

**Potential concern:** If FREX (or mods using it) implicitly rely on `glFinish()` to guarantee Voxy's GPU-side buffer updates are fully complete before FREX renders, removing it could cause transient visual corruption. However, `innerPrimaryWork` runs *during* Voxy's opaque render pass, not before/after FREX's pass. OpenGL command ordering within a single context should ensure visibility without `glFinish()`.

### 2. Performance Risk: MEDIUM

**GPU command queue bloat:** Without the per-iteration `glFinish()` throttle, `innerPrimaryWork` can loop many times back-to-back while the GPU is still executing previous iterations' compute dispatches and traversal shaders. This could queue up a large batch of GPU commands, increasing GPU latency and potentially causing frame time instability.

**Memory pressure:** Each loop iteration consumes a `SyncResults` object and uses `UploadStream` scratch space. While `AsyncNodeManager` caps cached results at 2 slots, the render thread consuming them faster than the GPU finishes could temporarily increase live memory. `UploadStream` has a 64MB buffer and a `glFinish()` fallback on exhaustion, but hitting that fallback would reintroduce the stalls this change aims to remove.

**Worker thread blocking:** If the render thread drains `RESULT_HANDLE` faster than the GPU completes work, the worker thread may hit the `needsWaitForSync` block more frequently. This is not a correctness issue but could reduce parallelism.

### 3. Semantic Risk: MEDIUM-HIGH

**The loop throttling behavior changes fundamentally.** Currently, `glFinish()` acts as a coarse frame-pacing mechanism: each iteration of `innerPrimaryWork` is gated by GPU completion. With fence polling that returns `true` when the fence is unsignaled, the loop is gated purely by CPU work availability.

If `nodeManager.hasWork()` stays true (worker thread producing results) while the GPU is saturated, the loop could iterate many times per actual display frame. This means Voxy might do multiple "logical update passes" within one frame before the GPU has caught up. The effect on frame time is unpredictable:
- **Best case:** CPU was the bottleneck; removing `glFinish()` improves latency with no downside.
- **Worst case:** GPU was the bottleneck; the CPU queues even more work, increasing submission overhead and total frame time.

**Mitigation:** The fence should not unconditionally return `true`. A safer design is:
- Insert a fence at the start of the first iteration (or after the first `glFinish()` replacement).
- In `frexStillHasWork()`, check the fence.
- If the fence is **not signaled**, still evaluate CPU work state. If CPU work state is `false`, return `false` (exit loop). If CPU work state is `true`, return `true` (continue looping, but note we're running ahead of GPU).
- If the fence **is signaled**, delete it and proceed normally.

This preserves the existing semantics for loop exit while removing the blocking behavior when the loop would have continued anyway.

### 4. Resource Leak / Cleanup Risk: LOW

**Fence lifecycle:** If `frexStillHasWork()` creates a `GLsync` object, it must be deleted when signaled or when the pipeline shuts down. Failure to delete fences could exhaust driver sync object pools.

- **Mitigation:** Store the fence in `VoxyRenderSystem` as a field, delete it on the next signaled poll or in `shutdown()`.

### 5. UploadStream Interaction Risk: LOW

`UploadStream.rawUploadAddress()` contains an emergency `glFinish()` + `tick()` retry loop when the upload buffer is full. If the fence-based path causes more frequent UploadStream exhaustion, the emergency `glFinish()` would still fire, preventing crashes but partially negating the optimization.

- **Likelihood:** Low in normal operation; the 64MB buffer is large relative to typical per-frame uploads.

## Risk Matrix

| Risk | Severity | Likelihood | Mitigation |
|------|----------|------------|------------|
| Correctness (visual corruption) | Medium | Low | Rely on existing `glMemoryBarrier` and `GlFence` infrastructure; validate with FREX active |
| Performance regression (GPU bloat) | Medium | Medium | Profile GPU queue depth; consider capping loop iterations per frame |
| Semantic change (loop throttle) | Medium | High | Fence should gate blocking, not loop exit logic |
| Resource leak (sync objects) | Low | Low | Store fence reference, delete on signal or shutdown |
| UploadStream exhaustion | Low | Low | Existing `glFinish()` fallback in `rawUploadAddress()` handles this |

## Recommended Validation Steps

1. **GPU Profiler:** Verify that `glFinish()` in `frexStillHasWork()` is indeed a hotspot with measurable frame-time spikes.
2. **FREX Smoke Test:** Run with FREX active (e.g. with Canvas or a FREX-based mod). Fly through world, load/unload chunks rapidly. Check for flickering, missing geometry, or LOD pop-in.
3. **GPU Queue Depth:** Profile the number of `innerPrimaryWork` loop iterations per frame before/after. If iterations increase >3x, the throttle was load-bearing.
4. **UploadStream Pressure:** Monitor if `rawUploadAddress()` hits its `glFinish()` fallback more frequently.
5. **Driver Validation:** Test on both NVIDIA and AMD (Mesa) drivers, as sync fence behavior can vary.

## Recommended Implementation Approach

1. Create a `GLsync fence` field in `VoxyRenderSystem`, initialized to `null`.
2. In `frexStillHasWork()`:
   - If `fence == null`, create it with `glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)`.
   - Poll with `glClientWaitSync(fence, 0, 0)`.
   - If signaled: `glDeleteSync(fence); fence = null;`.
   - Evaluate and return CPU work state as before.
3. In `shutdown()`: if `fence != null`, `glDeleteSync(fence)`.
4. **Do NOT** change the boolean return logic based on fence state alone.
5. Run behind a config toggle for initial rollout.

## Verdict

**Proceed with caution.** The correctness risk is low because the codebase already uses fine-grained barriers and fences. The main risk is a **semantic change to the `innerPrimaryWork` loop throttle**, which could cause the CPU to outrun the GPU and increase frame latency. The proposed change is viable if the fence is managed carefully (deleted on signal, not used to alter return logic) and validated with GPU profiling and FREX compatibility testing.
