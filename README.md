# Code Structure Visualizer

A minimal IntelliJ IDEA plugin that adds subtle background grouping in Java files:

- class fields are grouped with a soft field background (`#7b7d57`, 65 % opacity)
- each method/constructor gets its own soft method background

All background overlays are semi-transparent so the editor background and syntax
colours remain visible underneath.

It is designed to make large Java files easier to visually scan without changing
code, formatting, folding, or inspections.

See `INSTALL.md` before building.


## Shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+Alt+Shift+B` | Toggle the visual overlay on / off |
| `Ctrl+Alt+Shift+G` | Toggle nested bracket-level colouring |
| `Ctrl+Alt+Shift+F` | Toggle **Focus / Dim mode** |
| `Ctrl+Alt+Shift+W` | Toggle camelCase / snake_case identifier word-spacing |
| `Ctrl+Alt+Shift+Y` | Toggle **Syntax Emphasis** (bold / colour for tokens) |
| `Ctrl+Alt+Shift+X` | Toggle **Indented Boxes** mode |
| `Ctrl+Alt+Shift+L` | Toggle **Wider Field Spacing** |
| `Ctrl+Shift+O`     | Toggle **Control-Flow View** |

All actions are also available under **Tools** in the main menu.


## Nested bracket-level colouring

Press `Ctrl+Alt+Shift+G` to turn on per-level colouring inside methods.

Each nesting level gets its own semi-transparent tint:

| Level | Colour | Alpha |
|---|---|---|
| 1 – method body | `#303638` | 75 % |
| 2 – first nested block | `#2a353d` | 55 % |
| 3 | `#2c3b44` | 50 % |
| 4 | `#2e404b` | 45 % |
| 5+ | `#364e58` | 40 % |


## Focus / Dim mode

Press `Ctrl+Alt+Shift+F` to enter focus mode.  All code outside the block
the cursor is in is dimmed with a near-black overlay (`#181a1b`, 90 % opacity).
Moving the caret instantly updates which block is highlighted.
Press the shortcut again to return to normal view.


## CamelCase / snake_case word-spacing

Press `Ctrl+Alt+Shift+W` to toggle. A small 2 px inlay gap (intentionally
smaller than a full space) is inserted at every word boundary inside method
names, field names, class names, **local variables**, and **parameters** — no
source code is ever modified.  The first character of each new word also gets a
subtle bold weight.

E.g. `generatePlayerData` renders with a tiny extra space before *Player*
and before *Data*, with those letters slightly bold.


## Indented Boxes mode (`Ctrl+Alt+Shift+X`)

When active, every background band starts at the first non-whitespace column
of its block's opening line instead of the left margin.  Nested blocks therefore
appear as inset boxes that visually mirror the code's bracket depth.


## Wider Field Spacing (`Ctrl+Alt+Shift+L`)

When active, each field row in a class is annotated individually and a 4 px
top-inset is applied to its background.  Adjacent fields show a thin dark
separator line between them, making it easy to distinguish one field from
the next at a glance (~30 % more visual separation).


## Control-Flow View (`Ctrl+Shift+O`)

Toggle to replace the normal level colouring with a single orange tint
(`#d17524`, 55 % opacity) applied to **every `if` / `else` chain and `switch`
statement** in the file.  Field backgrounds remain visible.  Because
`PsiIfStatement` covers its entire else-chain, the full extent of each
conditional is highlighted as one unit — nested ifs stack naturally via alpha
and appear slightly brighter.

Press the shortcut again to return to normal view.
