# GPU Sync Fence Polling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `GL11.glFinish()` in `VoxyRenderSystem.frexStillHasWork()` with non-blocking OpenGL sync fence polling while preserving all existing CPU work-state logic.

**Architecture:** Store a single `long gpuFence` field in `VoxyRenderSystem`. Poll it with `glClientWaitSync(..., 0)` on entry to `frexStillHasWork()`. Create a new fence via `glFenceSync` only when returning `true` (still has work). Delete the fence when signaled or during `shutdown()`.

**Tech Stack:** Java 21, LWJGL 3 (OpenGL 3.2+ sync objects), NeoForge 1.21.1

---

### Task 1: Add GPU fence field and imports to VoxyRenderSystem

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`

- [ ] **Step 1: Add GL32 sync imports**

Insert after the existing static GL11 imports (around line 47):

```java
import static org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL32.glClientWaitSync;
import static org.lwjgl.opengl.GL32.glDeleteSync;
import static org.lwjgl.opengl.GL32.glFenceSync;
```

- [ ] **Step 2: Add the gpuFence field**

Add the field inside the `VoxyRenderSystem` class, near the other instance fields (after `private final AbstractRenderPipeline pipeline;`):

```java
    private long gpuFence;
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java
git commit -m "feat(render): add gpuFence field and GL32 sync imports"
```

---

### Task 2: Implement fence poll-and-replace in frexStillHasWork()

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java:450-460`

- [ ] **Step 1: Replace glFinish with fence polling logic**

Replace the entire `frexStillHasWork()` method (lines 450-460):

```java
    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) {
            return false;
        }

        // Poll existing fence non-blockingly
        if (this.gpuFence != 0) {
            int ret = glClientWaitSync(this.gpuFence, 0, 0);
            if (ret == GL_ALREADY_SIGNALED || ret == GL_CONDITION_SATISFIED) {
                glDeleteSync(this.gpuFence);
                this.gpuFence = 0;
            } else if (ret < 0) {
                // GL_WAIT_FAILED or other error
                Logger.error("glClientWaitSync failed with " + ret + ", discarding fence");
                glDeleteSync(this.gpuFence);
                this.gpuFence = 0;
            }
        }

        //If frex is running we must tick everything to ensure correctness
        UploadStream.INSTANCE.tick();
        //Done here as is allows less gl state resetup
        this.modelService.tick(100_000_000);

        boolean stillHasWork = this.nodeManager.hasWork() || this.renderGen.getTaskCount()!=0 || !this.modelService.areQueuesEmpty();
        if (stillHasWork && this.gpuFence == 0) {
            this.gpuFence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (this.gpuFence == 0) {
                Logger.warn("glFenceSync returned 0, proceeding without GPU fence");
            }
        }
        return stillHasWork;
    }
```

- [ ] **Step 2: Remove unused glFinish import**

Delete this line from the imports:
```java
import static org.lwjgl.opengl.GL11.glFinish;
```

- [ ] **Step 3: Verify no other references to the removed import in this file**

Search the file for `glFinish`; the only remaining occurrence should be in the constructor at lines 104-105 (which is intentional and unrelated to `frexStillHasWork`).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java
git commit -m "feat(render): replace glFinish in frexStillHasWork with GPU fence polling"
```

---

### Task 3: Add fence cleanup in shutdown()

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java:495-531`

- [ ] **Step 1: Delete lingering gpuFence during shutdown**

Inside the `shutdown()` method, before the final `DownloadStream.INSTANCE.flushWaitClear()`, add:

```java
        if (this.gpuFence != 0) {
            glDeleteSync(this.gpuFence);
            this.gpuFence = 0;
        }
```

Place it after `this.viewportSelector.free();` and before `Logger.info("Flushing download stream");` (around line 518).

- [ ] **Step 2: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java
git commit -m "feat(render): cleanup gpuFence on render system shutdown"
```

---

### Task 4: Build verification

- [ ] **Step 1: Compile the project**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL with no compilation errors.

- [ ] **Step 2: Check for unused imports**

Verify the removed `glFinish` static import no longer causes warnings. If using an IDE or linter, confirm no unused-import warnings on `VoxyRenderSystem.java`.

- [ ] **Step 3: Commit (if build scripts or IDE metadata changed)**

If no file changes, skip.

---

### Task 5: Self-review checklist

- [ ] `gpuFence` is a `long` initialized to `0` (LWJGL `GLsync` handle type).
- [ ] `glClientWaitSync` is called with timeout `0` and no flags (`0`), ensuring non-blocking behavior.
- [ ] Fence is deleted when signaled (`GL_ALREADY_SIGNALED` or `GL_CONDITION_SATISFIED`).
- [ ] Fence is deleted on error (`ret < 0`) to prevent repeated failure.
- [ ] Fence is only created when `stillHasWork == true` and `gpuFence == 0`.
- [ ] `glFinish()` is removed from `frexStillHasWork()` but remains in the constructor (line 104-105) and is unrelated.
- [ ] `shutdown()` deletes any lingering fence.
- [ ] All existing CPU work-state logic (`nodeManager.hasWork()`, `renderGen.getTaskCount()`, `modelService.areQueuesEmpty()`) is preserved exactly.

---

## Self-Review

**1. Spec coverage:**
- Fence field and imports: Task 1
- Poll-and-replace in `frexStillHasWork()`: Task 2
- `shutdown()` cleanup: Task 3
- Build verification: Task 4
- All spec requirements addressed.

**2. Placeholder scan:**
- No TBD, TODO, or vague steps. Every step contains exact code and commands.

**3. Type consistency:**
- `gpuFence` is `long` everywhere (field, comparison to `0`, `glDeleteSync` parameter).
- `glClientWaitSync` return type is `int` (correct in LWJGL).
- `glFenceSync` returns `long` (correct).

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-02-gpu-sync-fence.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?