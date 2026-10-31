package com.cowork.bikerecoder.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * A "?" that explains [topic] with [text] in a bubble under it while [open]. The icon is 18 dp in a 48 dp touch target and
 * a screen reader reads it "도움말: $topic". A tap on the icon calls [onToggle]; so does a tap outside the bubble or Back
 * (the bubble takes that tap, so a tap on another "?" only closes this one). The screen keeps which tip is open
 * (one at a time). The bubble is at most 60 % of the screen high and scrolls beyond that.
 */
@Composable
fun HelpTip(topic: String, text: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        IconButton(onClick = onToggle, modifier = Modifier.size(48.dp).testTag(StatsTags.help(topic))) {
            Icon(
                Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = "도움말: $topic",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            Popup(
                popupPositionProvider = BelowAnchor,
                onDismissRequest = onToggle,
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .widthIn(max = 320.dp)
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)
                        .testTag(StatsTags.HELP_TEXT),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        // A long text at a large font scrolls inside the bubble.
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    )
                }
            }
        }
    }
}

/** Under the icon, centred on it and kept inside the window; above it when there is no room below. */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val below = anchorBounds.bottom
        val y = if (below + popupContentSize.height <= windowSize.height) below else maxOf(0, anchorBounds.top - popupContentSize.height)
        return IntOffset(x, y)
    }
}

/** The words of the "?" bubbles (V1.4.0 stats tab), by topic. */
object StatsHelp {
    const val GOAL_TOPIC = "목표"
    const val GOAL =
        "주간 목표는 월요일부터 일요일까지, 연간 목표는 1월 1일부터 탄 거리입니다. " +
            "'연말 예상'은 올해 하루 평균 거리에 올해 날 수를 곱한 값으로, 1월 7일부터 보입니다."

    const val STREAK_TOPIC = "연속 주행"
    const val STREAK =
        "한 번 이상 탄 주가 끊기지 않고 이어진 주 수입니다. 주는 월요일부터 일요일까지입니다. " +
            "이번 주에 아직 안 탔다면 지난주까지 센 수를 보여 줍니다."

    const val TOTALS_TOPIC = "주행·이동 시간·상승 고도"
    const val TOTALS =
        "주행은 10 m 이상 달리고 끝난 기록 하나를 1회로 셉니다. 이동 시간은 멈춰 있던 시간(일시정지)을 뺀, 실제로 달린 시간입니다. " +
            "1시간 중 15분을 쉬었다면 45분입니다. 상승 고도는 해발 높이가 아니라 오르막에서 올라간 높이를 모두 더한 값입니다. " +
            "10 m를 올랐다가 내려와 다시 20 m를 오르면 30 m입니다."

    const val COMPARISON_TOPIC = "비교"
    const val COMPARISON =
        "이번 기간은 오늘까지 지난 날 수만큼만, 앞 기간의 같은 날까지와 비교합니다. 이미 끝난 기간은 앞 기간 전체와 비교합니다."

    const val CHART_TOPIC = "그래프"
    const val CHART =
        "막대 하나는 그 칸(요일·주·달·해)에 탄 합계입니다. 위의 [거리] [시간] [상승 고도]로 보는 값을 바꿉니다. " +
            "막대를 누르면 값이 크게 보입니다."

    const val RECORDS_TOPIC = "개인 기록"
    const val RECORDS =
        "처음 기록부터 지금까지 전체에서 가장 좋은 기록입니다. 평균 속도는 5 km 이상 탄 주행만 셉니다. " +
            "같은 기록이면 먼저 세운 것을 남깁니다."
}
