package app.mono.finance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.mono.domain.Currency
import app.mono.domain.MonthSummary
import app.mono.domain.Money
import app.mono.domain.MoneyContext
import app.mono.domain.TxKind
import app.mono.domain.model.TransactionRecord
import app.mono.domain.theme.ColorMath
import app.mono.finance.ui.theme.Mono
import app.mono.finance.ui.theme.figure
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reading this local makes a composable redraw when Hide amounts or the currency changes. */
val LocalMoneyContext = compositionLocalOf { MoneyContext() }

/** The one way a screen turns an amount into text; it subscribes to Hide amounts. */
@Composable
fun fmt(cents: Long, signed: Boolean = false, currency: Currency? = null): String {
    LocalMoneyContext.current
    return Money.format(cents, signed = signed, currency = currency)
}

private fun Int.c() = Color(0xFF000000.toInt() or this)

/** SF Symbol names are stored in the data (shared with iOS); this maps them to Material icons. */
fun symbolIcon(symbol: String): ImageVector = when (symbol) {
    "fork.knife" -> Icons.Outlined.Restaurant
    "cart" -> Icons.Outlined.ShoppingCart
    "car" -> Icons.Outlined.DirectionsCar
    "book" -> Icons.Outlined.MenuBook
    "bag" -> Icons.Outlined.ShoppingBag
    "film" -> Icons.Outlined.Movie
    "arrow.triangle.2.circlepath" -> Icons.Outlined.Autorenew
    "airplane" -> Icons.Outlined.Flight
    "cross.case" -> Icons.Outlined.MedicalServices
    "doc.text" -> Icons.Outlined.Description
    "ellipsis" -> Icons.Outlined.MoreHoriz
    "banknote" -> Icons.Outlined.Payments
    "laptopcomputer" -> Icons.Outlined.Laptop
    "gift" -> Icons.Outlined.CardGiftcard
    "plus.circle" -> Icons.Outlined.AddCircleOutline
    "arrow.left.arrow.right" -> Icons.Outlined.SwapHoriz
    else -> Icons.Outlined.Label
}

fun Modifier.surface(padding: Dp = 16.dp): Modifier = composed {
    val c = Mono.colors
    this.clip(RoundedCornerShape(18.dp))
        .background(c.surface)
        .border(0.5.dp, c.cardLine, RoundedCornerShape(18.dp))
        .padding(padding)
}

@Composable
fun SectionTitle(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Mono.colors.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing, color = Mono.colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = onTrailing != null) { onTrailing?.invoke() }.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Mono.colors.line))
}

@Composable
fun StatTile(title: String, value: String, tint: Color = Mono.colors.ink, footnote: String? = null, modifier: Modifier = Modifier, valueSize: Int = 20) {
    val c = Mono.colors
    Column(modifier.surface(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = c.inkSecondary, fontSize = 13.sp)
        Text(value, color = tint, style = figure(valueSize, FontWeight.Medium), maxLines = 1, softWrap = false)
        if (footnote != null) Text(footnote, color = c.inkTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ProgressLine(value: Double, tint: Color, height: Dp = 6.dp, modifier: Modifier = Modifier) {
    val c = Mono.colors
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(c.line)) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0.0, 1.0).toFloat()).height(height).clip(CircleShape).background(tint))
    }
}

@Composable
fun RingGauge(value: Double, tint: Color, modifier: Modifier = Modifier, lineWidth: Dp = 11.dp, center: @Composable () -> Unit) {
    val track = Mono.colors.line
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = lineWidth.toPx()
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            drawArc(tint, -90f, (360 * value.coerceIn(0.0, 1.0)).toFloat(), false, Offset(inset, inset), arcSize,
                style = Stroke(w, cap = StrokeCap.Round))
        }
        center()
    }
}

/** Muted slice colours built from the active palette, so charts never go neon. */
@Composable
fun chartRamp(): List<Color> {
    val c = Mono.colors
    fun mix(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)
    return listOf(c.accent, c.secondary, c.primary, mix(c.accent, c.surface, 0.45f), mix(c.secondary, c.surface, 0.45f), mix(c.primary, c.surface, 0.5f))
}

@Composable
fun IncomeExpenseChart(months: List<MonthSummary>, modifier: Modifier = Modifier) {
    val c = Mono.colors
    LocalMoneyContext.current
    val max = (months.maxOfOrNull { maxOf(it.income, it.expense) } ?: 0L).coerceAtLeast(1L)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            months.forEach { m ->
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Bar(m.income.toFloat() / max, c.accent)
                    Bar(m.expense.toFloat() / max, c.secondary)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            months.forEach { Text(it.month.shortTitle(), color = c.inkTertiary, fontSize = 11.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("Income", c.accent); Legend("Expenses", c.secondary)
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    val h = (120 * fraction.coerceIn(0f, 1f)).coerceAtLeast(if (fraction > 0f) 2f else 0f)
    Box(Modifier.size(width = 14.dp, height = h.dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(color))
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, color = Mono.colors.inkSecondary, fontSize = 12.sp)
    }
}

@Composable
fun CategoryDonut(slices: List<Pair<String, Long>>, modifier: Modifier = Modifier) {
    val ramp = chartRamp()
    val total = slices.sumOf { it.second }.coerceAtLeast(1L)
    Canvas(modifier.size(150.dp)) {
        val w = 24.dp.toPx()
        val arcSize = Size(size.width - w, size.height - w)
        var start = -90f
        slices.forEachIndexed { i, s ->
            val sweep = 360f * s.second / total
            drawArc(ramp[i % ramp.size], start, (sweep - 1.5f).coerceAtLeast(0.5f), false, Offset(w / 2, w / 2), arcSize, style = Stroke(w))
            start += sweep
        }
    }
}

@Composable
fun CategoryBreakdownList(slices: List<Pair<String, Long>>, limit: Int = 5) {
    val ramp = chartRamp()
    val total = slices.sumOf { it.second }.coerceAtLeast(1L)
    val c = Mono.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slices.take(limit).forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(ramp[i % ramp.size]))
                Text(s.first, color = c.ink, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1)
                Text("${(100.0 * s.second / total).roundToInt()}%", color = c.inkTertiary, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                Text(fmt(s.second), color = c.ink, style = figure(15))
            }
        }
    }
}

@Composable
fun TransactionRow(tx: TransactionRecord, symbol: String, modifier: Modifier = Modifier) {
    val c = Mono.colors
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(tx.date).atZone(zone).toLocalDate()
        .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    val kind = tx.kind
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(symbolIcon(if (kind == TxKind.TRANSFER) "arrow.left.arrow.right" else symbol), null, tint = c.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            val title = tx.title.ifBlank { tx.category.ifBlank { kind.label } }
            Text(title, color = c.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(tx.category.takeIf { it.isNotBlank() && it != title }, date).filterNotNull().joinToString(" · "),
                color = c.inkSecondary, fontSize = 13.sp, maxLines = 1,
            )
        }
        val signed = when (kind) { TxKind.INCOME -> tx.amountCents; TxKind.EXPENSE -> -tx.amountCents; TxKind.TRANSFER -> tx.amountCents }
        Text(
            fmt(signed, signed = kind == TxKind.INCOME),
            color = if (kind == TxKind.INCOME) c.income else c.ink,
            style = figure(16, FontWeight.Medium),
        )
    }
}
