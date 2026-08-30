# UI Redesign Plan — BookCon v1.3.0

Reference: Syed Raju's "E-Book App" on Dribbble (sha256 7aa0f890…, 1459×1083).

This plan takes the existing feature-complete BookCon app and re-skins the
**chrome** (navigation, lists, cards, toolbars, dialogs, settings) without
touching the **reader surface** itself (page rendering, PDF rendering, EPUB
navigator). The reader stays high‑contrast and unobtrusive; everything around
it gets the friendly e‑commerce look.

---

## 1. Visual Analysis of the Reference

### Three surfaces visible
1. **Favorites** — empty‑state with isometric book illustration and a solid
   orange CTA pill. Header bar is saturated cobalt blue with white icons +
   title.
2. **Home / "Hi, Syed"** — greeting + greeting chip, then a section title
   ("Continue Reading") with a "See more" link, a horizontal carousel of
   book cards (cover thumbnail + tiny price/year line), then a "This Week"
   section with its own "See more".
3. **Store / Browse** — left side circular category thumbnails with names,
   right side a vertical ranked list of books with thumbnails and counts.

### Style traits
| Trait | What the reference does |
|---|---|
| **Backgrounds** | Pure white (`#FFFFFF`) or very light grey (`#F7F8FA`) |
| **Accent (primary)** | Cobalt / royal blue, ~`#2F66F4` to `#3B7BFF` (header bars, active icons) |
| **CTA / promo accent** | Warm orange `~#FF6B35` (Browse Books button) |
| **Type** | Bold display headings (~24–28sp), medium body (~14–16sp), greyed supporting text (~12sp) |
| **Cards** | 12–16 dp corner radius, very subtle elevation (0–2 dp), generous internal padding (12–16 dp) |
| **Spacing** | Generous — ~16 dp page gutters, ~8–12 dp between list items, ~20 dp between sections |
| **Shapes** | Pill‑shaped buttons (fully rounded), circular category icons, rounded rectangles for cards |
| **Iconography** | Outlined / line icons, minimal, consistent stroke width |
| **Shadow** | Almost none — depth comes from borders and background contrast |
| **Header bars** | Solid blue rounded "top bar" containing title + 2–3 trailing icons (search, list/menu) |
| **Empty states** | Large illustration, short copy, single orange pill CTA |
| **Lists** | Thumbnail on left, title bold, subtitle greyed, trailing meta on right |

### Tone
Friendly, consumer‑facing, bookstore‑like. Closer to **Storytel / Kindle /
Wattpad** than to a developer tool. Implies the user is shopping/discovering
as much as reading.

---

## 2. Design Tokens — what we extract vs invent

### Color (new tokens in `core/Theme.kt`)
| Token | Light | Dark | Use |
|---|---|---|---|
| `brand/primary` | `#2F66F4` | `#5B8DFF` | Header bars, active icons, primary buttons |
| `brand/primaryContainer` | `#E8EFFF` | `#1B2A55` | Tints, chips, selected state bg |
| `brand/secondary` | `#FF6B35` | `#FF8A5C` | CTA pills, "Browse Books", promos |
| `surface/page` | `#FFFFFF` | `#0F1115` | Page background |
| `surface/card` | `#FFFFFF` | `#1A1D24` | Card / sheet |
| `surface/muted` | `#F4F6FA` | `#161A22` | Secondary bg, search field, dividers |
| `text/primary` | `#0F1623` | `#F1F4F8` | Headings |
| `text/secondary` | `#5A6478` | `#A4ADBC` | Supporting text, prices |
| `divider` | `#EAECF1` | `#262B36` | Hairlines |
| `state/success` | `#1FA868` | `#34D399` | (reuse) |
| `state/error` | `#DC2626` | `#F87171` | (reuse) |

### Typography (Material 3 type scale, retuned)
- `displaySmall` → 28sp / 700 (screen titles)
- `headlineMedium` → 22sp / 700 (section headers, greeting)
- `titleLarge` → 18sp / 600 (card titles, book titles)
- `titleMedium` → 16sp / 600 (sub‑headers, list items)
- `bodyLarge` → 15sp / 400 (body copy)
- `bodyMedium` → 14sp / 400 (descriptions)
- `bodySmall` → 12sp / 500 (metadata, prices)
- `labelLarge` → 14sp / 600 (button text)
- Use system font **Inter** (fall back to Roboto if not bundled) — friendly
  geometric sans that matches the reference.

