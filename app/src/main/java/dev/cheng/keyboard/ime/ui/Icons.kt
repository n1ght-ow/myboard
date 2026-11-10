package dev.cheng.keyboard.ime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.cheng.keyboard.model.ShiftState

@Composable
fun AskIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.068f, cap = StrokeCap.Round)
        val bubble = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = size.width * 0.16f,
                    top = size.height * 0.16f,
                    right = size.width * 0.84f,
                    bottom = size.height * 0.68f,
                    cornerRadius = CornerRadius(size.minDimension * 0.22f),
                ),
            )
        }
        drawPath(bubble, color, style = stroke)
        val tail = Path().apply {
            moveTo(size.width * 0.32f, size.height * 0.64f)
            lineTo(size.width * 0.26f, size.height * 0.86f)
            lineTo(size.width * 0.48f, size.height * 0.68f)
        }
        drawPath(tail, color, style = Stroke(width = stroke.width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
fun TranslateIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.068f, cap = StrokeCap.Round)
        fun arrow(y: Float, from: Float, to: Float) {
            drawLine(color, Offset(size.width * from, y), Offset(size.width * to, y), stroke.width, StrokeCap.Round)
            val tip = size.width * to
            val dir = if (to > from) 1f else -1f
            val wing = size.minDimension * 0.16f
            drawLine(color, Offset(tip, y), Offset(tip - dir * wing, y - wing * 0.72f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(tip, y), Offset(tip - dir * wing, y + wing * 0.72f), stroke.width, StrokeCap.Round)
        }
        arrow(size.height * 0.36f, 0.18f, 0.82f)
        arrow(size.height * 0.66f, 0.82f, 0.18f)
    }
}

@Composable
fun PolishIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.068f, cap = StrokeCap.Round)
        val spark = Path().apply {
            moveTo(size.width * 0.50f, size.height * 0.12f)
            lineTo(size.width * 0.62f, size.height * 0.38f)
            lineTo(size.width * 0.90f, size.height * 0.50f)
            lineTo(size.width * 0.62f, size.height * 0.62f)
            lineTo(size.width * 0.50f, size.height * 0.88f)
            lineTo(size.width * 0.38f, size.height * 0.62f)
            lineTo(size.width * 0.10f, size.height * 0.50f)
            lineTo(size.width * 0.38f, size.height * 0.38f)
            close()
        }
        drawPath(spark, color)
    }
}

@Composable
fun DismissIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.075f, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.24f, size.height * 0.38f), Offset(size.width * 0.5f, size.height * 0.64f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.76f, size.height * 0.38f), Offset(size.width * 0.5f, size.height * 0.64f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun GridIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension * 0.28f
        val gap = size.minDimension * 0.12f
        val left = (size.width - cell * 2 - gap) / 2f
        val top = (size.height - cell * 2 - gap) / 2f
        val radius = CornerRadius(cell * 0.28f)
        repeat(2) { row ->
            repeat(2) { column ->
                drawRoundRect(
                    color,
                    topLeft = Offset(left + column * (cell + gap), top + row * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = radius,
                    style = Stroke(width = size.minDimension * 0.07f),
                )
            }
        }
    }
}

