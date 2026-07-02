# appkit

`appkit` is the **desktop / dense-data platform binding** on top of
[`kotoba-ui`](../kotoba-ui), part of kotoba-lang's default UI/UX design
(`90-docs/adr/2607022800-kotoba-lang-default-uiux-appkit-uikit-interface-fundamentals.md`).
Mirrors Apple's AppKit role against SwiftUI (`kotoba-ui.core` plays the
SwiftUI-equivalent role here): screen-shape-specific *defaults*, not a new
component catalog.

## What it actually wraps

Of `kotoba-ui.core`'s 32 components, only `panel` (`:surface`/`:elevation`)
and `list-view` (`:surface`) take an opt that varies by screen shape in
liquid-glass-ui v1 — everything else (`toolbar`/`nav-bar`/`sheet`/`alert`/
`button`/…) has a fixed glass look with nothing to differentiate, so `appkit`
wraps only those two and leaves the rest to be called on `kotoba-ui.core`
directly.

| fn | default opts | rationale |
|---|---|---|
| `panel` | `{:surface :thick :elevation :flat}` | desktop panes read as flush/embedded, legible over dense content, no shadow since panes sit side-by-side rather than stacked |
| `list-view` | `{:surface :thick}` | same legibility rationale as panel |

Caller-supplied opts always win (`merge default opts`), same contract as
every liquid-glass-ui component wrap.

## Usage

```clojure
(require '[kotoba-ui.core :as ui]
         '[appkit.core :as app])

(app/panel [(ui/toolbar [(ui/icon-button "☰")])
            (app/list-view [(ui/list-row "Row 1") (ui/list-row "Row 2")])])
```

## Target consumers (provisional, see superproject ADR)

kotoba EDA flow workbench, slides editor, itonami.cloud cockpit,
kotobase.net console — dense/desktop-first product surfaces.

## Tests

```bash
clojure -M:test
clojure -M:local:test   # local ../kotoba-ui override
```