### Shape
- `shapes/small` = 8 dp
- `shapes/medium` = 14 dp (cards)
- `shapes/large` = 20 dp (sheets)
- `shapes/pill` = full (Buttons default to fully rounded)

### Spacing
- Page gutter: 16 dp
- Card padding: 14 dp
- List row vertical spacing: 8 dp
- Section spacing: 20 dp

### Elevation
- Cards: 0 dp + 1 px border in light, 1 dp in dark
- Sheets: 2 dp
- Top app bar: 0 dp (flat, coloured background)

---

## 3. Component Library

New shared composables under `ui/components/`:

| Component | Purpose | Specs |
|---|---|---|
| `AppTopBar` | Replaces current `TopAppBar` everywhere | Solid blue bg, white title left, up to 3 trailing icons, rounded bottom corners (16 dp) |
| `BookCover` | Smart cover with placeholder fallback | 2:3 aspect, 12 dp radius, slight shadow; uses Coil |
| `BookListRow` | Horizontal row: cover + title + subtitle + meta | Used in library, history, vocab, search |
| `BookCard` | Vertical card: cover on top, title below | Used in carousels, recommendations |
| `PillButton` | Fully rounded CTA | Used for empty states, primary actions |
| `SectionHeader` | Title left + "See more" right | Section header pattern |
| `EmptyState` | Illustration + text + CTA | Empty libraries, vocab, stats |
| `SearchField` | Rounded muted-bg field with leading icon | Replaces current OutlinedTextField |
| `FormatChip` | Small coloured chip: EPUB / PDF / CBZ | Reused from current; restyled |
| `CategoryCircle` | Circular category icon | New (for Store‑style view) |
| `DrawerScaffold` | Optional side drawer for nav | Can replace top-bar overflow |

---

## 4. Screen‑by‑Screen Mapping

