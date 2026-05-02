# Node GPU Upload Batching — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate per-node GPU micro-copies in `NodeManager.writeChanges()` by batching consecutive dirty node IDs into range uploads.

**Architecture:** Sort the dirty node ID set, walk it to find consecutive runs, and upload each run as a single `glCopyNamedBufferSubData` via `UploadStream`. Add a `NodeStore.writeNodeRange()` helper to fill a contiguous native memory block with multiple nodes.

**Tech Stack:** Java 21, LWJGL, OpenGL 4.5+ DSA, FastUtil `IntOpenHashSet`

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeStore.java` | Add `writeNodeRange(...)` to write N nodes sequentially into a native memory pointer. |
| `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java` | Rewrite `writeChanges(...)` to sort dirty IDs, batch consecutive ranges, issue fewer GPU copies. |

---

## Task 1: Add `writeNodeRange` to `NodeStore`

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeStore.java:306`

- [ ] **Step 1: Add the `writeNodeRange` method**

Insert the following method into `NodeStore.java` immediately after the existing `writeNode(long ptr, int nodeId)` method (around line 306):

```java
public void writeNodeRange(long ptr, int startId, int count) {
    for (int i = 0; i < count; i++) {
        this.writeNode(ptr + i * 16L, startId + i);
    }
}
```

- [ ] **Step 2: Verify the project compiles**

Run:
```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeStore.java
git commit -m "feat(render): add NodeStore.writeNodeRange for batched node writes"
```

---

## Task 2: Rewrite `NodeManager.writeChanges()` for range batching

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java:1348-1357`

- [ ] **Step 1: Add `Arrays` import**

Add the following import near the top of `NodeManager.java` (after the existing `java.util.List` import):

```java
import java.util.Arrays;
```

- [ ] **Step 2: Replace `writeChanges` implementation**

Locate the existing `writeChanges` method in `NodeManager.java` (lines 1348–1357):

```java
public boolean writeChanges(GlBuffer nodeBuffer) {
    //TODO: use like compute based copy system or something
    // since microcopies are bad
    if (this.nodeUpdates.isEmpty()) {
        return false;
    }
    this.nodeUpdates.forEach((int i) -> this.nodeData.writeNode(UploadStream.INSTANCE.upload(nodeBuffer, i*16L, 16L), i));
    this.nodeUpdates.clear();
    return true;
}
```

Replace it entirely with:

```java
public boolean writeChanges(GlBuffer nodeBuffer) {
    if (this.nodeUpdates.isEmpty()) {
        return false;
    }

    int[] ids = this.nodeUpdates.toIntArray();
    Arrays.sort(ids);

    int i = 0;
    while (i < ids.length) {
        int rangeStart = ids[i];
        int rangeEnd = rangeStart;
        while (i + 1 < ids.length && ids[i + 1] == rangeEnd + 1) {
            rangeEnd++;
            i++;
        }
        int count = rangeEnd - rangeStart + 1;
        long size = count * 16L;

        long ptr = UploadStream.INSTANCE.upload(nodeBuffer, rangeStart * 16L, size);
        this.nodeData.writeNodeRange(ptr, rangeStart, count);

        i++;
    }

    UploadStream.INSTANCE.commit();
    this.nodeUpdates.clear();
    return true;
}
```

- [ ] **Step 3: Verify the project compiles**

Run:
```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java
git commit -m "feat(render): batch consecutive node uploads in writeChanges"
```

---

## Task 3: Build Verification

**Files:**
- None (runtime verification)

- [ ] **Step 1: Full build**

Run:
```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL` with tests passing.

---

## Self-Review

**1. Spec coverage:**
- Range batching algorithm → Task 2 implements it exactly.
- `writeNodeRange` in `NodeStore` → Task 1.
- Single `glCopyNamedBufferSubData` per consecutive range → Task 2 (via `UploadStream.upload` batching + single `commit`).
- Empty `nodeUpdates` early return → Task 2 preserves it.
- Buffer alignment safe → `UploadStream` already aligns allocations to `BASE_ALLOCATION_ALIGNEMENT >= 16`; node size is 16.

**2. Placeholder scan:**
- No "TBD", "TODO", "implement later", or vague instructions remain.
- Every step has exact file path and code.
- No references to undefined types or methods.

**3. Type consistency:**
- `writeNodeRange(long ptr, int startId, int count)` matches usage in Task 2.
- `NodeStore.writeNode(long ptr, int nodeId)` signature is unchanged.
- `UploadStream.upload(GlBuffer, long, long)` returns `long` (native address to write to), matching usage.

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **Sorting overhead** — `Arrays.sort(ids)` is O(n log n). If the dirty set is large/scattered, CPU cost may negate GPU savings. | Medium | Fast-path: if `nodeUpdates.size() < 8`, skip sorting and fall back to the old per-node loop. |
| **Allocation pressure** — `toIntArray()` allocates a new `int[]` every call. Frequent calls create GC churn. | Medium | Consider pooling the array or switching dirty tracking to `IntArrayList` to reuse storage. |
| **Scattered IDs** — If dirty nodes are sparse, range-finding produces many single-node ranges. We pay sort+alloc cost for no gain. | High | Measure before merging. If real workloads are mostly sparse, this optimization is a net loss. |
| **UploadStream.commit() timing** — Original code had implicit per-upload behavior. Batching to a single commit may delay buffer recycling or fence signaling. | Medium | Verify `UploadStream` tolerates fewer commits per frame. If not, commit per range instead of once at the end. |
| **Buffer bounds / alignment** — A large consecutive range might span an `UploadStream` chunk boundary. `writeNodeRange` would write past the mapped pointer. | Low | `UploadStream` is expected to handle large allocations; add an assertion or split ranges that exceed a chunk threshold. |
| **Concurrent modification** — `nodeUpdates` could be populated by another thread during `writeChanges()`. Snapshotting + clearing widens the race window. | Low | Ensure callers hold the same lock for add+write, or switch to a double-buffered dirty set. |

**Mitigation summary:** Add a small-size fast-path fallback, and benchmark frame times on representative scenes before merging.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-02-node-upload-batch.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
