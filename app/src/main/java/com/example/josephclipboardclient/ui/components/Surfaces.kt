package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

/** Glass card on the app's card colour — tint sheen + hairline border, 16dp radius. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(AppThemeTokens.dimens.radiusLg)
    Column(
        modifier
            .glassSurface(shape)
            .padding(AppThemeTokens.dimens.spaceLg),
        content = content,
    )
}

/** Interactive glass card — adds press-scale + click. Use for tappable bento/list surfaces. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(AppThemeTokens.dimens.radiusLg)
    val interaction = remember { MutableInteractionSource() }
    val base = if (onClick != null) {
        Modifier
            .pressScale(interaction)
            // role = Button so TalkBack announces the whole tappable card/tile as a button (clickable
            // already merges the card's text descendants into this one node). Central a11y fix for
            // every GlassCard-based surface (bento tiles, tappable list rows).
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Column(
        modifier
            .then(base)
            .glassSurface(shape)
            .padding(AppThemeTokens.dimens.spaceLg),
        content = content,
    )
}

/** Titled glass card: header row (icon + title + optional trailing) over content. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AppThemeTokens.colors.navIcon,
                    modifier = Modifier.size(AppThemeTokens.dimens.iconMd),
                )
                Spacer(Modifier.width(AppThemeTokens.dimens.spaceSm))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AppThemeTokens.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) trailing()
        }
        Spacer(Modifier.height(AppThemeTokens.dimens.spaceMd))
        content()
    }
}

/** Section/screen header: optional leading icon + headline. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AppThemeTokens.colors.navIcon,
                modifier = Modifier.size(AppThemeTokens.dimens.headerIcon),
            )
            Spacer(Modifier.width(AppThemeTokens.dimens.spaceSm))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = AppThemeTokens.colors.textPrimary,
        )
    }
}

/** Themed divider visible in both light and dark. */
@Composable
fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = AppThemeTokens.colors.divider)
}
