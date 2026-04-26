package me.cortex.voxy.server.worldgen;

import net.minecraft.world.level.ChunkPos;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

/**
 * track chunk generation state in a hierarchy
 * l0: 4x4 batch
 * l1: 8x8 l0 (32x32)
 * l2: 8x8 l1 (256x256)
 * l3: 8x8 l2 (2048x2048) -> entry point
 */
public class DistanceGraph {
    private static final int BATCH_SIZE_SHIFT = 2; // 4 chunks
    private static final int NODE_SIZE_BITS = 3;   // 8 nodes
    private static final int ROOT_SIZE_SHIFT = 9;  // 512 nodes
    
    private final Map<Long, Node> roots = new ConcurrentHashMap<>();

    private static class Node {
        final int level;
        final int x, z; // level-space coords
        volatile long fullMask = 0;
        final Map<Integer, Object> children = new ConcurrentHashMap<>();

        Node(int level, int x, int z) {
            this.level = level;
            this.x = x;
            this.z = z;
        }

        boolean isFull() { return fullMask == -1L; }
    }

    public void markChunkCompleted(int cx, int cz) {
        int bx = cx >> BATCH_SIZE_SHIFT;
        int bz = cz >> BATCH_SIZE_SHIFT;
        int bit = (cx & 3) + ((cz & 3) << 2);

        int rx = bx >> ROOT_SIZE_SHIFT;
        int rz = bz >> ROOT_SIZE_SHIFT;
        long rootKey = ChunkPos.asLong(rx, rz);

        Node root = roots.computeIfAbsent(rootKey, k -> new Node(3, rx, rz));
        recursiveMark(root, bx, bz, bit);
    }

    private void recursiveMark(Node node, int bx, int bz, int bit) {
        int idx = getLocalIndex(node.level, bx, bz);
        if ((node.fullMask & (1L << idx)) != 0) return;

        if (node.level == 1) {
            Integer mask = (Integer) node.children.getOrDefault(idx, 0);
            mask |= (1 << bit);
            if (mask == 0xFFFF) {
                synchronized(node) {
                    node.fullMask |= (1L << idx);
                    node.children.remove(idx);
                }
            } else {
                node.children.put(idx, mask);
            }
        } else {
            Node child = (Node) node.children.computeIfAbsent(idx, k -> {
                int cx = (node.x << NODE_SIZE_BITS) + (k & 0x7);
                int cz = (node.z << NODE_SIZE_BITS) + (k >> 3);
                return new Node(node.level - 1, cx, cz);
            });
            recursiveMark(child, bx, bz, bit);
            if (child.isFull()) {
                synchronized(node) {
                    node.fullMask |= (1L << idx);
                    node.children.remove(idx);
                }
            }
        }
    }

