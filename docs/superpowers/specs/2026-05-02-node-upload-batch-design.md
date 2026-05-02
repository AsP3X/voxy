# Node GPU Upload Batching — Design Spec

**Date:** 2026-05-02
**Scope:** Performance optimization for `NodeManager.writeChanges()`
**Approach:** Range batching of dirty node uploads

---

## Problem

`NodeManager.writeChanges(GlBuffer nodeBuffer)` iterates over every dirty node ID in `nodeUpdates` and uploads each one individually via `UploadStream.INSTANCE.upload(...)` followed by a single `commit()`. Each upload enqueues a separate `glCopyNamedBufferSubData` call. With hundreds or thousands of dirty nodes per frame, this creates excessive driver overhead from micro-copies.

The existing code even notes this:

```java
//TODO: use like compute based copy system or something
// since microcopies are bad
```

## Solution Overview

Batch consecutive dirty node IDs into contiguous ranges and upload each range with a single `glCopyNamedBufferSubData`. Since each node is 16 bytes and node IDs map linearly to buffer offsets, spatially clustered updates become single large copies.

## Algorithm

1. Collect all dirty node IDs from `nodeUpdates` into an `int[]`.
2. Sort the array.
3. Walk the sorted array and group consecutive IDs into ranges:
   - A range starts at `startId` and extends while `nextId == currentId + 1`.
   - When the sequence breaks, emit a range `[startId, endId]` (inclusive).
4. For each range, compute:
   - Source offset in CPU staging buffer: `basePtr + startId * 16`
   - Dest offset in GPU buffer: `startId * 16L`
   - Size: `(endId - startId + 1) * 16L`
5. Write all nodes for the range into a single contiguous staging area via `UploadStream.rawUpload(size)`, copy node data there, then enqueue one `glCopyNamedBufferSubData` per range.
6. Clear `nodeUpdates`.

## Key Properties

- **Spatial locality wins:** Nodes that update in clusters (e.g., player movement, LOD expansion) produce long consecutive runs, dramatically reducing copy count.
- **Worst case unchanged:** If every dirty node is scattered (no two consecutive), we fall back to the same number of copies as today — no regression.
- **Buffer alignment safe:** Node size is 16 bytes and `UploadStream.BASE_ALLOCATION_ALIGNEMENT` is at least 16, so range allocations remain naturally aligned.

## Files to Modify

| File | Change |
|---|---|
| `NodeManager.java` | Rewrite `writeChanges()` to sort + batch ranges. Add private helper `writeNodeRange(...)` or inline the batch logic. |
| `NodeStore.java` | Add `writeNodeRange(long ptr, int startId, int count)` to write multiple nodes sequentially starting at `ptr`. |

### NodeStore Addition

```java
public void writeNodeRange(long ptr, int startId, int count) {
    for (int i = 0; i < count; i++) {
        this.writeNode(ptr + i * 16L, startId + i);
    }
}
```

This avoids per-node `upload()` calls and instead fills a single contiguous CPU-side block.

### NodeManager.writeChanges() Rewrite

```java
public boolean writeChanges(GlBuffer nodeBuffer) {
    if (this.nodeUpdates.isEmpty()) {
        return false;
    }

    // Collect and sort dirty node IDs
    int[] ids = this.nodeUpdates.toIntArray();
    Arrays.sort(ids);

    // Batch consecutive IDs into ranges
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

        long addr = UploadStream.INSTANCE.rawUploadAddress((int) size);
        long ptr = UploadStream.INSTANCE.getBaseAddress() + addr;
        this.nodeData.writeNodeRange(ptr, rangeStart, count);

        UploadStream.INSTANCE.commit(); // Or accumulate in a list and commit once
        glCopyNamedBufferSubData(
            UploadStream.INSTANCE.getRawBufferId(), nodeBuffer.id,
            addr, rangeStart * 16L, size
        );

        i++;
    }

    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    this.nodeUpdates.clear();
    return true;
}
```

*Note:* The exact integration with `UploadStream` needs care — we may want to accumulate all range uploads into a list and call `commit()` once at the end to preserve the existing pattern. Alternatively, since we are bypassing `UploadStream`'s per-upload tracking, we use `rawUploadAddress` + explicit `glCopyNamedBufferSubData` and a final barrier.

## Error Handling

- Empty `nodeUpdates`: returns `false` immediately (unchanged behavior).
- Invalid node IDs in `nodeUpdates`: `NodeStore.writeNode()` already handles non-existent nodes by writing `-1` sentinel values.
- Upload stream overflow: `rawUploadAddress` throws if the staging buffer cannot fit the range. Ranges are bounded by total dirty nodes; the current 64 MB staging buffer is more than sufficient.

## Testing Plan

1. **Unit test:** Create a `NodeManager` with a small `NodeStore`, mark a scattered set of nodes dirty, call `writeChanges()`, and verify the GPU buffer contains correct data at all marked offsets and unchanged data elsewhere.
2. **Unit test:** Mark a consecutive block of 100 nodes dirty and verify only one copy is issued (instrument via mock or debug logging).
3. **Integration test:** Run the mod in a world, fly around to trigger LOD updates, and verify no visual corruption or node state desync.
4. **Performance test:** Use RenderStatistics or manual GPU timer queries to compare frame time before/after under heavy node update load (e.g., fast flying, large render distance).

## Future Work

If profiling shows the upload path is still hot after this change, **Approach 3 (GPU compute scatter)** can replace range batching entirely. A compute shader would take a single staging buffer of `(nodeId, nodeData)` pairs and scatter them into the node buffer in one dispatch. This removes even the per-range copy overhead but adds shader complexity.

## Out of Scope

- Changes to `UploadStream` itself (e.g., coalescing in `commit()`).
- Changes to node update frequency or dirty marking logic.
- GPU-side persistence or persistent mapped buffer redesign.
