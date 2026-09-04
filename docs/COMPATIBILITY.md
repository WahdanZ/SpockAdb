# Compatibility strategy

This document records **how** the supported range is decided, not just what it currently
is. Every claim below is produced by `./gradlew verifyPlugin`, not by inspection.

## Supported range

| Target | Builds | Status |
|---|---|---|
| Android Studio | 2023.1 (Hedgehog) and later | Supported, verified |
| IntelliJ IDEA | 2023.1 and later, with the bundled Android plugin | Supported, verified |

- `sinceBuild = 231`
- `untilBuild` is left open so new IDE releases do not require a republish.
- Compiled against Android Studio 2025.1.1.14 (platform 251), Java 17, Kotlin 2.2.

## Why the Marketplace served 1.0.2 for four years

Until 4.0.0, searching for the plugin in any current Android Studio or IntelliJ IDEA
offered **1.0.2** — a 2019 build — even though 2.0.3 had shipped in 2022. The IDE was not
wrong and the Marketplace was not stale. Neither of the newer releases was *serveable*:

| Version | Descriptor | Result in a 2025 IDE |
|---|---|---|
| 1.0.2 | no `until-build` | The newest release still considered compatible — so this is what was offered |
| 2.0.0 – 2.0.3 | `until-build=211.*` / `213.*`, `depends com.intellij.modules.androidstudio` | Filtered out: capped below the IDE's build |
| 3.0.0, 3.0.1 | `<id>` renamed to `com.wahdan.spockAdb` | Never reached the Marketplace at all |
| 4.0.0 | `since-build=231`, no `until-build`, no Android Studio gate | Serveable to Android Studio and IDEA 2023.1+ |

Two separate mistakes, neither of which fails a build:

**An `until-build` cap is a silent expiry date.** `pluginUntilBuild=213.*` was correct on the
day 2.0.3 shipped and wrong three months later. The Marketplace does not report an error for
this; it just stops offering the release and falls back to the newest upload without a cap.
That fallback was 1.0.2. This is why `pluginUntilBuild` is now deliberately empty — see the
comment in `gradle.properties`.

