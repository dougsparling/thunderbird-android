# Building with Mainland-China Mirrors

Google Maven, Maven Central, the Gradle Plugin Portal, and `services.gradle.org` are slow or unreachable from
mainland China. The build can optionally resolve dependencies, plugins, and the Gradle distribution through
mirrors in China instead. A single switch controls this, and it's **off by default**. With the switch off, the build
behaves exactly as before.

## Turning it on

Set `useChinaMirrors=true` in one of these places:

- **This checkout only:** `local.properties` in the project root. Git ignores this file, and Android Studio creates it
  to store `sdk.dir`, so the setting also applies to builds and syncs in Android Studio.
- **All projects:** your user-level `~/.gradle/gradle.properties`.
- **One build:** `./gradlew assembleDebug -PuseChinaMirrors=true`
- **Environment variable:** `USE_CHINA_MIRRORS=true`

If more than one is set, the Gradle property (`-P` or `~/.gradle/gradle.properties`) wins, then the environment
variable, then `local.properties`.

Do **not** add `useChinaMirrors=true` to the project's `gradle.properties`. That would route everyone's build through
the mirrors, including contributors outside China.

The switch is read through Gradle providers, so it works with the configuration cache. Changing it invalidates the
cached configuration as expected.

## Mirror list

When enabled, these repositories go **before** the official ones, in this order:

| # |                             Repository                             |         Mirrors          |         Used for         |
|---|--------------------------------------------------------------------|--------------------------|--------------------------|
| 1 | `https://maven.aliyun.com/repository/google`                       | Google Maven             | plugins and dependencies |
| 2 | `https://maven.aliyun.com/repository/gradle-plugin`                | Gradle Plugin Portal     | plugin resolution only   |
| 3 | `https://maven.aliyun.com/repository/public`                       | Maven Central + JCenter  | plugins and dependencies |
| 4 | `https://mirrors.cloud.tencent.com/nexus/repository/maven-public/` | Central, Google (backup) | plugins and dependencies |

The official repositories (`google()`, `mavenCentral()`, `gradlePluginPortal()`) and the project-specific ones
(Mozilla Maven, JitPack, and the Sonatype snapshots repository) stay in place, in their original order, after the
mirrors. They act as the last resort for anything the mirrors don't have.

The mirrors come first because Gradle tries repositories in order. If a blocked host came first, every lookup that
misses there would wait for a network timeout.

A few details:

- The Aliyun Google mirror is limited to the `androidx`, `com.android`, and `com.google` groups, the same filter the
  project already applies to `google()`.
- All mirrors are `releasesOnly()`. Snapshot dependencies still come from the Sonatype snapshots repository.
- The mirrors exclude the groups that are routed to Mozilla Maven or JitPack (`org.mozilla.components`,
  `org.mozilla.telemetry`, `com.github.cketti`, `com.github.ByteHamster`). Some mirrors hold incomplete copies of
  those artifacts, for example a POM without its jar. Gradle downloads artifacts from the repository it found the
  metadata in, so a partial copy would break the build. If you add a dependency from a new dedicated repository,
  add its group to `dedicatedRepositoryGroups` in `gradle/china-mirrors.settings.gradle.kts` as well.
- The logic lives in one place, `gradle/china-mirrors.settings.gradle.kts`. The settings files of the main build,
  `build-plugin`, and `components` apply it at the top of their `pluginManagement {}` block.
- `build-plugin` depends on Gradle plugins as regular dependencies (plugin marker artifacts such as
  `com.diffplug.spotless:com.diffplug.spotless.gradle.plugin`). Those exist only on the Plugin Portal, so
  `build-plugin` also uses the Aliyun gradle-plugin mirror for dependency resolution. It opts in by setting
  `settings.extra["chinaMirrors.pluginMirrorForDependencies"] = true` before applying the script. There, the
  gradle-plugin mirror comes last among the mirrors, so large Central artifacts (like the Kotlin Gradle plugin) aren't
  fetched through the Plugin Portal proxy.
- Aliyun gradle-plugin is the only mirror in this list with Plugin Portal content. The Tencent mirror doesn't serve
  plugin marker artifacts.

## Gradle wrapper

The wrapper downloads the Gradle distribution from `distributionUrl` before Gradle starts, so a Gradle property
can't redirect it. The committed `gradle/wrapper/gradle-wrapper.properties` keeps pointing at `services.gradle.org`.

Instead, run this once whenever the Gradle version changes:

```shell
scripts/gradle-wrapper-mirror.sh
```

The script:

1. Reads `distributionUrl` and `distributionSha256Sum` from `gradle/wrapper/gradle-wrapper.properties`.
2. Downloads the same distribution file from `https://mirrors.cloud.tencent.com/gradle/`, falling back to
   `https://mirrors.huaweicloud.com/gradle/`.
3. Checks the SHA-256 checksum against `distributionSha256Sum` and aborts on a mismatch.
4. Places the zip where the wrapper looks for it,
   `~/.gradle/wrapper/dists/<distribution>/<hash of distributionUrl>/` (respects `GRADLE_USER_HOME`).

The next `./gradlew` run finds the zip, verifies the checksum again, and unpacks it without contacting
`services.gradle.org`. If the distribution is already cached, the script does nothing.

It needs `bash`, `bc`, `curl` or `wget`, and `md5sum`/`md5` plus `sha256sum`/`shasum`.

## Not covered

- **JitPack.** No mainland-China mirror proxies JitPack. As of September 2026, Aliyun, Tencent, and Huawei don't
  serve complete copies of the project's three JitPack dependencies (`com.github.cketti:xmlpull-extracted-from-android`,
  `com.github.cketti:kxml2-extracted-from-android`, `com.github.ByteHamster:SearchPreference`). If `jitpack.io` isn't
  reachable, build once on a network that can reach it, so Gradle caches these artifacts. Builds without
  `--refresh-dependencies` then use the cache.
- **JDK toolchain downloads.** See [JDK toolchains](#jdk-toolchains) below.
- **Android SDK components**, which the Android SDK Manager downloads, not Gradle.

## JDK toolchains

The Gradle daemon runs on JDK 21 (`gradle/gradle-daemon-jvm.properties`, any vendor). If no JDK 21 is installed,
Gradle downloads one from `api.foojay.io`. That host has no mirror setting, so from mainland China the download
usually times out with `ToolchainDownloadException: Unable to download toolchain matching the requirements`.

To avoid the download:

1. Install a JDK 21. The Tsinghua University mirror serves Eclipse Temurin builds at
   `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/`.
2. If it's not in a standard location (`/Library/Java/JavaVirtualMachines`, `/usr/lib/jvm`, SDKMAN, asdf), tell
   Gradle where it is in `~/.gradle/gradle.properties`:

   ```properties
   org.gradle.java.installations.paths=/path/to/jdk-21
   # Fail right away with a clear message instead of waiting for a network timeout
   org.gradle.java.installations.auto-download=false
   ```

   On macOS, the path points at the `Contents/Home` directory inside the JDK bundle.

3. Check that Gradle sees it: `./gradlew -q javaToolchains`.

## Trust

Artifacts resolved through a mirror are only as trustworthy as that mirror. The project doesn't use Gradle
dependency verification (`gradle/verification-metadata.xml`), so nothing checks mirrored artifacts against known
checksums. The Gradle distribution is the exception: both the script and the wrapper verify it against
`distributionSha256Sum`.
