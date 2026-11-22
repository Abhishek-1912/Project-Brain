# Project Brain Brand Theme

Status: Source of truth for all frontend styling
Direction: dark developer workspace, with electric cyan as the brand accent

Use these tokens in components. Do not hardcode hex values outside `src/index.css`.

## Tailwind tokens

Copy this block into `frontend/src/index.css` in Task 10.

```css
@import "tailwindcss";
@import "@fontsource-variable/inter";
@import "@fontsource-variable/jetbrains-mono";

@theme {
  --font-sans: "Inter Variable", ui-sans-serif, system-ui, sans-serif;
  --font-mono: "JetBrains Mono Variable", ui-monospace, monospace;

  /* Brand */
  --color-primary: #06b6d4;
  --color-primary-hover: #22d3ee;
  --color-primary-dim: rgb(6 182 212 / 0.15);
  --color-on-primary: #0a0e17;
  --color-secondary: #6366f1;
  --color-secondary-light: #818cf8;

  /* Surfaces, darkest to brightest */
  --color-canvas: #0a0e17;
  --color-surface-dim: #0f131c;
  --color-surface-low: #131824;
  --color-surface: #181f2f;
  --color-surface-high: #1f273b;
  --color-surface-bright: #2b344d;

  /* Borders */
  --color-border: #1e293b;
  --color-border-focus: #06b6d4;
  --color-border-active: rgb(6 182 212 / 0.4);
  --color-hairline: rgb(255 255 255 / 0.04);

  /* Text */
  --color-text-primary: #f8fafc;
  --color-text-secondary: #94a3b8;
  --color-text-meta: #8291a7;
  --color-text-muted: #64748b;
  --color-text-disabled: #475569;

  /* Status */
  --color-success: #10b981;
  --color-success-tint: rgb(16 185 129 / 0.12);
  --color-warning: #f59e0b;
  --color-warning-tint: rgb(245 158 11 / 0.12);
  --color-error: #ef4444;
  --color-error-text: #f87171;
  --color-error-tint: rgb(239 68 68 / 0.15);
  --color-info: #38bdf8;
  --color-info-tint: rgb(56 189 248 / 0.12);
}

body {
  background: var(--color-canvas);
  color: var(--color-text-secondary);
  font-family: var(--font-sans);
}
```

## Where each surface goes

| Token | Use |
|---|---|
| `canvas` | App background |
| `surface-dim` | Main workspace |
| `surface-low` | Sidebar and top navigation |
| `surface` | Cards, tables, panels |
| `surface-high` | Hovered cards, modals, nested cards |
| `surface-bright` | Active tabs, search, dropdowns |

## Readability rules

These rules keep text at WCAG AA contrast (4.5:1).

- Primary buttons use `on-primary` dark text. White on cyan is only 2.43:1.
- Timestamps, metadata and placeholders use `text-meta` on surfaces up to `surface-high`. On `surface-bright`, use `text-secondary`.
- `text-muted` (`#64748b`) is for icons, arrows and decoration, not for text people must read.
- `text-disabled` is only for disabled controls.
- Indigo text uses `secondary-light`. `secondary` is for fills and borders.
- Small error text uses `error-text`. `error` is for icons, borders and fills.
- Do not put colored status text on `surface-bright`. Use a tinted badge on a darker surface.
- Status is never shown by color alone. Pair it with a label or icon.
- Every interactive element has a visible `border-focus` focus ring.

## Typography

- Inter for all interface text.
- JetBrains Mono for code, IDs, timestamps and numeric telemetry. It replaces Space Mono, so the app loads one mono font.
- Fonts are bundled with the app, so no request goes to a font CDN.

## Color meaning

- Cyan: primary actions, active state, key values.
- Indigo: architecture, RAG, LangGraph, schema and DTO labels.
- Green: healthy or passed. Amber: needs review. Red: critical or blocking. Sky: informational or synced.

Show only real data. Do not ship sample values such as uptime, budget or version figures as if they were live. Phase 1 has no telemetry screens.
