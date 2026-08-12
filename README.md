# ChestLogger Mod for Fabric (Minecraft 26.2 / 1.20.6)

High-Performance HDD-Optimized Chest Inventory Logger and Rollback Engine for Fabric Minecraft servers.

## Key Features

- **Zero Main-Thread Blocking:** Inventory interaction events are enqueued 100% lock-free (`ConcurrentLinkedQueue`).
- **HDD Optimized:** Sequential LZ4-compressed binary frames (`.chlog`) written in batches to prevent seek storms.
- **Circuit Breaker Resilience:** Automatic writer disablement and dropped event counting on critical disk errors.
- **State-Delta Rollback Engine:** Preflight inventory validation, virtual array simulation, and compensating double-fault recovery.
- **Async Brigadier Commands:** Off-thread disk queries for `/chestlog inspect` and `/chestlog rollback`.

## CI / Building

This repository is configured with **GitHub Actions CI** (`.github/workflows/build.yml`). Building takes place on GitHub runners:

1. Push code to `main` or open a Pull Request.
2. GitHub Actions runs `./gradlew build` using Java 21 (Temurin JDK).
3. The compiled `.jar` artifact is published under the Action's **Artifacts** tab.