**The plugin id is the Marketplace primary key.** 3.0.0 renamed `<id>` from
`com.wahdan.com.wahdan.spockAdb` (a typo, but the published one) to the tidier
`com.wahdan.spockAdb`. An id is not cosmetic: it identifies the listing. Both 3.x release
workflow runs failed, no 3.x ever appeared on
[listing 11591](https://plugins.jetbrains.com/plugin/11591-spockadb), and the id was
restored before 4.0.0. **Do not rename it.** Publishing under a different id cannot update
this listing, and users who installed the old id would not see the rename as an update.

### The guard

`scripts/verify-marketplace-descriptor.sh` asserts, against the built artifact rather than
the source descriptor (`since-build`, `until-build` and `version` are all patched in by the
Gradle plugin):

- `<id>` is exactly `com.wahdan.com.wahdan.spockAdb`,
- there is no `until-build` attribute,
- `since-build` matches `pluginSinceBuild`,
- `<version>` matches the release tag (release job only).

It runs in the `Build` job on every pull request, and in the `Release` job immediately
before `publishPlugin`, so neither mistake can ship again.

### After a release: the version is not instant

`publishPlugin` succeeding means *uploaded*, not *served*. A new version goes through
Marketplace approval, and the compatibility index is rebuilt afterwards, so the plugin page
can already show the new description and screenshots while the IDE's Install button still
offers the previous compatible version. Check the actual state in the vendor console at
<https://plugins.jetbrains.com/plugin/11591/versions> before assuming a descriptor bug —
and if the IDE lags behind the plugin page, *Settings > Plugins > gear > Check for Updates*
after restarting refreshes its cached repository listing.

## Why the plugin is no longer Android Studio only

Until 3.0.0 the descriptor declared:

```xml
<depends>com.intellij.modules.androidstudio</depends>
```

That is a hard gate: the Marketplace lists the plugin as **incompatible with IntelliJ
IDEA**, and IDEA refuses to install it, regardless of whether the code would actually run.

It turns out to have bought nothing. Every Android API the plugin uses —
`com.android.ddmlib`, `AndroidFacet`, `AndroidModel`, `AndroidSdkUtils`, and the execution
tooling — is packaged inside `plugins/android/lib/*.jar`, i.e. the `org.jetbrains.android`
plugin, which the descriptor already depends on. None of them come from the Android Studio
platform itself.

The dependency was therefore removed, and IntelliJ IDEA was added to the verification
matrix so the claim is checked rather than assumed.

Two dependencies were added at the same time:

- `com.intellij.modules.platform` — the standard base dependency.
- `com.intellij.modules.java` — the plugin navigates to Activity and Fragment sources
  through `JavaPsiFacade` and `PsiShortNamesCache`. Plugin Verifier reported using Java
  PSI without declaring the Java plugin as a compatibility problem on **every** target.

## The one feature that is not universally available

`Restart App With Debugger` needs
`com.android.tools.idea.execution.common.debug.AndroidDebugger`, part of the Android Studio
execution stack. It is present in every supported Android Studio build and in IntelliJ IDEA
2025.1, but not in the Android plugin bundled with IntelliJ IDEA 2023.1.

Rather than gate the whole plugin on it, `spock.adb.compat.DebuggerSupport` feature-detects
the class before the `Debugger` class is loaded:

- the action is hidden in the tool window when unavailable, and
- the command reports a clear message instead of dying with `NoClassDefFoundError`.

The corresponding verifier finding is listed in `verifier-ignored-problems.txt` with the
reasoning. **If the guard is removed, delete that entry and let the verifier fail.**

## The `sinceBuild` trap, and why compiling against a newer platform is not free

`sinceBuild = 231` was previously declared while compiling against platform 251. That
combination was **not** actually compatible with 231, and the verifier proved it:

```
Method spock.AdbDrawerViewer.manage(...) contains an *invokespecial* instruction
referencing an unresolved method com.intellij.openapi.wm.ToolWindowFactory.manage(...).
This can lead to NoSuchMethodError exception at runtime.

Method spock.AdbDrawerViewer.isApplicableAsync(...) contains an *invokespecial* instruction
referencing an unresolved method ToolWindowFactory.isApplicableAsync(...).
```

`ToolWindowFactory` is a Kotlin interface with default methods. When a Kotlin class
implements it, the compiler emits *compatibility bridge* overrides that `invokespecial`
into every default method the interface had **at compile time**. Compiled against 251, the
bridges reference `manage` and `isApplicableAsync`, which do not exist on 231.

`AdbDrawerViewer` is the tool window factory — the plugin's entry point — so this was not a
corner case: the tool window would fail to open on Android Studio 2023.1 through 2024.x.

The fix is one compiler flag in `build.gradle.kts`:

```kotlin
freeCompilerArgs.add("-Xjvm-default=all")
```

which stops the bridges being generated. After it, all five targets report `Compatible`.

The general lesson, and the rule for this repository: **lowering `sinceBuild` is a claim
that must be verified.** Compiling against a newer platform can inject references to APIs
that do not exist in older ones, without any warning at compile time.

## The second trap: inline platform functions

The same class of problem appears with Kotlin `inline` helpers from the platform. Using the
idiomatic service lookup:

```kotlin
fun getInstance(project: Project): SpockAdbService = project.service()
```

inlines a call to `ServicesKt.serviceNotFoundError`, which does not exist before 2023.3.
The verifier caught it immediately:

```
Method SpockAdbService.Companion.getInstance(Project) contains an *invokestatic*
instruction referencing an unresolved method ServicesKt.serviceNotFoundError(...).
This can lead to NoSuchMethodError exception at runtime.
```

Inlining copies the callee's body — including calls to APIs that are private to the
platform version you compiled against — into your own bytecode. Prefer the non-inline
equivalent when supporting a wide range:

```kotlin
project.getService(SpockAdbService::class.java)
```

Both `SpockAdbService.getInstance` and `AppSettingService.getInstance` do this deliberately.

## The third trap: a newer overload of an old class

The same failure shape appears when a class you have always used gains a nicer constructor
or method. `FileSaverDescriptor(String, String)` reads better than the vararg form and the
vararg form is deprecated on 2025.1 — but the two-argument constructor does not exist before
it:

```
Method LogcatPanel.export() contains an *invokespecial* instruction referencing an
unresolved constructor FileSaverDescriptor.<init>(String, String).
This can lead to NoSuchMethodError exception at runtime.
```

Compiling against the newest platform makes the newer overload the obvious choice, and
nothing warns you. **A deprecation warning on the newest platform is strictly better than a
NoSuchMethodError on the oldest**, so the deprecated-but-present overload is used, with a
comment saying why. Do not "clean up" that call without re-running the verifier.

Four distinct instances of this one failure mode turned up while modernising this plugin —
compatibility bridges, an inlined platform helper, a newer overload, and a Kotlin builder
needing a newer stdlib. All four were invisible at compile time, and all four were caught by
`verifyPlugin`.

## The fourth trap: Kotlin language features that need a newer stdlib

`kotlin.stdlib.default.dependency=false` — the plugin uses the Kotlin stdlib **bundled with
the IDE**, not one it ships. So a language feature whose generated code calls a newer stdlib
function fails on an older IDE even though it compiles perfectly:

```
Method UiNode.asSequence$1.invokeSuspend(Object) references an unresolved class
kotlin.coroutines.jvm.internal.SpillingKt.
This can lead to NoSuchClassError exception at runtime.
```

That came from an innocuous-looking `sequence { yield(...) }` builder. It compiles to a
coroutine state machine, and Kotlin 2.x emits a reference to `SpillingKt`, which the stdlib
bundled with 2023.1 IDEs does not have. Rewritten as a plain recursive walk.

**Be wary of anything that lowers to a coroutine state machine** — `sequence { }`,
`iterator { }`, `suspend` functions — in code that must run on the oldest supported IDE.
`verifyPlugin` is what catches it; nothing else does.

## Verification matrix

`./gradlew verifyPlugin` checks these builds. They are the two ends of the supported range
plus a midpoint, on both IDEs:

| IDE | Version | Why |
|---|---|---|
| Android Studio | 2023.1.1.28 | The `sinceBuild` floor |
| Android Studio | 2024.2.1.12 | Midpoint, catches breakage between the ends |
| Android Studio | 2025.1.1.14 | Current stable, the compile target |
| IntelliJ IDEA Community | 2023.1.5 | IDEA floor, no Android execution tooling |
| IntelliJ IDEA Community | 2025.1 | Current IDEA |

CI runs this on every push and pull request as the `Plugin Verifier` job, and uploads the
report. `failureLevel` includes `COMPATIBILITY_PROBLEMS`, `INVALID_PLUGIN` and
`NOT_DYNAMIC`, so a regression fails the build rather than producing a warning.

## Changing the supported range

1. Change `pluginSinceBuild` in `gradle.properties` and/or `androidStudioVersion`.
2. Update the `ides { }` block in `build.gradle.kts` so the new floor is verified.
3. Run `./gradlew verifyPlugin` and read the report — do not rely on the build passing
   alone, since deprecation and internal-API findings do not fail it.
4. Update the table at the top of this file.

## Known remaining findings

Two deprecated API usages remain, both deliberate:

- `org.joor.Reflect.on(Class)` — inside `Debugger`'s reflective fallback path for older
  Android Studio builds. Replacing it means dropping that fallback.
- The IntelliJ form runtime's generated `$$$setupUI$$$` is invoked reflectively from
  Kotlin constructors, because the form compiler only injects the call automatically into
  Java constructors.

Neither is a compatibility problem; both are recorded here so they are not rediscovered.
