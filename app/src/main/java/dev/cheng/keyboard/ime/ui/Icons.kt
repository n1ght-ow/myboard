package dev.cheng.keyboard.ime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.ShiftState
import kotlin.math.cos
import kotlin.math.sin

private fun DrawScope.iconStroke(scale: Float = 1f): Stroke = Stroke(
    width = size.minDimension * (1.85f / 24f) * scale,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

private fun DrawScope.pt(x: Float, y: Float): Offset = Offset(
    x = size.width * (x / 24f),
    y = size.height * (y / 24f),
)

private fun DrawScope.w(value: Float): Float = size.width * (value / 24f)
private fun DrawScope.h(value: Float): Float = size.height * (value / 24f)
private fun DrawScope.r(value: Float): Float = size.minDimension * (value / 24f)

@Composable
fun AskIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke()
        val bubble = Path().apply {
            moveTo(w(8.2f), h(4.4f))
            lineTo(w(15.8f), h(4.4f))
            quadraticTo(w(20.0f), h(4.4f), w(20.0f), h(8.6f))
            lineTo(w(20.0f), h(12.8f))
            quadraticTo(w(20.0f), h(17.0f), w(15.8f), h(17.0f))
            lineTo(w(10.2f), h(17.0f))
            lineTo(w(6.0f), h(20.0f))
            lineTo(w(6.8f), h(16.6f))
            quadraticTo(w(4.0f), h(15.6f), w(4.0f), h(12.6f))
            lineTo(w(4.0f), h(8.6f))
            quadraticTo(w(4.0f), h(4.4f), w(8.2f), h(4.4f))
            close()
        }
        drawPath(bubble, color, style = stroke)
        drawLine(color, pt(8.4f, 9.4f), pt(15.6f, 9.4f), stroke.width, StrokeCap.Round)
        drawLine(color, pt(8.4f, 12.6f), pt(13.0f, 12.6f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun TranslateIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke(0.94f)
        drawLine(color, pt(8.0f, 3.6f), pt(8.0f, 5.4f), stroke.width, StrokeCap.Round)
        drawLine(color, pt(3.6f, 5.8f), pt(12.6f, 5.8f), stroke.width, StrokeCap.Round)
        val leftStroke = Path().apply {
            moveTo(w(10.6f), h(6.0f))
            quadraticTo(w(9.0f), h(10.4f), w(4.0f), h(13.2f))
        }
        val rightStroke = Path().apply {
            moveTo(w(5.6f), h(7.4f))
            quadraticTo(w(8.2f), h(10.6f), w(12.0f), h(13.0f))
        }
        drawPath(leftStroke, color, style = stroke)
        drawPath(rightStroke, color, style = stroke)

        val latinA = Path().apply {
            moveTo(w(12.4f), h(20.0f))
            lineTo(w(16.2f), h(11.0f))
            lineTo(w(20.0f), h(20.0f))
        }
        drawPath(latinA, color, style = stroke)
        drawLine(color, pt(13.7f, 17.2f), pt(18.7f, 17.2f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun PolishIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke(0.95f)
        val spark = Path().apply {
            moveTo(w(10.6f), h(4.8f))
            quadraticTo(w(10.6f), h(12.6f), w(18.4f), h(12.6f))
            quadraticTo(w(10.6f), h(12.6f), w(10.6f), h(20.4f))
            quadraticTo(w(10.6f), h(12.6f), w(2.8f), h(12.6f))
            quadraticTo(w(10.6f), h(12.6f), w(10.6f), h(4.8f))
            close()
        }
        drawPath(spark, color, style = stroke)
        val mini = iconStroke(0.82f)
        drawLine(color, pt(18.4f, 3.6f), pt(18.4f, 8.0f), mini.width, StrokeCap.Round)
        drawLine(color, pt(16.2f, 5.8f), pt(20.6f, 5.8f), mini.width, StrokeCap.Round)
    }
}

@Composable
fun DismissIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(w(6.2f), h(9.4f))
            lineTo(w(12.0f), h(15.0f))
            lineTo(w(17.8f), h(9.4f))
        }
        drawPath(path, color, style = iconStroke(1.05f))
    }
}