@Composable
fun ShiftIcon(color: Color, state: ShiftState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.16f)
            lineTo(size.width * 0.84f, size.height * 0.52f)
            lineTo(size.width * 0.66f, size.height * 0.52f)
            lineTo(size.width * 0.66f, size.height * 0.82f)
            lineTo(size.width * 0.34f, size.height * 0.82f)
            lineTo(size.width * 0.34f, size.height * 0.52f)
            lineTo(size.width * 0.16f, size.height * 0.52f)
            close()
        }
        if (state == ShiftState.Off) {
            drawPath(path, color, style = Stroke(width = size.minDimension * 0.07f, cap = StrokeCap.Round))
        } else {
            drawPath(path, color)
        }
        if (state == ShiftState.Caps) {
            drawLine(
                color,
                Offset(size.width * 0.28f, size.height * 0.92f),
                Offset(size.width * 0.72f, size.height * 0.92f),
                size.minDimension * 0.07f,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
fun BackspaceIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.07f, cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(size.width * 0.42f, size.height * 0.22f)
            lineTo(size.width * 0.78f, size.height * 0.22f)
            quadraticTo(size.width * 0.9f, size.height * 0.22f, size.width * 0.9f, size.height * 0.36f)
            lineTo(size.width * 0.9f, size.height * 0.64f)
            quadraticTo(size.width * 0.9f, size.height * 0.78f, size.width * 0.78f, size.height * 0.78f)
            lineTo(size.width * 0.42f, size.height * 0.78f)
            lineTo(size.width * 0.12f, size.height * 0.5f)
            close()
        }
        drawPath(path, color, style = stroke)
        drawLine(color, Offset(size.width * 0.48f, size.height * 0.38f), Offset(size.width * 0.72f, size.height * 0.62f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.72f, size.height * 0.38f), Offset(size.width * 0.48f, size.height * 0.62f), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun LockIcon(color: Color, modifier: Modifier = Modifier, locked: Boolean = false) {
    Canvas(modifier) {
        val stroke = Stroke(
            width = size.minDimension * 0.08f,
            cap = StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        val bodyTop = size.height * 0.52f
        drawRoundRect(
            color,
            topLeft = Offset(size.width * 0.22f, bodyTop),
            size = Size(size.width * 0.56f, size.height * 0.36f),
            cornerRadius = CornerRadius(size.minDimension * 0.08f),
            style = stroke,
        )
        val bow = Path()
        if (locked) {
            val left = size.width * 0.36f
            val right = size.width * 0.64f
            val neck = size.height * 0.38f
            bow.moveTo(left, bodyTop)
            bow.lineTo(left, neck)
            bow.arcTo(Rect(left, neck - (right - left), right, neck), 180f, 180f, false)
            bow.lineTo(right, bodyTop)
        } else {
            val hinge = size.width * 0.64f
            bow.moveTo(hinge, bodyTop)
            bow.lineTo(hinge, size.height * 0.34f)
            bow.quadraticTo(hinge, size.height * 0.10f, size.width * 0.44f, size.height * 0.10f)
            bow.quadraticTo(size.width * 0.26f, size.height * 0.10f, size.width * 0.26f, size.height * 0.30f)
        }
        drawPath(bow, color, style = stroke)
    }
}

@Composable
fun CycleIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.072f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val left = size.width * 0.16f
        val top = size.height * 0.16f
        val box = Size(size.width * 0.68f, size.height * 0.68f)
        drawArc(color, startAngle = 205f, sweepAngle = 130f, useCenter = false, topLeft = Offset(left, top), size = box, style = stroke)
        drawArc(color, startAngle = 25f, sweepAngle = 130f, useCenter = false, topLeft = Offset(left, top), size = box, style = stroke)
        arrowHead(color, stroke.width, pointOnOval(left, top, box, 335f), 335f + 90f)
        arrowHead(color, stroke.width, pointOnOval(left, top, box, 155f), 155f + 90f)
    }
}

@Composable
fun StackIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.07f, cap = StrokeCap.Round)
        val radius = CornerRadius(size.minDimension * 0.08f)
        drawRoundRect(
            color,
            topLeft = Offset(size.width * 0.28f, size.height * 0.30f),
            size = Size(size.width * 0.52f, size.height * 0.46f),
            cornerRadius = radius,
            style = stroke,
        )
        drawRoundRect(
            color,
            topLeft = Offset(size.width * 0.16f, size.height * 0.18f),
            size = Size(size.width * 0.52f, size.height * 0.46f),
            cornerRadius = radius,
            style = stroke,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.arrowHead(
    color: Color,
    width: Float,
    tip: Offset,
    directionDegrees: Float,
) {
    val radians = Math.toRadians(directionDegrees.toDouble())
    val back = directionDegrees + 180.0
    val length = size.minDimension * 0.16f
    val spread = 32.0
    fun wing(angle: Double): Offset {
        val rad = Math.toRadians(angle)
        return Offset(tip.x + (length * kotlin.math.cos(rad)).toFloat(), tip.y + (length * kotlin.math.sin(rad)).toFloat())
    }
    drawLine(color, tip, wing(back - spread), width, StrokeCap.Round)
    drawLine(color, tip, wing(back + spread), width, StrokeCap.Round)
    if (radians == 0.0) Unit
}

private fun pointOnOval(left: Float, top: Float, box: Size, degrees: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return Offset(
        left + box.width / 2f + (box.width / 2f * kotlin.math.cos(radians)).toFloat(),
        top + box.height / 2f + (box.height / 2f * kotlin.math.sin(radians)).toFloat(),
    )
}

@Composable
fun EnterIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.085f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val left = size.width * 0.20f
        val right = size.width * 0.74f
        val top = size.height * 0.24f
        val mid = size.height * 0.66f
        val path = Path().apply {
            moveTo(right, top)
            lineTo(right, mid)
            lineTo(left, mid)
        }
        drawPath(path, color, style = stroke)
        val head = size.minDimension * 0.24f
        drawLine(color, Offset(left + head, mid - head * 0.85f), Offset(left, mid), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(left + head, mid + head * 0.85f), Offset(left, mid), stroke.width, StrokeCap.Round)
    }
}
