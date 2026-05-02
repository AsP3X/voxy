# Voxy Mod Expansion Ideas

This document tracks potential areas for improvement and new features. Items are grouped by category and can be promoted to individual design specs when we decide to implement them.

---

## A) Rendering / Visual Quality
- Better fog blending at LOD transitions (reduce popping/harsh cutoffs)
- Smooth LOD transitions (morphing or cross-fading between detail levels)
- Distant lighting improvements (ambient occlusion for far chunks, global illumination approximation)
- Atmospheric scattering integration with Iris shaders
- Water rendering consistency across LOD boundaries
- Shadow handling for distant geometry

## B) Performance / Optimization
- Culling improvements (frustum culling enhancements, occlusion culling for distant LODs)
- Memory reduction (compressed chunk data, sparse storage, deduplication)
- Streaming pipeline improvements (async I/O prioritization, predictive loading)
- GPU buffer management (persistent mapped buffers, buffer recycling, draw indirect batching)
- CPU-side mesh building optimizations (faster greedy meshing, job scheduling)
- Reducing synchronization stalls between CPU and GPU
- LOD mesh cache eviction policies (LRU vs. distance-weighted)

## C) User Experience
- In-game config UI (integrated with NeoForge's config screen API or a custom overlay)
- Debug overlay improvements (memory usage graphs, LOD statistics, frame time breakdown)
- Keybinds for toggling LOD / debug modes / pregen controls
- Chat/command feedback improvements (progress bars, colored status messages)
- Onboarding / first-launch hints explaining LOD behavior

## D) Worldgen / Server Features
- Multiplayer server-side LOD generation (so clients don't need to build LOD themselves)
- Dedicated server pregen persistence across restarts
- Region-based pregen scheduling and prioritization
- LOD data sharing between connected players
- Server commands for admin LOD control

## E) Mod Compatibility
- Supporting more rendering mods beyond Sodium/Iris
- Integration with biome mod custom colors and blocks
- Structure mod compatibility (distant structure visibility)
- Custom block model LOD fallback handling
- Compatibility with worldgen mods that alter terrain shape

---

## How to Promote an Idea
1. Pick a category and specific item(s) from the list above.
2. Run through the brainstorming + design process to create a detailed spec.
3. Write the spec to `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`.
4. Get approval, then write an implementation plan.