@Composable
fun HideKeyboardIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke()
        val chevron = Path().apply {
            moveTo(w(7.0f), h(7.6f))
            lineTo(w(12.0f), h(12.6f))
            lineTo(w(17.0f), h(7.6f))
        }
        drawPath(chevron, color, style = stroke)
        drawLine(color, pt(6.6f, 17.0f), pt(17.4f, 17.0f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun GridIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke(0.95f)
        val cellSize = Size(w(6.6f), h(6.6f))
        val corner = CornerRadius(r(2.0f))
        drawRoundRect(color, topLeft = pt(4.0f, 4.0f), size = cellSize, cornerRadius = corner, style = stroke)
        drawRoundRect(color, topLeft = pt(13.4f, 4.0f), size = cellSize, cornerRadius = corner, style = stroke)
        drawRoundRect(color, topLeft = pt(4.0f, 13.4f), size = cellSize, cornerRadius = corner, style = stroke)
        drawCircle(color, radius = r(3.3f), center = pt(16.7f, 16.7f), style = stroke)
    }
}

@Composable
fun ShiftIcon(color: Color, state: ShiftState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val caps = state == ShiftState.Caps
        val dy = if (caps) -1.4f else 0f
        val stemBottom = if (caps) 16.8f else 18.4f
        val path = Path().apply {
            moveTo(w(12.0f), h(4.4f + dy))
            lineTo(w(19.2f), h(11.8f + dy))
            lineTo(w(15.0f), h(11.8f + dy))
            lineTo(w(15.0f), h(stemBottom))
            lineTo(w(9.0f), h(stemBottom))
            lineTo(w(9.0f), h(11.8f + dy))
            lineTo(w(4.8f), h(11.8f + dy))
            close()
        }
        if (state == ShiftState.Off) {
            drawPath(path, color, style = iconStroke())
        } else {
            drawPath(path, color)
            drawPath(path, color, style = iconStroke(0.72f))
        }
        if (caps) {
            drawLine(color, pt(8.4f, 20.0f), pt(15.6f, 20.0f), r(2.0f), StrokeCap.Round)
        }
    }
}

@Composable
fun BackspaceIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke()
        val path = Path().apply {
            moveTo(w(8.8f), h(5.6f))
            lineTo(w(18.4f), h(5.6f))
            quadraticTo(w(20.8f), h(5.6f), w(20.8f), h(8.0f))
            lineTo(w(20.8f), h(16.0f))
            quadraticTo(w(20.8f), h(18.4f), w(18.4f), h(18.4f))
            lineTo(w(8.8f), h(18.4f))
            quadraticTo(w(7.6f), h(18.4f), w(6.8f), h(17.5f))
            lineTo(w(3.5f), h(13.2f))
            quadraticTo(w(2.6f), h(12.0f), w(3.5f), h(10.8f))
            lineTo(w(6.8f), h(6.5f))
            quadraticTo(w(7.6f), h(5.6f), w(8.8f), h(5.6f))
            close()
        }
        drawPath(path, color, style = stroke)
        val cross = iconStroke(0.92f)
        drawLine(color, pt(11.6f, 9.8f), pt(16.0f, 14.2f), cross.width, StrokeCap.Round)
        drawLine(color, pt(16.0f, 9.8f), pt(11.6f, 14.2f), cross.width, StrokeCap.Round)
    }
}

@Composable
fun GlobeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke(0.92f)
        drawCircle(color, radius = r(8.2f), center = pt(12.0f, 12.0f), style = stroke)
        drawLine(color, pt(4.0f, 12.0f), pt(20.0f, 12.0f), stroke.width, StrokeCap.Round)
        drawOval(color, topLeft = pt(8.4f, 3.8f), size = Size(w(7.2f), h(16.4f)), style = stroke)
    }
}

