package com.joseph.clipboard.windows.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joseph.clipboard.windows.theme.Dimens
import com.joseph.clipboard.windows.theme.LocalExtraColors

/*
 * The Windows glass component kit — the M2 counterpart of the Android ui/components surfaces/buttons.
 * Everything here leans on Modifier.glassSurface + Modifier.pressScale so the slate+emerald glass
 * language is consistent and one-line at every call site.
 */

/** Static or tappable glass card (fill + hairline + press-scale when [onClick] is set). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(Dimens.radiusLg),
    fill: Color = MaterialTheme.colors.surface,
    contentPadding: PaddingValues = PaddingValues(Dimens.spaceLg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var m = modifier
    if (onClick != null) m = m.pressScale(interaction)
    m = m.glassSurface(shape, fill)
    if (onClick != null) {
        m = m.clickable(
            interactionSource = interaction,
            indication = rememberRipple(),
        ) { onClick() }
    }
    Column(modifier = m.padding(contentPadding), content = content)
}

/** A titled glass card: header row (optional icon + title + optional trailing) then content. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = LocalExtraColors.current.textSecondary,
                    modifier = Modifier.padding(end = Dimens.spaceSm).size(Dimens.iconMd),
                )
            }
            Text(title, style = MaterialTheme.typography.subtitle1, color = MaterialTheme.colors.onSurface, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(Dimens.spaceMd))
        content()
    }
}

/** Bento metric tile: optional accent icon chip, big value, small label. */
@Composable
fun BentoTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = LocalExtraColors.current.green,
    valueColor: Color = MaterialTheme.colors.onSurface,
    onClick: (() -> Unit)? = null,
) {
    GlassCard(modifier = modifier, onClick = onClick) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(Dimens.radiusSm))
                    .background(LocalExtraColors.current.accentMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(Dimens.iconSm))
            }
            Spacer(Modifier.height(Dimens.spaceMd))
        }
        Text(value, style = MaterialTheme.typography.h5, color = valueColor)
        Text(label, style = MaterialTheme.typography.caption, color = LocalExtraColors.current.textSecondary)
    }
}

// ---- Buttons ----------------------------------------------------------------------------------

@Composable
private fun BaseButton(
    text: String,
    onClick: () -> Unit,
    fill: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    border: Color? = null,
    enabled: Boolean = true,
    height: Dp = Dimens.buttonHeightLg,
    shape: Shape = RoundedCornerShape(Dimens.radiusMd),
) {
    val interaction = remember { MutableInteractionSource() }
    var m = modifier.height(height)
    if (enabled) m = m.pressScale(interaction)
    m = m.clip(shape).background(if (enabled) fill else fill.copy(alpha = 0.4f))
    if (border != null) m = m.border(Dimens.hairlineWidth, border, shape)
    m = m.clickable(
        interactionSource = interaction,
        indication = rememberRipple(),
        enabled = enabled,
    ) { onClick() }
    Box(modifier = m, contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.button,
            fontWeight = FontWeight.Bold,
            color = if (enabled) contentColor else contentColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Dimens.spaceLg),
        )
    }
}

/** Primary CTA — green fill, dark label. One per screen. */
@Composable
fun AppButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    BaseButton(text, onClick, fill = LocalExtraColors.current.green, contentColor = MaterialTheme.colors.onPrimary, modifier = modifier, enabled = enabled)

/** Subordinate action — muted-green fill, green label. */
@Composable
fun AppTonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    BaseButton(text, onClick, fill = LocalExtraColors.current.accentMuted, contentColor = LocalExtraColors.current.greenText, modifier = modifier, enabled = enabled)

/** Outline-only secondary action. */
@Composable
fun AppSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    BaseButton(text, onClick, fill = Color.Transparent, contentColor = MaterialTheme.colors.onSurface, border = LocalExtraColors.current.divider, modifier = modifier, enabled = enabled)

/** Destructive action — error fill, white label, visually separated from the green CTA. */
@Composable
fun AppDestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    BaseButton(text, onClick, fill = LocalExtraColors.current.error, contentColor = Color.White, modifier = modifier, enabled = enabled)