| Screen | Current | New |
|---|---|---|
| **Library (home)** | Top bar + filter chips + grid/list toggle + flat list | `AppTopBar` (blue) with "📚 My Library" + search/menu; greeting "Welcome back" header; "Continue Reading" carousel; "All Books" section with `BookListRow`; FAB replaced by bottom pill CTA |
| **Book detail** | Modal sheet | Full screen with `AppTopBar` (transparent), large `BookCover`, title block, format chip, action buttons (Read / Cover / Export), description, metadata list |
| **Reader (EPUB)** | Readium webview surface + minimal chrome | **No change to page surface.** Update chrome bars: top = transparent with floating round buttons; bottom = pill row (page #, bookmark, AI, TTS, a-a) |
| **Reader (PDF)** | Same shape as EPUB chrome | Same new chrome as EPUB |
| **Settings** | Card‑stack list | `AppTopBar` + grouped cards, each with icon avatar (circular tinted bg) + title + subtitle + chevron |
| **AI summary sub‑screen** | Stacked cards | Restyled: `PillButton` for Test connection, dropdowns for provider/model, `SearchField` for key |
| **Stats** | Mix of chart + lists | New `AppTopBar`; hero card with goal ring; chart card; per‑book list using `BookListRow` |
| **Vocab** | List + add/edit dialog | `AppTopBar`; word cards with `EmptyState` if none; review flow uses pill buttons |
| **Wi‑Fi import** | URL + status | `AppTopBar`; hero card showing URL (monospace pill) with copy button; instructions list |
| **Annotations** | List of highlights | Card list of excerpts; per‑row AI actions menu |
| **Auth / Sign‑in** | Plain form | Restyled: centered logo, pill inputs |
| **Onboarding / First run** | None | New: 3‑page pager with `EmptyState`‑style illustrations + "Get Started" pill |

---

## 5. Implementation Phases

### Phase 1 — Design System Foundation (no UI changes visible)
**Files touched**: `core/Theme.kt`, `ui/theme/Colors.kt`, `ui/theme/Type.kt`, `ui/theme/Shape.kt`, `ui/components/*` (new), `res/font/*` (Inter).

Deliverables:
- New color/typography/shape tokens committed.
- All components listed above built and previewed in `@Preview`.
- No screen changes yet — app looks identical to today.

### Phase 2 — Library + Book Detail (the home surfaces)
**Files touched**: `ui/library/LibraryScreen.kt`, `ui/library/LibraryViewModel.kt`, `ui/details/*`.

- Adopt `AppTopBar`, `SectionHeader`, `BookListRow`, `BookCover`, `SearchField`.
- Add "Continue Reading" carousel pulling from last‑read list (already tracked).
- Add empty‑state with illustration + "Browse" pill.
- Detail screen becomes a full screen.

### Phase 3 — Settings + Sub‑screens
**Files touched**: `ui/settings/SettingsScreen.kt`, `AiSettingsScreen.kt`, `ui/vocab/*`, `ui/stats/*`, `ui/importwifi/*`, `ui/annotations/*`.

- All settings cards adopt new shape, icon avatars, chevrons.
- AI summary adopts `PillButton`, `SearchField` for key.
- Stats hero card with goal ring restyled.
- Vocab review flow uses pill buttons.

### Phase 4 — Reader Chrome
**Files touched**: `ui/reader/ReaderScreen.kt`, `ui/reader/PdfPager.kt`.

- Replace Material `TopAppBar` with `AppTopBar` (transparent).
- Bottom chrome becomes a single floating pill row.
- Keep all existing functionality (scrubber, AI, TTS, night mode, annotations).

### Phase 5 — Onboarding + Polish
- First‑run welcome pager.
- Empty‑state illustrations (vector drawables in `res/drawable`).
- Dark mode polish.
- Animation: gentle fade between sections, carousel scroll snap.

### Phase 6 — Ship
- Bump to v1.3.0 (code 4).
- Build, install on tablet, verify visually.
- Screenshot diff against reference.
- Push + GitHub release.

---

## 6. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Reader chrome changes break long‑press selection / ink gestures | Keep gesture zones the same; only restyle `Box` containers and colors |
| Carousels / LazyRow performance on the A9 (4 GB RAM) | Use `key()` for items, fixed `contentType`, cap carousel window to 5 items |
| Color contrast in dark mode | Validate every new color pair with WCAG AA before merge |
| Reader reader‑theme (sepia/dark/black) regression | Theme tokens only affect chrome; reader keeps its own `readerTheme` switch |
| Inter font licensing | Ship Roboto first; switch to Inter later if user provides OFL bundle |
| Screenshot regressions | Capture before/after with existing `bc.py` helpers; assert pill bounds, greeting text, count of sections |

---

## 7. What I Will NOT Change
- PDF rendering pipeline (PDFBox) — pixel‑identical output.
- EPUB Readium navigator behaviour.
- BYOK summarization logic — only the settings UI.
- Data layer (Room, DataStore, network).
- All gesture mappings already proven on the tablet.

---

## 8. Decisions Locked In
1. **Accent**: cobalt blue (`#2F66F4`) primary + warm orange (`#FF6B35`) secondary, exactly as the reference shows. Two‑tone gives the friendly bookstore feel.
2. **Greeting**: "Welcome back" generic copy for now (no name in account yet). Drop in a first‑name greeting later if we wire up a profile.
3. **Bottom nav**: **add a bottom navigation bar**. The reference clearly assumes it. Four destinations: Library / Discover / Stats / Settings. The overflow menu goes away.
4. **Onboarding**: **skip 3‑page intro**. Land directly on the library. Empty‑state illustration + "Import your first book" pill replaces the intro.
5. **Icons**: **swap to Lucide** — thin consistent stroke matches the reference's friendly line‑icon style. Ship the Lucide set via the `compose-material-icons-extended` proxy we already have, or bundle a small Lucide subset as vectors.

---

## 9. Estimated Effort
- Phase 1: 1 session (foundation only)
- Phase 2: 1–2 sessions (the visible "wow" change)
- Phase 3: 1 session
- Phase 4: 0.5 session (chrome only)
- Phase 5: 0.5 session
- Phase 6: 0.5 session

Total: ~5 sessions, fully testable on your tablet between each phase.
