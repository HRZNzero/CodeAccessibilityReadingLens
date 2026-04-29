# Code Structure Visualizer

A minimal IntelliJ IDEA plugin that adds subtle background grouping in Java files:

- class fields are grouped with a soft field background
- each method/constructor gets its own soft method background

It is designed to make large Java files easier to visually scan without changing code, formatting, folding, or inspections.

See `INSTALL.md` before building.


## Toggle shortcut

Press `Ctrl+Alt+Shift+B` to toggle the visual overlay on or off. The same action is also available under `Tools > Toggle Code Structure Overlay`.

## Nested bracket-level colouring

Press `Ctrl+Alt+Shift+N` to toggle nested subsection colouring inside methods.

Nested method levels use these colours:

- level 1: `#34393b`
- level 2: `#32383b`
- level 3: `#363f42`
- level 4: `#384347`
- level 5+: `#485a61`
