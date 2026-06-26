# Design Tokens — Source of Truth

Slate + emerald "glass" design system (Linear/Vercel aesthetic). This file is the **single human-readable
source of truth**. The same tokens are encoded on three parallel surfaces that must stay in lockstep —
diff them against this table whenever a value changes:

1. **Android Compose** — `ui/theme/Color.kt`, `AppColors.kt`, `Dimens.kt`, `Elevation.kt`, `Motion.kt`, `Glass.kt`
2. **Android XML** — `res/values/themes.xml` + `res/values-night/themes.xml` (`?attr/app*`), `res/values/dimens.xml`
3. **Windows Compose** — `Window Ver/src/main/kotlin/com/joseph/clipboard/windows/theme/*` (Phase 2)

> There is intentionally **no shared/KMP module** — parallel-file discipline is the chosen strategy (one
> developer; the `:app` Android app and `:window-ver` JVM app have incompatible module shapes). Revisit only
> if a third platform appears.

## Colors

| Token | Light | Dark | Compose val | XML attr |
|---|---|---|---|---|
| background | `#F8FAFC` | `#0F172A` | `*Background` | `appBackgroundColor` |
| card | `#FFFFFF` | `#1B2336` | `*Card` | `appCardColor` |
| surfaceElevated | `#F8FAFC` | `#222C42` | `*SurfaceElevated` | `appSurfaceElevated` |
| textPrimary | `#0F172A` | `#F1F5F9` | `*TextPrimary` | `appTextPrimaryColor` |
| textSecondary | `#475569` | `#94A3B8` | `*TextSecondary` | `appTextSecondaryColor` |
| divider | `#E2E8F0` | `#2A3448` | `*Divider` | `appDividerColor` |
| hairline | `#0F172A @6%` (`0x0F0F172A`) | `#FFFFFF @8%` (`0x14FFFFFF`) | `*Hairline` | `appHairline` |
| scrim | `#0F172A @40%` (`0x660F172A`) | `#0F172A @72%` (`0xB80F172A`) | `*Scrim` | `appScrim` |
| navBar | `#FFFFFF` | `#131A2C` | `*NavBar` | `appNavBarColor` |
| navIcon | `#475569` | `#94A3B8` | `*NavIcon` | `appNavIconColor` |
| bottomSheet | `#FFFFFF` | `#1B2336` | `*BottomSheet` | `appBottomSheetColor` |
| gradientTop | `#FFFFFF` | `#111A2E` | `*GradientTop` | — (Compose-only) |
| gradientBottom | `#F1F5F9` | `#0C1322` | `*GradientBottom` | — (Compose-only) |
| green (fill) | `#16A34A` | `#22C55E` | `*Green` | `appGreen` |
| greenText (WCAG) | `#15803D` | `#4ADE88` | `*GreenText` | `appGreenText` |
| accentMuted | `#16A34A @10%` (`0x1A16A34A`) | `#22C55E @14%` (`0x2422C55E`) | `*AccentMuted` | — (Compose-only) |
| success | `#15803D` | `#34D399` | `*Success` | `appSuccess` |
| info | `#1D4ED8` | `#60A5FA` | `*Info` | `appInfo` |
| warning | `#B45309` | `#FBBF24` | `*Warning` | `appWarning` |
| error / destructive | `#DC2626` | `#EF4444` | `*Error` | `appError` |

**Brand mark only:** `#34D399` (launcher icon / logo). UI fill green is `#16A34A` (light) / `#22C55E` (dark).
**Green discipline:** `green` = fill (white/dark text ON it); `greenText` = text/link/small-icon green (≥4.5:1).

## Dimens

Spacing (4/8 rhythm): `xxs 2 · xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32 · huge 48` (dp).
Radii: `xs 6 · sm 8 · md 12 · lg 16 · xl 20 · pill 999` (dp). Glass: `hairlineWidth 1 · blurRadius 24` (dp).
Controls: `touchTarget 48 · buttonHeight 56 · iconSm 18 · iconMd 24 · iconLg 32 · headerIcon 28` (dp).

## Elevation (`Elevation.kt`)

`level0 0 · level1 1 (card) · level2 3 (bento/raised) · level3 8 (popover) · modal 24` (dp).
Rendered as tint + hairline + soft shadow, **not** heavy M2 drop shadows.

## Motion (`Motion.kt`)

Signature easing `emphasized = cubic-bezier(0.16, 1, 0.3, 1)`; `standard = FastOutSlowIn`.
Durations: `durFast 120` (exit/collapse) · `durMed 220` (enter/expand/crossfade) · `durSlow 320` (hero), ms.
`pressScale 0.97` · `staggerStepMs 40` (cap ~8 items).
**Reduced motion:** `Motion.reduceMotion` is computed in `AppTheme` from `Settings.Global.ANIMATOR_DURATION_SCALE == 0`.
Use `motion.dur(x)` → returns 0 when reduced; skip stagger/press-scale/shimmer. Desktop has no OS flag →
drive the same flag from a Settings toggle (Phase 2).

## Glass recipe (`Glass.kt`)

`Surface(color = glass.surface)` → overlay `Modifier.background(glass.verticalTint())` →
`Modifier.border(dimens.hairlineWidth, glass.hairline, RoundedCornerShape(dimens.radiusLg))`.
Optional background-layer `Modifier.blur(glass.blurRadius)` gated by `glass.enableBlur` (Android 12+/`SDK_INT>=31`);
the flat tint + hairline is the load-bearing fallback.

## Typography (`Type.kt`) — Roboto (platform default)

All text uses **Roboto** (the platform default). Inter/Geist were trialled and removed per user preference.

- `AppTypography` (no explicit fontFamily → Roboto): headlineSmall 24 Bold · titleMedium 18 Bold ·
  bodyLarge 15 Normal · labelLarge 14 Medium · bodySmall 12 Normal. Mirrored in XML by `TextAppearance.App.*`.
- `TabularFigures = TextStyle(fontFeatureSettings = "tnum")` — merge onto a base style for pure-number fields so
  ticking values don't reflow; for mixed strings use `String.withInterNumerals()` (`MixedTypeText.kt`, now Roboto+tnum).
- The `inter_variable` / `geist_variable` font resources remain in `res/font/` but are unused.