    public List<ChunkPos> findWork(ChunkPos center, int radiusChunks, Set<Long> trackedBatches) {
        int cbx = center.x >> BATCH_SIZE_SHIFT;
        int cbz = center.z >> BATCH_SIZE_SHIFT;
        int rb = (radiusChunks + 3) >> BATCH_SIZE_SHIFT;

        PriorityQueue<WorkItem> queue = new PriorityQueue<>(Comparator.comparingDouble(i -> i.distSq));

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        int rbxMin = (cbx - rb) >> ROOT_SIZE_SHIFT;
        int rbxMax = (cbx + rb) >> ROOT_SIZE_SHIFT;
        int rbzMin = (cbz - rb) >> ROOT_SIZE_SHIFT;
        int rbzMax = (cbz + rb) >> ROOT_SIZE_SHIFT;

        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = roots.get(ChunkPos.asLong(rx, rz));
                // check empty space even if node is null
                double dSq = getDistSq(rx, rz, rootSize, cbx, cbz);
                if (dSq <= (double)rb * rb) {
                    queue.add(new WorkItem(root, 3, rx, rz, dSq));
                }
            }
        }

        while (!queue.isEmpty()) {
            WorkItem item = queue.poll();
            if (item.node != null && item.node.isFull()) continue;

            if (item.level == 0) {
                // found a batch
                long key = ChunkPos.asLong(item.x, item.z);
                if (trackedBatches.add(key)) {
                    List<ChunkPos> batch = new ArrayList<>(16);
                    for (int lz = 0; lz < 4; lz++) {
                        for (int lx = 0; lx < 4; lx++) {
                            batch.add(new ChunkPos((item.x << 2) + lx, (item.z << 2) + lz));
                        }
                    }
                    return batch;
                }
                continue;
            }

            int childLevel = item.level - 1;
            int childSize = 1 << (3 * childLevel);
            
            for (int i = 0; i < 64; i++) {
                if (item.node != null && (item.node.fullMask & (1L << i)) != 0) continue;

                int cx = (item.x << 3) + (i & 7);
                int cz = (item.z << 3) + (i >> 3);
                
                double dSq = getDistSq(cx, cz, childSize, cbx, cbz);
                if (dSq <= (double)rb * rb) {
                    Object child = (item.node == null) ? null : item.node.children.get(i);
                    Node childNode = (child instanceof Node) ? (Node) child : null;
                    queue.add(new WorkItem(childNode, childLevel, cx, cz, dSq));
                }
            }
        }
        return null;
    }

    private double getDistSq(int nx, int nz, int size, int cbx, int cbz) {
        // distance to nearest edge of node
        double dx = Math.max(0, Math.max((double)nx * size - cbx, (double)cbx - (nx + 1) * size + 1));
        double dz = Math.max(0, Math.max((double)nz * size - cbz, (double)cbz - (nz + 1) * size + 1));
        return dx * dx + dz * dz;
    }

    private int getLocalIndex(int level, int bx, int bz) {
        int shift = (level - 1) * 3;
        int lx = (bx >> shift) & 7;
        int lz = (bz >> shift) & 7;
        return lx + (lz << 3);
    }

    /**
     * Count the number of <em>completed</em> chunks within the circular radius.
     */
    public int countCompletedInRange(ChunkPos center, int radiusChunks) {
        int cbx = center.x >> BATCH_SIZE_SHIFT;
        int cbz = center.z >> BATCH_SIZE_SHIFT;
        int rb  = (radiusChunks + 3) >> BATCH_SIZE_SHIFT;

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        int rbxMin = (cbx - rb) >> ROOT_SIZE_SHIFT;
        int rbxMax = (cbx + rb) >> ROOT_SIZE_SHIFT;
        int rbzMin = (cbz - rb) >> ROOT_SIZE_SHIFT;
        int rbzMax = (cbz + rb) >> ROOT_SIZE_SHIFT;

        int count = 0;
        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = roots.get(ChunkPos.asLong(rx, rz));
                count += recursiveCountCompleted(root, 3, rx, rz, cbx, cbz, rb);
            }
        }
        return count;
    }

    private int recursiveCountCompleted(Node node, int level, int nx, int nz, int cbx, int cbz, int rb) {
        int size = 1 << (3 * level);
        if (getDistSq(nx, nz, size, cbx, cbz) > (double) rb * rb) return 0;

        if (level == 0) {
            // batch-level: all 16 chunks in this batch cell are completed (we only reach level-0 via full mask)
            return 16;
        }

        if (node != null && node.isFull()) {
            // count all chunks in this subtree that are within range
            return recursiveCountAll(level, nx, nz, cbx, cbz, rb);
        }

        if (node == null) return 0; // nothing completed here

        // l1: check individual batch masks
        if (level == 1) {
            int c = 0;
            for (int i = 0; i < 64; i++) {
                int bx = (nx << 3) + (i & 7);
                int bz = (nz << 3) + (i >> 3);
                if (getDistSq(bx, bz, 1, cbx, cbz) > (double) rb * rb) continue;
                if ((node.fullMask & (1L << i)) != 0) {
                    c += 16;
                } else {
                    Object child = node.children.getOrDefault(i, 0);
                    if (child instanceof Integer mask) {
                        c += Integer.bitCount(mask);
                    }
                }
            }
            return c;
        }

        // higher levels: recurse
        int c = 0;
        for (int i = 0; i < 64; i++) {
            int cx = (nx << 3) + (i & 7);
            int cz = (nz << 3) + (i >> 3);
            if ((node.fullMask & (1L << i)) != 0) {
                int childSize = 1 << (3 * (level - 1));
                if (getDistSq(cx, cz, childSize, cbx, cbz) <= (double) rb * rb) {
                    c += recursiveCountAll(level - 1, cx, cz, cbx, cbz, rb);
                }
            } else {
                Object child = node.children.get(i);
                if (child instanceof Node childNode) {
                    c += recursiveCountCompleted(childNode, level - 1, cx, cz, cbx, cbz, rb);
                }
            }
        }
        return c;
    }

    /** Count all 16-chunk batches within range for a fully-complete subtree. */
    private int recursiveCountAll(int level, int nx, int nz, int cbx, int cbz, int rb) {
        int size = 1 << (3 * level);
        if (getDistSq(nx, nz, size, cbx, cbz) > (double) rb * rb) return 0;
        if (level == 0) return 16;
        int c = 0;
        for (int i = 0; i < 64; i++) {
            int cx = (nx << 3) + (i & 7);
            int cz = (nz << 3) + (i >> 3);
            c += recursiveCountAll(level - 1, cx, cz, cbx, cbz, rb);
        }
        return c;
    }

    public int countMissingInRange(ChunkPos center, int radiusChunks) {
        int cbx = center.x >> BATCH_SIZE_SHIFT;
        int cbz = center.z >> BATCH_SIZE_SHIFT;
        int rb = (radiusChunks + 3) >> BATCH_SIZE_SHIFT;

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        int rbxMin = (cbx - rb) >> ROOT_SIZE_SHIFT;
        int rbxMax = (cbx + rb) >> ROOT_SIZE_SHIFT;
        int rbzMin = (cbz - rb) >> ROOT_SIZE_SHIFT;
        int rbzMax = (cbz + rb) >> ROOT_SIZE_SHIFT;

        int count = 0;
        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = roots.get(ChunkPos.asLong(rx, rz));
                count += recursiveCount(root, 3, rx, rz, cbx, cbz, rb);
            }
        }
        return count;
    }

    public void collectCompletedInRange(ChunkPos center, int radiusChunks, it.unimi.dsi.fastutil.longs.LongSet alreadySynced, List<ChunkPos> out, int maxResults) {
        int cbx = center.x >> BATCH_SIZE_SHIFT;
        int cbz = center.z >> BATCH_SIZE_SHIFT;
        int rb = (radiusChunks + 3) >> BATCH_SIZE_SHIFT;

        // use a priority queue to process chunks from nearest to farthest
        PriorityQueue<CollectItem> queue = new PriorityQueue<>(Comparator.comparingDouble(i -> i.distSq));

        int rbxMin = (cbx - rb) >> ROOT_SIZE_SHIFT;
        int rbxMax = (cbx + rb) >> ROOT_SIZE_SHIFT;
        int rbzMin = (cbz - rb) >> ROOT_SIZE_SHIFT;
        int rbzMax = (cbz + rb) >> ROOT_SIZE_SHIFT;

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        double maxDistSq = (double) rb * rb;

        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = roots.get(ChunkPos.asLong(rx, rz));
                if (root == null) continue;
                
                double dSq = getDistSq(rx, rz, rootSize, cbx, cbz);
                if (dSq <= maxDistSq) {
                    queue.add(new CollectItem(root, false, 0, 3, rx, rz, dSq));
                }
            }
        }

        while (!queue.isEmpty() && out.size() < maxResults) {
            CollectItem item = queue.poll();
            
            if (item.level == 0) {
                // process batch
                int mask = item.isVirtualFull ? 0xFFFF : item.mask;
                for (int i = 0; i < 16; i++) {
                    if ((mask & (1 << i)) != 0) {
                        int lx = i & 3;
                        int lz = i >> 2;
                        ChunkPos pos = new ChunkPos((item.x << 2) + lx, (item.z << 2) + lz);
                        if (!alreadySynced.contains(pos.toLong())) {
                            out.add(pos);
                            if (out.size() >= maxResults) return;
                        }
                    }
                }
                continue;
            }
            
            // expand children
            int childLevel = item.level - 1;
            int childSize = 1 << (3 * childLevel);
            
            for (int i = 0; i < 64; i++) {
                int cx = (item.x << 3) + (i & 7);
                int cz = (item.z << 3) + (i >> 3);
                
                double dSq = getDistSq(cx, cz, childSize, cbx, cbz);
                if (dSq > maxDistSq) continue;
                
                if (item.isVirtualFull || (item.node != null && (item.node.fullMask & (1L << i)) != 0)) {
                   queue.add(new CollectItem(null, true, 0xFFFF, childLevel, cx, cz, dSq));
                   continue;
                }
                
                if (item.node == null) continue;
                Object child = item.node.children.get(i);
                if (child == null) continue;
                
                if (childLevel == 0) {
                    if (child instanceof Integer mask) {
                        queue.add(new CollectItem(null, false, mask, 0, cx, cz, dSq));
                    }
                } else if (child instanceof Node childNode) {
                    queue.add(new CollectItem(childNode, false, 0, childLevel, cx, cz, dSq));
                }
            }
        }
    }
    
    private record CollectItem(Node node, boolean isVirtualFull, int mask, int level, int x, int z, double distSq) {}

    private int recursiveCount(Node node, int level, int nx, int nz, int cbx, int cbz, int rb) {
        int size = 1 << (3 * level);
        if (getDistSq(nx, nz, size, cbx, cbz) > (double)rb * rb) return 0;
        if (node != null && node.isFull()) return 0;

        if (level == 0) return 1; // batch

        if (node == null) {
            // estimate chunks in circle inside empty node
            if (level == 1) {
                int c = 0;
                for (int i = 0; i < 64; i++) {
                    int bx = (nx << 3) + (i & 7);
                    int bz = (nz << 3) + (i >> 3);
                    if (getDistSq(bx, bz, 1, cbx, cbz) <= (double)rb * rb) c += 16;
                }
                return c;
            }
            // higher level, recurse null node
            int c = 0;
            for (int i = 0; i < 64; i++) {
                int cx = (nx << 3) + (i & 7);
                int cz = (nz << 3) + (i >> 3);
                c += recursiveCount(null, level - 1, cx, cz, cbx, cbz, rb);
            }
            return c;
        }

        // l1 partial
        if (level == 1) {
            int c = 0;
            for (int i = 0; i < 64; i++) {
                if ((node.fullMask & (1L << i)) != 0) continue;
                int bx = (nx << 3) + (i & 7);
                int bz = (nz << 3) + (i >> 3);
                if (getDistSq(bx, bz, 1, cbx, cbz) <= (double)rb * rb) {
                    Integer mask = (Integer) node.children.getOrDefault(i, 0);
                    c += (16 - Integer.bitCount(mask));
                }
            }
            return c;
        }

        // higher level partial
        int c = 0;
        for (int i = 0; i < 64; i++) {
            if ((node.fullMask & (1L << i)) != 0) continue;
            int cx = (nx << 3) + (i & 7);
            int cz = (nz << 3) + (i >> 3);
            Object child = node.children.get(i);
            Node childNode = (child instanceof Node) ? (Node) child : null;
            c += recursiveCount(childNode, level - 1, cx, cz, cbx, cbz, rb);
        }
        return c;
    }



    private static class WorkItem {
        final Node node;
        final int level;
        final int x, z;
        final double distSq;
        WorkItem(Node node, int level, int x, int z, double distSq) {
            this.node = node; this.level = level; this.x = x; this.z = z; this.distSq = distSq;
        }
    }

    // -------------------------------------------------------------------------
    // Square / AABB variants

    private static boolean nodeIntersects(int nx, int nz, int size, int minBx, int maxBx, int minBz, int maxBz) {
        return nx * size <= maxBx && nx * size + size - 1 >= minBx
            && nz * size <= maxBz && nz * size + size - 1 >= minBz;
    }

    /**
     * Find the nearest incomplete batch whose 4x4 chunk footprint intersects the
     * axis-aligned square {@code [minCx,maxCx] × [minCz,maxCz]} (chunk coords).
     * Only chunks that are strictly within the bounds are included in the returned
     * batch list, so boundary batches may contain fewer than 16 positions.
     */
    public List<ChunkPos> findWorkInBounds(int minCx, int minCz, int maxCx, int maxCz, Set<Long> trackedBatches) {
        int minBx = minCx >> BATCH_SIZE_SHIFT;
        int minBz = minCz >> BATCH_SIZE_SHIFT;
        int maxBx = maxCx >> BATCH_SIZE_SHIFT;
        int maxBz = maxCz >> BATCH_SIZE_SHIFT;
        int cbx   = (minBx + maxBx) >> 1;
        int cbz   = (minBz + maxBz) >> 1;

        PriorityQueue<WorkItem> queue = new PriorityQueue<>(Comparator.comparingDouble(i -> i.distSq));

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        int rbxMin = Math.floorDiv(minBx, rootSize);
        int rbxMax = Math.floorDiv(maxBx, rootSize);
        int rbzMin = Math.floorDiv(minBz, rootSize);
        int rbzMax = Math.floorDiv(maxBz, rootSize);

        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                if (!nodeIntersects(rx, rz, rootSize, minBx, maxBx, minBz, maxBz)) continue;
                Node root = roots.get(ChunkPos.asLong(rx, rz));
                queue.add(new WorkItem(root, 3, rx, rz, getDistSq(rx, rz, rootSize, cbx, cbz)));
            }
        }

        while (!queue.isEmpty()) {
            WorkItem item = queue.poll();
            if (item.node != null && item.node.isFull()) continue;

            if (item.level == 0) {
                long key = ChunkPos.asLong(item.x, item.z);
                if (trackedBatches.add(key)) {
                    List<ChunkPos> batch = new ArrayList<>(16);
                    for (int lz = 0; lz < 4; lz++) {
                        for (int lx = 0; lx < 4; lx++) {
                            int cx = (item.x << BATCH_SIZE_SHIFT) + lx;
                            int cz = (item.z << BATCH_SIZE_SHIFT) + lz;
                            if (cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz) {
                                batch.add(new ChunkPos(cx, cz));
                            } else {
                                // Out-of-bounds slot: mark complete so the graph node
                                // eventually becomes full and stops returning this batch.
                                markChunkCompleted(cx, cz);
                            }
                        }
                    }
                    if (!batch.isEmpty()) return batch;
                    trackedBatches.remove(key); // batch entirely outside bounds
                }
                continue;
            }

            int childLevel = item.level - 1;
            int childSize  = 1 << (3 * childLevel);
            for (int i = 0; i < 64; i++) {
                if (item.node != null && (item.node.fullMask & (1L << i)) != 0) continue;
                int cx = (item.x << 3) + (i & 7);
                int cz = (item.z << 3) + (i >> 3);
                if (!nodeIntersects(cx, cz, childSize, minBx, maxBx, minBz, maxBz)) continue;
                Object child = (item.node == null) ? null : item.node.children.get(i);
                Node childNode = (child instanceof Node n) ? n : null;
                queue.add(new WorkItem(childNode, childLevel, cx, cz, getDistSq(cx, cz, childSize, cbx, cbz)));
            }
        }
        return null;
    }

    /**
     * Count the number of incomplete chunks in the square region
     * {@code [minCx,maxCx] × [minCz,maxCz]} (chunk coords).
     */
    public int countMissingInBounds(int minCx, int minCz, int maxCx, int maxCz) {
        int minBx = minCx >> BATCH_SIZE_SHIFT;
        int minBz = minCz >> BATCH_SIZE_SHIFT;
        int maxBx = maxCx >> BATCH_SIZE_SHIFT;
        int maxBz = maxCz >> BATCH_SIZE_SHIFT;

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        int rbxMin = Math.floorDiv(minBx, rootSize);
        int rbxMax = Math.floorDiv(maxBx, rootSize);
        int rbzMin = Math.floorDiv(minBz, rootSize);
        int rbzMax = Math.floorDiv(maxBz, rootSize);

        int count = 0;
        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = roots.get(ChunkPos.asLong(rx, rz));
                count += recursiveCountBounds(root, 3, rx, rz, minBx, maxBx, minBz, maxBz, minCx, maxCx, minCz, maxCz);
            }
        }
        return count;
    }

    private int recursiveCountBounds(Node node, int level, int nx, int nz,
                                     int minBx, int maxBx, int minBz, int maxBz,
                                     int minCx, int maxCx, int minCz, int maxCz) {
        int size = 1 << (3 * level);
        if (!nodeIntersects(nx, nz, size, minBx, maxBx, minBz, maxBz)) return 0;
        if (node != null && node.isFull()) return 0;

        if (level == 1) {
            int c = 0;
            for (int i = 0; i < 64; i++) {
                if (node != null && (node.fullMask & (1L << i)) != 0) continue;
                int bx = (nx << 3) + (i & 7);
                int bz = (nz << 3) + (i >> 3);
                // batch (bx,bz) covers chunks [bx*4, bx*4+3] × [bz*4, bz*4+3]
                int bMinCx = bx << BATCH_SIZE_SHIFT;
                int bMaxCx = bMinCx + 3;
                int bMinCz = bz << BATCH_SIZE_SHIFT;
                int bMaxCz = bMinCz + 3;
                int aMinCx = Math.max(bMinCx, minCx);
                int aMaxCx = Math.min(bMaxCx, maxCx);
                int aMinCz = Math.max(bMinCz, minCz);
                int aMaxCz = Math.min(bMaxCz, maxCz);
                if (aMinCx > aMaxCx || aMinCz > aMaxCz) continue;
                int completedMask = (node == null) ? 0 : (int) node.children.getOrDefault(i, 0);
                for (int lz = aMinCz - bMinCz; lz <= aMaxCz - bMinCz; lz++) {
                    for (int lx = aMinCx - bMinCx; lx <= aMaxCx - bMinCx; lx++) {
                        if ((completedMask & (1 << (lx + (lz << 2)))) == 0) c++;
                    }
                }
            }
            return c;
        }

        // node == null means all space in this region is ungenerated — count chunks
        if (node == null) {
            if (level == 2) {
                // Recurse to level-1 null nodes
                int c = 0;
                for (int i = 0; i < 64; i++) {
                    int cx = (nx << 3) + (i & 7);
                    int cz = (nz << 3) + (i >> 3);
                    c += recursiveCountBounds(null, 1, cx, cz, minBx, maxBx, minBz, maxBz, minCx, maxCx, minCz, maxCz);
                }
                return c;
            }
            // level 3+: recurse through null
            int c = 0;
            for (int i = 0; i < 64; i++) {
                int cx = (nx << 3) + (i & 7);
                int cz = (nz << 3) + (i >> 3);
                c += recursiveCountBounds(null, level - 1, cx, cz, minBx, maxBx, minBz, maxBz, minCx, maxCx, minCz, maxCz);
            }
            return c;
        }

        // partial non-null higher-level node
        int c = 0;
        for (int i = 0; i < 64; i++) {
            if ((node.fullMask & (1L << i)) != 0) continue;
            int cx = (nx << 3) + (i & 7);
            int cz = (nz << 3) + (i >> 3);
            Object child = node.children.get(i);
            Node childNode = (child instanceof Node n) ? n : null;
            c += recursiveCountBounds(childNode, level - 1, cx, cz, minBx, maxBx, minBz, maxBz, minCx, maxCx, minCz, maxCz);
        }
        return c;
    }

    public static long getBatchKey(int cx, int cz) {
        return ChunkPos.asLong(cx >> 2, cz >> 2);
    }
}