@Composable
fun ClipboardIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke(0.95f)
        val board = Path().apply {
            moveTo(w(8.6f), h(6.0f))
            lineTo(w(7.4f), h(6.0f))
            quadraticTo(w(5.4f), h(6.0f), w(5.4f), h(8.0f))
            lineTo(w(5.4f), h(18.2f))
            quadraticTo(w(5.4f), h(20.2f), w(7.4f), h(20.2f))
            lineTo(w(16.6f), h(20.2f))
            quadraticTo(w(18.6f), h(20.2f), w(18.6f), h(18.2f))
            lineTo(w(18.6f), h(8.0f))
            quadraticTo(w(18.6f), h(6.0f), w(16.6f), h(6.0f))
            lineTo(w(15.4f), h(6.0f))
        }
        drawPath(board, color, style = stroke)
        val tab = Path().apply {
            addRoundRect(
                RoundRect(
                    left = w(8.8f),
                    top = h(3.8f),
                    right = w(15.2f),
                    bottom = h(7.6f),
                    cornerRadius = CornerRadius(r(1.6f)),
                ),
            )
        }
        drawPath(tab, color, style = stroke)
        drawLine(color, pt(8.8f, 11.8f), pt(15.2f, 11.8f), stroke.width, StrokeCap.Round)
        drawLine(color, pt(8.8f, 15.4f), pt(13.4f, 15.4f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun SettingsIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke(0.92f)
        val cx = w(12.0f)
        val cy = h(12.0f)
        val innerR = r(6.7f)
        val outerR = r(8.9f)
        val gear = Path()
        for (index in 0 until 8) {
            val base = index * 45.0
            val a0 = Math.toRadians(base - 14.0)
            val a1 = Math.toRadians(base - 8.5)
            val a2 = Math.toRadians(base - 5.2)
            val a3 = Math.toRadians(base + 5.2)
            val a4 = Math.toRadians(base + 8.5)
            val p0 = Offset(cx + (innerR * cos(a0)).toFloat(), cy + (innerR * sin(a0)).toFloat())
            val p1 = Offset(cx + (innerR * cos(a1)).toFloat(), cy + (innerR * sin(a1)).toFloat())
            val p2 = Offset(cx + (outerR * cos(a2)).toFloat(), cy + (outerR * sin(a2)).toFloat())
            val p3 = Offset(cx + (outerR * cos(a3)).toFloat(), cy + (outerR * sin(a3)).toFloat())
            val p4 = Offset(cx + (innerR * cos(a4)).toFloat(), cy + (innerR * sin(a4)).toFloat())
            if (index == 0) gear.moveTo(p0.x, p0.y) else gear.lineTo(p0.x, p0.y)
            gear.lineTo(p1.x, p1.y)
            gear.lineTo(p2.x, p2.y)
            gear.lineTo(p3.x, p3.y)
            gear.lineTo(p4.x, p4.y)
        }
        gear.close()
        drawPath(gear, color, style = stroke)
        drawCircle(color, radius = r(2.8f), center = Offset(cx, cy), style = stroke)
    }
}

@Composable
fun CloseIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke()
        drawLine(color, pt(6.6f, 6.6f), pt(17.4f, 17.4f), stroke.width, StrokeCap.Round)
        drawLine(color, pt(17.4f, 6.6f), pt(6.6f, 17.4f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun CheckIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(w(5.0f), h(12.6f))
            lineTo(w(9.8f), h(17.4f))
            lineTo(w(19.2f), h(7.2f))
        }
        drawPath(path, color, style = iconStroke(1.08f))
    }
}

