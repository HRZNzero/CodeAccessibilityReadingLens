# Code Structure Visualizer

A minimal IntelliJ IDEA plugin that adds subtle background grouping in Java files:

- class fields are grouped with a soft field background
- each method/constructor gets its own soft method background

All background overlays are semi-transparent (~75–80 % opacity) so the editor
background and syntax colours remain visible underneath.

It is designed to make large Java files easier to visually scan without changing
code, formatting, folding, or inspections.

See `INSTALL.md` before building.


## Shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+Alt+Shift+B` | Toggle the visual overlay on / off |
| `Ctrl+Alt+Shift+G` | Toggle nested bracket-level colouring |
| `Ctrl+Alt+Shift+D` | Toggle **Focus / Dim mode** – grays out all code except the block the cursor is in |
| `Ctrl+Alt+Shift+W` | Toggle camelCase / snake_case identifier word-spacing |

All actions are also available under **Tools** in the main menu.


## Nested bracket-level colouring

Press `Ctrl+Alt+Shift+G` to turn on per-level colouring inside methods.

Each nesting level gets its own semi-transparent tint (applied with
`AlphaComposite` so they stack naturally without hiding the text):

| Level | Colour | Alpha |
|---|---|---|
| 1 – method body | `#30363 8` | 75 % |
| 2 – first nested block | `#2a353d` | 55 % |
| 3 | `#2c3b44` | 50 % |
| 4 | `#2e404b` | 45 % |
| 5+ | `#364e58` | 40 % |


## Focus / Dim mode

Press `Ctrl+Alt+Shift+D` to enter focus mode.  All code outside the block
the cursor is currently inside is dimmed with a near-black overlay (`#181a1b`,
90 % opacity).  Moving the caret instantly updates which block is highlighted.
Press the shortcut again to return to normal view.


## CamelCase / snake_case word-spacing

Press `Ctrl+Alt+Shift+W` to toggle. A small invisible 4 px inlay gap is
inserted at every word boundary inside method names, field names, and class
names — no source code is ever modified.
E.g. `generatePlayerData` will render with a tiny extra space before *Player*
and before *Data*.
