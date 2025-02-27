## General requirements
- Java 17 (should be specified in JAVA_HOME)
- [macOs/Linux] Android SDK downloaded via `./jbdeps/android-sdk/downloadAndroidSdk`
- [Windows] Android SDK downloaded from Android Studio and specified in ANDROID_SDK_ROOT

## Developing in IDE
1. Download Android Studio from [the official site](https://developer.android.com/studio/archive) (it is mandatory to use the version, written [here](https://github.com/JetBrains/androidx/blob/jb-main/gradle/libs.versions.toml#L11)). As an alternative you can use IDEA, which is compatible with [this AGP version](https://github.com/JetBrains/androidx/blob/jb-main/gradle/libs.versions.toml#L5), or you can disable Android plugin in IDEA plugins, to develop non-Android targets.
2. [macOs/Linux] Download Android SDK via `./jbdeps/android-sdk/downloadAndroidSdk`
3. [Windows] Download Android SDK via [Android Studio](https://developer.android.com/studio/intro/update#sdk-manager) and specify it in ANDROID_SDK_ROOT environment variable. Components, their versions and folder structure should be the same as in downloaded via script `./jbdeps/android-sdk/downloadAndroidSdk` SDK for other platforms.
4. Specify Gradle JVM to use JDK 17 in InteliJ IDEA Preferences (`Build, Execution, Deployment -> Build Tools -> Gradle`)

### Run tests

Run tests for Desktop:
```bash
./gradlew desktopTest
```

Run tests for Web:
```bash
./gradlew :mpp:testWeb
```

Run tests for UIKit:
```bash
./gradlew :mpp:testUIKit
```

### API checks
Compose Multiplatform stores all public API in *.api files. If any API is added/changed, `./gradlew checkDesktop` will fail with an error that API is changed (it runs on CI). Example:

```
Execution failed for task ':compose:material3:material3:desktopApiCheck'.
> API check failed for project material3.
  --- D:\Work\compose-multiplatform-core\compose\material3\material3\api\desktop\material3.api
  +++ D:\Work\compose-multiplatform-core\out\androidx\compose\material3\material3\build\api\desktop\material3.api
  @@ -552,6 +552,11 @@
   public abstract interface annotation class androidx/compose/material3/ExperimentalMaterial3Api : java/lang/annotation/Annotation {
   }

  +public final class androidx/compose/material3/FF {
  +     public static final field $stable I
  +     public fun <init> ()V
  +}
  +
   public final class androidx/compose/material3/FabPosition {
        public static final field Companion Landroidx/compose/material3/FabPosition$Companion;
        public static final synthetic fun box-impl (I)Landroidx/compose/material3/FabPosition;

   You can run :material3:apiDump task to overwrite API declarations
```

To fix this error:
1. Run `./gradlew desktopApiDump`
2. See what has changed in *.api files.
3. If there are only additions - there is no binary incompatible change.
4. If there are some removals - most probably there is a binary incompatible change and it needs to be fixed before merging it to the main branch.

Note that only desktop has API checks at the moment, but in the future it will be added for all targets.

### Publishing
Compose Multiplatform core libraries can be published to local Maven with the following steps:
1. Set `COMPOSE_CUSTOM_VERSION` environment variable
```bash
export COMPOSE_CUSTOM_VERSION=0.0.0-custom-version
```
2. Publish core libraries
```bash
./gradlew :mpp:publishComposeJbToMavenLocal -Pcompose.platforms=all
```
`-Pcompose.platforms=all` could be replace with comma-separated list of platforms, such as `js,jvm,androidDebug,androidRelease,macosx64,uikit`.

3. Publish extended icons
```bash
./gradlew :mpp:publishComposeJbExtendedIconsToMavenLocal -Pcompose.platforms=all --max-workers=1
```

4. (Optional) Publish Gradle plugin using [instructions](https://github.com/JetBrains/compose-multiplatform/tree/master/compose#publishing) to check changes locally.

### Run samples
Run jvm desktop samples:
```bash
./gradlew :compose:mpp:demo:runDesktop
```
```bash
./gradlew :compose:desktop:desktop:desktop-samples:run1
```
```bash
./gradlew :compose:desktop:desktop:desktop-samples:run2
```
```bash
./gradlew :compose:desktop:desktop:desktop-samples:run3
```
```bash
./gradlew :compose:desktop:desktop:desktop-samples:runSwing
```
```bash
./gradlew :compose:desktop:desktop:desktop-samples:runWindowApi
```
```bash
./gradlew :compose:desktop:desktop:desktop-samples:runVsync
```
```bash
./gradlew :compose:desktop:desktop:desktop-samples:runLayout
```
```bash
./gradlew :compose:desktop:desktop:desktop-samples-material3:runScaffold
```

Run wasm sample:
```bash
./gradlew :compose:mpp:demo:jsRun
```

Run native macos X64 sample:
```bash
./gradlew :compose:mpp:demo:runDebugExecutableMacosX64
```

Run native macos Arm64 sample:
```bash
./gradlew :compose:mpp:demo:runDebugExecutableMacosArm64
```

#### Run mpp/demo-uikit sample on iOS
 - Install plugin **Kotlin Multiplatform Mobile** for AppCode
 - Disable Android Plugin in IDE preferences
 - Open root of repository with AppCode
 - Increase AppCode IDE memory more that 10GB
 - Restart AppCode
 - Wait while project synchronization with Gradle
 - [Optional] To run on real iOS device 
   - Device should be at least with iOS 16.5. And Xcode should be at least 14.3
   - Find your TEAM_ID with instruction https://github.com/JetBrains/compose-multiplatform-template#running-on-a-real-ios-device
   - Create file project.properties in the root of repository. Add property `TEAM_ID=[your team id]` without double quotes, for example `TEAM_ID=ABC123ABC1`.
 - Choose run configuration **iOS App**

#### Run mpp/demo sample on iOS with Xcode
Run script:
```bash 
./compose/mpp/demo/regenerate_xcode_project.sh
 ```
Wait while Xcode is opening, and press run button.

### Clean IDE and Gradle cache
 - Close project
 - ```bash 
   ./cleanTempFiles.sh
   ```
