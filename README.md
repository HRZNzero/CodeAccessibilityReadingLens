# Code Structure Visualizer

A minimal IntelliJ IDEA plugin that adds subtle background grouping in Java files:

- class fields are grouped with a soft field background
- each method/constructor gets its own soft method background

It is designed to make large Java files easier to visually scan without changing code, formatting, folding, or inspections.

See `INSTALL.md` before building.


## Shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+Alt+Shift+B` | Toggle structure overlay on/off |
| `Ctrl+Alt+Shift+G` | Toggle nested bracket-level colouring |
| `Ctrl+Alt+Shift+I` | Toggle **Inspection Mode** |
| `Ctrl+Alt+Shift+W` | Toggle **camelCase / snake_case word-spacing** |

All actions are also available under **Tools** in the menu bar.


## Nested bracket-level colouring

When enabled, nested code blocks inside methods use these background colours:

| Level | Colour |
|---|---|
| 1 (method top) | `#34393b` |
| 2 | `#32383b` |
| 3 | `#363f42` |
| 4 | `#384347` |
| 5+ | `#485a61` |


## Inspection Mode (`Ctrl+Alt+Shift+I`)

Dims all code in the current file **except** the bracket or method body the
cursor is currently inside.  The active snippet is shown with a slightly
brighter background; everything else is covered by a near-black overlay.

Moving the cursor to a different bracket or method instantly updates which
region is highlighted.  Press the shortcut again to turn the mode off.


## camelCase / snake_case word-spacing (`Ctrl+Alt+Shift+W`)

Inserts a small invisible gap (4 px) at every word boundary inside method
names, field names, and class names so that long identifiers are easier to
read at a glance.

- **camelCase** – gap before each uppercase letter that follows a lowercase
  letter, e.g. `generatePlayerData` renders as `generate · Player · Data`.
- **snake_case** – gap after each `_` separator, e.g. `generate_player_data`
  renders as `generate_ · player_ · data`.

The spacing is purely visual and does not modify any source file.
