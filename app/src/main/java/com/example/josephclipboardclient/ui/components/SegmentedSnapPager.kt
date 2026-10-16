package com.EntropicSoftwareLab.ClipboardShare.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.ClipboardShare.ui.theme.AppThemeTokens

private class SnapPullConnection(
    private val state: PagerState,
    private val segmentCount: Int,
    private val thresholdPx: Float,
    private val resistance: Float
) : NestedScrollConnection {
    var pull = 0f
    var startPage = 0

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && pull != 0f) {
            val delta = -available.y * resistance
            var newPull = pull + delta
            if ((newPull > 0f) != (pull > 0f) || newPull == 0f) newPull = 0f
            val consumed = state.dispatchRawDelta(newPull - pull)
            pull += consumed
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) {
            if (pull == 0f) startPage = state.settledPage
            val delta = -available.y * resistance
            pull += state.dispatchRawDelta(delta)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (pull != 0f) {
            val target = when {
                pull >= thresholdPx -> startPage + 1
                pull <= -thresholdPx -> startPage - 1
                else -> startPage
            }.coerceIn(0, segmentCount - 1)
            pull = 0f
            state.animateScrollToPage(
                target,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
            )
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (state.currentPageOffsetFraction != 0f) {
            state.animateScrollToPage(state.currentPage)
            return available
        }
        return Velocity.Zero
    }
}

/**
 * Full-height vertical segments with a sticky snap. Inside a segment the inner list scrolls normally;
 * once it hits its edge, further dragging is resisted and has to pass [advanceThreshold] to move to the
 * next segment, otherwise it springs back. Flings never cross a boundary, and it never rests between two.
 */
@Composable
fun SegmentedSnapPager(
    segmentCount: Int,
    modifier: Modifier = Modifier,
    state: PagerState = rememberPagerState { segmentCount },
    advanceThreshold: Dp = 140.dp,
    resistance: Float = 0.45f,
    content: @Composable (segment: Int) -> Unit
) {
    val thresholdPx = with(LocalDensity.current) { advanceThreshold.toPx() }
    val connection = remember(state, segmentCount, thresholdPx, resistance) {
        SnapPullConnection(state, segmentCount, thresholdPx, resistance)
    }
    VerticalPager(
        state = state,
        userScrollEnabled = false,
        beyondViewportPageCount = 1,
        modifier = modifier.nestedScroll(connection)
    ) { page ->
        Box(Modifier.fillMaxSize()) { content(page) }
    }
}

/** Vertical pill indicator for [SegmentedSnapPager]; the active segment is the tall green pill. */
@Composable
fun SegmentDots(state: PagerState, count: Int, modifier: Modifier = Modifier) {
    val colors = AppThemeTokens.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            val active = index == state.currentPage
            val h by animateDpAsState(if (active) 16.dp else 6.dp, label = "segmentDot")
            Box(
                Modifier
                    .width(6.dp)
                    .height(h)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) colors.green else colors.textSecondary.copy(alpha = 0.4f))
            )
        }
    }
}
