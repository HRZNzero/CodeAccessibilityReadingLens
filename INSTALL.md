# Install / Build

This is a source project. Build it first, then install the generated plugin zip.

## Important IntelliJ setting

Because this project intentionally does **not** use Gradle toolchains, set Gradle JVM manually:

1. IntelliJ: `File > Settings > Build, Execution, Deployment > Build Tools > Gradle`
2. Set `Gradle JVM` to a local JDK 17 or newer. The JetBrains Runtime / bundled JDK is fine.
3. Reload the Gradle project.

## Build

Run:

```bash
gradle buildPlugin
```

or in the Gradle tool window:

```text
Tasks > intellij > buildPlugin
```

## Install

Install this generated file:

```text
build/distributions/CodeStructureVisualizer-1.0.6.zip
```

In IntelliJ:

```text
Settings > Plugins > gear icon > Install Plugin from Disk...
```

Do not install this source zip directly.


## Toggle shortcut

After installing the plugin, press `Ctrl+Alt+Shift+B` to turn the overlay on/off. You can also use `Tools > Toggle Code Structure Overlay`.

## Nested subsection shortcut

After installing the plugin, press `Ctrl+Alt+Shift+N` to toggle nested bracket-level colouring inside methods.
The main overlay shortcut remains `Ctrl+Alt+Shift+B`.
