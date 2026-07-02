# Design Tokens — Source of Truth

Emerald accent on a neutral near-black grey (dark) / light slate (light) base — "glass" design system
(Linear/Vercel aesthetic). This file is the **single human-readable source of truth**. The same tokens are encoded on three parallel surfaces that must stay in lockstep —
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
| background | `#F8FAFC` | `#141414` | `*Background` | `appBackgroundColor` |
| card | `#FFFFFF` | `#1F1F1F` | `*Card` | `appCardColor` |
| surfaceElevated | `#F8FAFC` | `#2A2A2A` | `*SurfaceElevated` | `appSurfaceElevated` |
| textPrimary | `#0F172A` | `#F5F5F5` | `*TextPrimary` | `appTextPrimaryColor` |
| textSecondary | `#475569` | `#A3A3A3` | `*TextSecondary` | `appTextSecondaryColor` |
| divider | `#E2E8F0` | `#303030` | `*Divider` | `appDividerColor` |
| hairline | `#0F172A @6%` (`0x0F0F172A`) | `#FFFFFF @8%` (`0x14FFFFFF`) | `*Hairline` | `appHairline` |
| scrim | `#000000 @40%` (`0x66000000`) | `#000000 @72%` (`0xB8000000`) | `*Scrim` | `appScrim` |
| navBar | `#FFFFFF` | `#171717` | `*NavBar` | `appNavBarColor` |
| navIcon | `#475569` | `#A3A3A3` | `*NavIcon` | `appNavIconColor` |
| bottomSheet | `#FFFFFF` | `#1F1F1F` | `*BottomSheet` | `appBottomSheetColor` |
| gradientTop | `#FFFFFF` | `#1A1A1A` | `*GradientTop` | — (Compose-only) |
| gradientBottom | `#F1F5F9` | `#0D0D0D` | `*GradientBottom` | — (Compose-only) |
| green (fill) | `#34D399` | `#22C55E` | `*Green` | `appGreen` |
| greenText (WCAG) | `#047857` | `#4ADE88` | `*GreenText` | `appGreenText` |
| accentMuted | `#34D399 @10%` (`0x1A34D399`) | `#22C55E @14%` (`0x2422C55E`) | `*AccentMuted` | — (Compose-only) |
| success | `#047857` | `#34D399` | `*Success` | `appSuccess` |
| info | `#1D4ED8` | `#60A5FA` | `*Info` | `appInfo` |
| warning | `#B45309` | `#FBBF24` | `*Warning` | `appWarning` |
| error / destructive | `#DC2626` | `#EF4444` | `*Error` | `appError` |

**Green fills:** the light fill `#34D399` doubles as the brand mark (launcher icon / logo); dark fill `#22C55E`.
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

## Typography (`Type.kt`) — two voices: Plex content, Geist controls

Content text (display/headline/title/body slots) uses **IBM Plex Sans** (`res/font/ibm_plex_sans_variable`,
Latin + Cyrillic); control text (label* slots, buttons, chips) stays **Geist** (`res/font/geist_variable`).
Mirrored on the Windows client (`theme/Type.kt`). History: Roboto → Geist everywhere → this split
(Geist-everywhere read as the generic AI-product look).

- `AppTypography`: headlineSmall 24 Bold · titleMedium 18 Bold · bodyLarge 15 Normal (Plex) ·
  labelLarge 14 Medium (Geist) · bodySmall 12 Normal (Plex). Mirrored in XML by `TextAppearance.App.*`
  (Label = Geist, rest = Plex); theme default fontFamily = Plex; `Widget.App.Button`/`Widget.App.HowToUse` = Geist.
- `TabularFigures = TextStyle(fontFamily = PlexSans, fontFeatureSettings = "tnum")` — merge onto a base style for
  pure-number fields so ticking values don't reflow; for mixed strings use `String.withInterNumerals()` (`MixedTypeText.kt`).
- `inter_variable` stays bundled in `res/font/` as a one-line swap candidate.
