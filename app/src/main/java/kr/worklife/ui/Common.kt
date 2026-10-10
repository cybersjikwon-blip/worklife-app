package kr.worklife.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

// One UI 느낌: 연회색 배경 + 큰 라운드 흰 카드 + 큰 제목
private val Blue = Color(0xFF3E91FF)
private val LightColors = lightColorScheme(
    primary = Blue, onPrimary = Color.White, secondary = Color(0xFF0FA36B), tertiary = Color(0xFFFF8A3D),
    background = Color(0xFFF4F4F6), surface = Color.White, surfaceVariant = Color(0xFFEDEEF2),
    onBackground = Color(0xFF111111), onSurface = Color(0xFF111111), error = Color(0xFFE5484D),
    primaryContainer = Color(0xFFDCEAFF), onPrimaryContainer = Color(0xFF1F55AD),
    secondaryContainer = Color(0xFFDCEAFF), onSecondaryContainer = Color(0xFF1F55AD),
    tertiaryContainer = Color(0xFFFFEBDD), onTertiaryContainer = Color(0xFF8A3A08),
    outline = Color(0xFFB4B5BA), outlineVariant = Color(0xFFE4E5E9),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FA4FF), secondary = Color(0xFF3DDC97), tertiary = Color(0xFFFFA463),
    background = Color(0xFF000000), surface = Color(0xFF17171A), surfaceVariant = Color(0xFF26262B),
    error = Color(0xFFFF6B6E),
    primaryContainer = Color(0xFF1C3A66), onPrimaryContainer = Color(0xFFDCEAFF),
    secondaryContainer = Color(0xFF1C3A66), onSecondaryContainer = Color(0xFFDCEAFF),
)

@Composable
fun WorkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

val OkGreen: Color @Composable get() = MaterialTheme.colorScheme.secondary
val OtOrange: Color @Composable get() = MaterialTheme.colorScheme.tertiary

fun won(v: Int) = "%,d원".format(v)
fun hmShort(m: Int) = if (m == 0) "0분" else if (m % 60 == 0) "${m / 60}시간" else if (m < 60) "${m}분" else "${m / 60}시간 ${m % 60}분"
fun wd(d: LocalDate) = "월화수목금토일"[d.dayOfWeek.value - 1].toString()

/** One UI 스타일 큰 제목 영역 */
@Composable
fun BigHeader(title: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 16.dp)) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        if (sub != null) Text(sub, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f),
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun SCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
    Card(
        modifier = if (onClick != null) m.clickable { onClick() } else m,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) { Column(Modifier.padding(20.dp), content = content) }
}

@Composable
fun CardTitle(t: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun KV(k: String, v: String, bold: Boolean = false, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(k, Modifier.weight(1f), fontSize = 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(v, fontSize = 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color)
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Box(Modifier.background(color.copy(alpha = .14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MonthNav(ym: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { onChange(ym.minusMonths(1)) }) { Icon(Icons.Filled.KeyboardArrowLeft, "이전 달") }
        Text("${ym.year}년 ${ym.monthValue}월", fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onChange(YearMonth.now()) }.padding(horizontal = 12.dp))
        IconButton(onClick = { onChange(ym.plusMonths(1)) }, enabled = ym.isBefore(YearMonth.now().plusMonths(1))) {
            Icon(Icons.Filled.KeyboardArrowRight, "다음 달")
        }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false,
          hint: String? = null, modifier: Modifier = Modifier.fillMaxWidth()) {
    val kb = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text)
    val m = modifier.padding(vertical = 4.dp)
    // 안내문구 유무를 별도 호출로 분리 (nullable 컴포저블 람다 타입 추론 문제 회피)
    if (hint == null) {
        OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            modifier = m, keyboardOptions = kb)
    } else {
        OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            modifier = m, keyboardOptions = kb, supportingText = { Text(hint) })
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked, onChange)
    }
}

fun pickTime(ctx: Context, init: LocalTime, cb: (LocalTime) -> Unit) =
    TimePickerDialog(ctx, { _, h, m -> cb(LocalTime.of(h, m)) }, init.hour, init.minute, true).show()

fun pickDate(ctx: Context, init: LocalDate, cb: (LocalDate) -> Unit) =
    DatePickerDialog(ctx, { _, y, m, d -> cb(LocalDate.of(y, m + 1, d)) }, init.year, init.monthValue - 1, init.dayOfMonth).show()

/** 급여 산정기간 안내 줄: "10월분 · 9/21~10/20 · 급여일 10/23(금)" */
@Composable
fun PeriodLine(p: kr.worklife.core.PayPeriod) {
    Text("${p.label.monthValue}월분 · ${p.rangeText()} · 급여일 ${p.payDate.monthValue}/${p.payDate.dayOfMonth}(${wd(p.payDate)})",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
