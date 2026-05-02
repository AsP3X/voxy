# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Fixed
- **Render/Model**: Fixed depth blit scaling, CWB viewport, and color provider detection.
- **Iris/Render**: Fixed viewport mismatch and exposed pregen command client-side.
- **Iris/CWB**: Removed redundant `glViewport` override in `MixinLevelRenderer`.

### Changed
- **AsyncNodeManager**: Replaced `Thread.sleep(10)` with `LockSupport.parkNanos(100_000)` in the async worker thread (`AsyncNodeManager.run()`). This reduces latency from ~10 ms to ~0.1 ms per wakeup cycle, improving async mesh/node throughput without sacrificing the existing 300-item batching limits.

## [Recent History]

### Added
- **Worldgen**: NeoForge server worldgen worker, client LOD sync, and HUD overlay with dynamic enable/disable.
- **Commands**: `/voxy pregen stop|start|pause|resume` and region-based pregeneration CLI.
- **Render**: Hierarchical occlusion traversal with async node management and compute-based geometry uploads.

### Fixed
- **NeoForge**: Dedicated pregen, storage paths, and Sodium 0.6.x mixin compatibility.
- **Build**: JPMS conflicts with embedded sqlite-jdbc and LWJGL base exclusions.
- **LOD/Vanilla handoff**: Eliminated flicker by processing the ring queue before render and deferring chunk-bound additions.

---

*Last updated: 2026-05-01*
