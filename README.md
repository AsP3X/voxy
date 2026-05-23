# Voxy

Voxy is a level-of-detail (LoD) rendering mod for Minecraft. It targets **NeoForge** on **Minecraft 1.21.1** (see `gradle.properties` for pinned versions).

Unofficial Discord for help: [https://discord.gg/6rH7nzmfg8](https://discord.gg/6rH7nzmfg8)

## Requirements

- **JDK 21** (the build uses a Java 21 toolchain; CI uses Eclipse Temurin 21)
- **Git** (optional but recommended: the build records the current short commit in mod metadata; without a repo you may see placeholder values)

Network access on the first build: Gradle will download the Minecraft/NeoForge toolchain, dependencies, and assets.

## Build

From the repository root:

**Windows (PowerShell or cmd)**

```bat
gradlew.bat build
```

**Linux / macOS**

```sh
./gradlew build
```

The mod JAR is written under `build/libs/`. On local builds, the file name may include a Git short hash as a classifier (e.g. `voxy-0.2.14-alpha-<hash>.jar`) when the project is a Git checkout.

**CI / automation:** GitHub Actions runs `./gradlew -I init.gradle build`. The `init.gradle` file only adjusts Gradle cache cleanup when `GITHUB_ACTIONS` is set; you do not need `-I init.gradle` for normal local development.

Other useful tasks:

- `gradlew clean` — delete `build/`
- `gradlew test` — run tests (also part of `build`)

## Run from source (development)

The Gradle setup includes NeoForge run configurations. After dependencies resolve, you can start a client or server with the mod on the runtime classpath:

**Windows**

```bat
gradlew.bat runClient
gradlew.bat runServer
```

**Linux / macOS**

```sh
./gradlew runClient
./gradlew runServer
```

`runClient` / `runServer` download game assets and launch Minecraft; the first run can take a while. For debugging outside Gradle, `gradlew createLaunchScripts` generates launcher scripts (see the task output for paths).

## Server deployment

On **Java 22+**, the JVM restricts native library access by default. If you see warnings like:

```
WARNING: java.lang.System::loadLibrary has been called by org.rocksdb.RocksDB in module voxy
WARNING: java.lang.System::loadLibrary has been called by net.jpountz.util.Native in module org.lz4.java
```

Add this JVM flag to your server startup script to allow the required native libraries to load:

```
--enable-native-access=voxy,org.lz4.java
```

Full example (Neoforge server):

```bash
java -Xmx4G --enable-native-access=voxy,org.lz4.java @libraries/net/neoforged/neoforge/21.1.228/unix_args.txt nogui
```

Without this flag, RocksDB and LZ4 will still work for now but may be blocked by a future JVM release.

## IDE

Import the folder as a Gradle project (IntelliJ IDEA, Eclipse, or VS Code with a Java/Gradle extension). The NeoForge plugin provides `neoForgeIdeSync` to generate files needed for IDE sync; your IDE’s Gradle import usually runs the equivalent steps automatically.

## License

See [LICENSE.md](LICENSE.md). Mod metadata in `gradle.properties` lists `mod_license=All-Rights-Reserved` unless the project changes it.