/** Compact pill button (hairline border) for inline list/sheet actions. */
@Composable
fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colors.onSurface,
    fill: Color = MaterialTheme.colors.surface,
) = BaseButton(
    text, onClick,
    fill = fill,
    contentColor = contentColor,
    border = LocalExtraColors.current.divider,
    modifier = modifier,
    height = 40.dp,
    shape = RoundedCornerShape(Dimens.radiusPill),
)

// ---- Rows & states ----------------------------------------------------------------------------

/**
 * Glass slider switch — desktop port of the Android DesignModeToggle compact (no-labels) form: one
 * glass thumb slides across the track, red on the left = off, green on the right = on, so meaning
 * is carried by position + colour and no translated On/Off word is needed. Click-to-toggle only —
 * the Android drag gesture leans on requestDisallowInterceptTouchEvent, which has no desktop
 * counterpart.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalExtraColors.current
    val shape = RoundedCornerShape(Dimens.radiusSm)
    val bias by animateFloatAsState(
        targetValue = if (checked) 1f else -1f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
    )
    // Accent crossfades red→green as the thumb slides, matching the Android form.
    val accent = lerp(extra.error, extra.greenText, ((bias + 1f) / 2f).coerceIn(0f, 1f))
    Box(
        modifier = modifier
            .size(width = 64.dp, height = 30.dp)
            .clip(shape)
            .background(MaterialTheme.colors.surface)
            .border(Dimens.hairlineWidth, extra.divider, shape)
            // toggleable (not clickable) so the stock Switch's accessibility contract survives the
            // swap: Role.Switch + on/off state stay announced to screen readers.
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .align(BiasAlignment(horizontalBias = bias, verticalBias = 0f))
                .fillMaxWidth(0.5f)
                .fillMaxHeight()
                .clip(shape)
                .background(accent.copy(alpha = 0.12f))
                .border(1.5.dp, accent, shape),
        )
    }
}

/** A label + [GlassSwitch] row. Drop inside a [SectionCard]. */
@Composable
fun GlassToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onSurface, modifier = Modifier.weight(1f))
        GlassSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Centered empty-state: icon, title, optional subtitle (+ optional pairing-style action) — Android parity. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Dimens.spaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = LocalExtraColors.current.textSecondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(Dimens.spaceLg))
        Text(title, style = MaterialTheme.typography.subtitle1, color = MaterialTheme.colors.onSurface, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(Dimens.spaceXs))
            Text(subtitle, style = MaterialTheme.typography.body2, color = LocalExtraColors.current.textSecondary, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Dimens.spaceLg))
            // Same transparent, hairline-outlined pill as the System Status Pair button,
            // 20% under the 56dp CTA baseline.
            BaseButton(
                actionLabel, onAction,
                fill = Color.Transparent,
                contentColor = LocalExtraColors.current.greenText,
                border = LocalExtraColors.current.divider,
                height = EMPTY_ACTION_HEIGHT,
                shape = RoundedCornerShape(Dimens.radiusPill),
            )
        }
    }
}

private val EMPTY_ACTION_HEIGHT = 45.dp

/** Hairline horizontal divider in the theme divider color. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.hairlineWidth)
            .background(LocalExtraColors.current.divider),
    )
}

/**
 * Pill segmented control (track + segments); selected segment is solid green. Colors default to
 * the active theme's tokens; call sites on a fixed-dark surface (the power menu) pass dark tokens
 * explicitly, because the theme may resolve light there.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colors.surface,
    borderColor: Color = LocalExtraColors.current.divider,
    selectedFill: Color = LocalExtraColors.current.green,
    selectedLabelColor: Color = MaterialTheme.colors.onPrimary,
    labelColor: Color = LocalExtraColors.current.textSecondary,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Dimens.radiusPill))
            .background(fill)
            .border(Dimens.hairlineWidth, borderColor, RoundedCornerShape(Dimens.radiusPill))
            .padding(Dimens.spaceXxs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxs),
    ) {
        options.forEachIndexed { i, option ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Dimens.radiusPill))
                    .background(if (selected) selectedFill else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.button,
                    color = if (selected) selectedLabelColor else labelColor,
                )
            }
        }
    }
}
