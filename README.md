# Code Structure Visualizer

Code Structure Visualizer is an IntelliJ IDEA plugin for Java files. It adds
optional visual layers on top of the editor to make the structure of a file
easier to read at a glance. The plugin does not change your source code,
formatting, folding, or inspections — every effect is a pure overlay that you
can turn on or off at any time.

## What it does

The plugin focuses on accessibility and code clarity. It is especially aimed
at users who benefit from stronger visual segmentation of code, such as
readers with dyslexia or anyone working with long Java files. All features
are independent toggles, so you can enable only the ones you find useful.

Available features:

- **Code structure overlay** — soft background tints group class fields and
  give each method or constructor its own band.
- **Nested code block colours** — each bracket-nesting level inside a method
  gets a slightly different semi-transparent tint.
- **Focus / Dim mode** — dims everything outside the block your caret is in,
  so the current scope stands out.
- **Identifier word-spacing** — inserts a tiny visual gap and subtle bold at
  every camelCase or snake_case word boundary in identifiers (no code is
  modified).
- **Syntax emphasis** — configurable bold and colour for selected tokens
  such as `if`, brackets, quotes, semicolons, and dots.
- **Indented boxes** — background bands start at each block's indentation
  column, producing a nested-box look that mirrors bracket depth.
- **Wider field spacing** — adds a thin separator between consecutive field
  rows for easier scanning of class fields.
- **Control-flow view** — highlights every `if` / `else` chain and `switch`
  block in a single orange tint so control flow is easy to spot.
- **Line markers** — place markers on lines, jump between them, or focus the
  editor on only the marked sections.

## Where to find the actions

Every feature has a keyboard shortcut and is also listed under the
**Tools** menu. See the *Shortcuts* section below for the full list.

## Installation

This repository contains the source. To build and install the plugin, see
[`INSTALL.md`](INSTALL.md).