@Composable
fun KeyIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke(0.95f)
        drawCircle(color, radius = r(4.2f), center = pt(8.2f, 12.0f), style = stroke)
        drawLine(color, pt(12.4f, 12.0f), pt(20.4f, 12.0f), stroke.width, StrokeCap.Round)
        drawLine(color, pt(17.2f, 12.0f), pt(17.2f, 15.4f), stroke.width, StrokeCap.Round)
        drawLine(color, pt(20.0f, 12.0f), pt(20.0f, 14.8f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun PinIcon(color: Color, modifier: Modifier = Modifier, pinned: Boolean = false) {
    Canvas(modifier) {
        val stroke = iconStroke(0.95f)
        val head = Path().apply {
            moveTo(w(8.4f), h(4.6f))
            lineTo(w(15.6f), h(4.6f))
            lineTo(w(14.8f), h(10.0f))
            lineTo(w(17.4f), h(13.0f))
            lineTo(w(6.6f), h(13.0f))
            lineTo(w(9.2f), h(10.0f))
            close()
        }
        if (pinned) drawPath(head, color)
        drawPath(head, color, style = stroke)
        drawLine(color, pt(12.0f, 13.0f), pt(12.0f, 20.0f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun TrashIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke(0.95f)
        drawLine(color, pt(4.8f, 6.8f), pt(19.2f, 6.8f), stroke.width, StrokeCap.Round)
        val lid = Path().apply {
            moveTo(w(9.2f), h(6.8f))
            lineTo(w(9.8f), h(4.4f))
            lineTo(w(14.2f), h(4.4f))
            lineTo(w(14.8f), h(6.8f))
        }
        drawPath(lid, color, style = stroke)
        val body = Path().apply {
            moveTo(w(6.8f), h(6.8f))
            lineTo(w(7.6f), h(18.4f))
            quadraticTo(w(7.7f), h(20.0f), w(9.4f), h(20.0f))
            lineTo(w(14.6f), h(20.0f))
            quadraticTo(w(16.3f), h(20.0f), w(16.4f), h(18.4f))
            lineTo(w(17.2f), h(6.8f))
        }
        drawPath(body, color, style = stroke)
        drawLine(color, pt(10.4f, 10.6f), pt(10.6f, 16.2f), stroke.width, StrokeCap.Round)
        drawLine(color, pt(13.6f, 10.6f), pt(13.4f, 16.2f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun LockIcon(color: Color, modifier: Modifier = Modifier, locked: Boolean = false) {
    Canvas(modifier) {
        val stroke = iconStroke(0.95f)
        drawRoundRect(
            color,
            topLeft = pt(5.6f, 11.0f),
            size = Size(w(12.8f), h(9.0f)),
            cornerRadius = CornerRadius(r(2.4f)),
            style = stroke,
        )
        drawLine(color, pt(12.0f, 14.4f), pt(12.0f, 16.8f), stroke.width, StrokeCap.Round)
        val bow = Path()
        if (locked) {
            bow.moveTo(w(8.4f), h(11.0f))
            bow.lineTo(w(8.4f), h(7.8f))
            bow.arcTo(Rect(w(8.4f), h(4.2f), w(15.6f), h(11.4f)), 180f, 180f, false)
            bow.lineTo(w(15.6f), h(11.0f))
        } else {
            bow.moveTo(w(15.6f), h(11.0f))
            bow.lineTo(w(15.6f), h(7.4f))
            bow.arcTo(Rect(w(8.4f), h(3.8f), w(15.6f), h(11.0f)), 0f, -155f, false)
        }
        drawPath(bow, color, style = stroke)
    }
}

@Composable
fun CycleIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke(0.95f)
        val left = w(4.6f)
        val top = h(4.6f)
        val box = Size(w(14.8f), h(14.8f))
        drawArc(color, startAngle = 205f, sweepAngle = 130f, useCenter = false, topLeft = Offset(left, top), size = box, style = stroke)
        drawArc(color, startAngle = 25f, sweepAngle = 130f, useCenter = false, topLeft = Offset(left, top), size = box, style = stroke)
        arrowHead(color, stroke.width, pointOnOval(left, top, box, 335f), 335f + 90f)
        arrowHead(color, stroke.width, pointOnOval(left, top, box, 155f), 155f + 90f)
    }
}

@Composable
fun StackIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = iconStroke(0.95f)
        drawCircle(color, radius = r(8.0f), center = pt(12.0f, 12.0f), style = stroke)
        val hand = Path().apply {
            moveTo(w(12.0f), h(7.4f))
            lineTo(w(12.0f), h(12.2f))
            lineTo(w(15.4f), h(14.2f))
        }
        drawPath(hand, color, style = stroke)
    }
}

private fun DrawScope.arrowHead(
    color: Color,
    width: Float,
    tip: Offset,
    directionDegrees: Float,
) {
    val back = directionDegrees + 180.0
    val length = r(3.4f)
    val spread = 32.0
    fun wing(angle: Double): Offset {
        val rad = Math.toRadians(angle)
        return Offset(tip.x + (length * cos(rad)).toFloat(), tip.y + (length * sin(rad)).toFloat())
    }
    drawLine(color, tip, wing(back - spread), width, StrokeCap.Round)
    drawLine(color, tip, wing(back + spread), width, StrokeCap.Round)
}

private fun pointOnOval(left: Float, top: Float, box: Size, degrees: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return Offset(
        left + box.width / 2f + (box.width / 2f * cos(radians)).toFloat(),
        top + box.height / 2f + (box.height / 2f * sin(radians)).toFloat(),
    )
}

@Composable
fun EnterIcon(color: Color, modifier: Modifier = Modifier, kind: EnterKind = EnterKind.Newline) {
    Canvas(modifier) {
        val stroke = iconStroke(1.06f)
        when (kind) {
            EnterKind.Newline -> {
                val stem = Path().apply {
                    moveTo(w(18.4f), h(6.2f))
                    lineTo(w(18.4f), h(11.4f))
                    quadraticTo(w(18.4f), h(14.4f), w(15.4f), h(14.4f))
                    lineTo(w(5.6f), h(14.4f))
                }
                val head = Path().apply {
                    moveTo(w(9.6f), h(10.4f))
                    lineTo(w(5.4f), h(14.4f))
                    lineTo(w(9.6f), h(18.4f))
                }
                drawPath(stem, color, style = stroke)
                drawPath(head, color, style = stroke)
            }
            EnterKind.Search -> {
                drawCircle(color, radius = r(5.8f), center = pt(10.8f, 10.8f), style = stroke)
                drawLine(color, pt(15.0f, 15.0f), pt(19.6f, 19.6f), stroke.width, StrokeCap.Round)
            }
            EnterKind.Send -> {
                val plane = Path().apply {
                    moveTo(w(19.8f), h(4.6f))
                    lineTo(w(3.8f), h(11.0f))
                    lineTo(w(10.4f), h(13.6f))
                    lineTo(w(13.0f), h(20.2f))
                    close()
                }
                drawPath(plane, color, style = iconStroke(0.98f))
                drawLine(color, pt(19.6f, 4.8f), pt(10.4f, 13.6f), iconStroke(0.92f).width, StrokeCap.Round)
            }
            EnterKind.Done -> {
                val check = Path().apply {
                    moveTo(w(5.0f), h(12.6f))
                    lineTo(w(9.8f), h(17.4f))
                    lineTo(w(19.2f), h(7.2f))
                }
                drawPath(check, color, style = stroke)
            }
            EnterKind.Next -> {
                drawLine(color, pt(4.4f, 12.0f), pt(15.4f, 12.0f), stroke.width, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w(11.4f), h(8.0f))
                    lineTo(w(15.6f), h(12.0f))
                    lineTo(w(11.4f), h(16.0f))
                }
                drawPath(head, color, style = stroke)
                drawLine(color, pt(19.2f, 7.2f), pt(19.2f, 16.8f), stroke.width, StrokeCap.Round)
            }
            EnterKind.Go -> {
                drawLine(color, pt(4.8f, 12.0f), pt(18.6f, 12.0f), stroke.width, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w(13.8f), h(7.2f))
                    lineTo(w(18.8f), h(12.0f))
                    lineTo(w(13.8f), h(16.8f))
                }
                drawPath(head, color, style = stroke)
            }
        }
    }
}
