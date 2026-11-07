package com.wolfram.lumen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

object LumenColors {
    val Background = Color(0xFF0A0E17)
    val Text = Color(0xFFF4F6FA)
    val Accent = Color(0xFF7FD1C7)
    val Teal = Color(0xFF1C6E6A)
    val Amber = Color(0xFF9A5A22)
    val Indigo = Color(0xFF2B3F8C)
}

/** Frosted-glass look: a light translucent fill with a brighter top edge (tinted by [edge]). */
fun Modifier.glass(
    shape: Shape,
    fill: Float = 0.08f,
    edge: Color = Color.White.copy(alpha = 0.30f),
): Modifier = this
    .clip(shape)
    .background(Color.White.copy(alpha = fill))
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(listOf(edge, Color.White.copy(alpha = 0.06f), edge.copy(alpha = edge.alpha * 0.5f))),
        shape = shape,
    )

@Composable
fun HdmiGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = h * 0.09f
        val port = Path().apply {
            moveTo(w * 0.05f, h * 0.10f)
            lineTo(w * 0.95f, h * 0.10f)
            lineTo(w * 0.95f, h * 0.58f)
            lineTo(w * 0.82f, h * 0.90f)
            lineTo(w * 0.18f, h * 0.90f)
            lineTo(w * 0.05f, h * 0.58f)
            close()
        }
        drawPath(port, color, style = Stroke(width = stroke, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.25f, h * 0.42f), Offset(w * 0.75f, h * 0.42f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun SearchGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = s * 0.12f
        drawCircle(color, radius = s * 0.30f, center = Offset(s * 0.43f, s * 0.43f), style = Stroke(stroke))
        drawLine(color, Offset(s * 0.66f, s * 0.66f), Offset(s * 0.90f, s * 0.90f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun SettingsGlyph(modifier: Modifier, color: Color, knob: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = s * 0.11f
        drawLine(color, Offset(s * 0.10f, s * 0.30f), Offset(s * 0.90f, s * 0.30f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(s * 0.10f, s * 0.70f), Offset(s * 0.90f, s * 0.70f), strokeWidth = stroke, cap = StrokeCap.Round)
        for (c in listOf(Offset(s * 0.36f, s * 0.30f), Offset(s * 0.64f, s * 0.70f))) {
            drawCircle(knob, radius = s * 0.13f, center = c)
            drawCircle(color, radius = s * 0.13f, center = c, style = Stroke(stroke))
        }
    }
}

@Composable
fun HomeGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val style = Stroke(width = s * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val roof = Path().apply {
            moveTo(s * 0.10f, s * 0.48f)
            lineTo(s * 0.50f, s * 0.12f)
            lineTo(s * 0.90f, s * 0.48f)
        }
        val body = Path().apply {
            moveTo(s * 0.20f, s * 0.42f)
            lineTo(s * 0.20f, s * 0.88f)
            lineTo(s * 0.80f, s * 0.88f)
            lineTo(s * 0.80f, s * 0.42f)
        }
        drawPath(roof, color, style = style)
        drawPath(body, color, style = style)
    }
}

@Composable
fun AppsGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val box = s * 0.32f
        for (x in listOf(s * 0.10f, s * 0.58f)) {
            for (y in listOf(s * 0.10f, s * 0.58f)) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(box, box),
                    cornerRadius = CornerRadius(s * 0.07f),
                    style = Stroke(width = s * 0.10f),
                )
            }
        }
    }
}

@Composable
fun InputGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = s * 0.10f
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.06f, s * 0.16f),
            size = Size(s * 0.88f, s * 0.54f),
            cornerRadius = CornerRadius(s * 0.08f),
            style = Stroke(width = stroke),
        )
        drawLine(color, Offset(s * 0.32f, s * 0.88f), Offset(s * 0.68f, s * 0.88f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(s * 0.50f, s * 0.70f), Offset(s * 0.50f, s * 0.88f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun PaletteGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        drawCircle(color, radius = s * 0.42f, center = Offset(s * 0.5f, s * 0.5f), style = Stroke(width = s * 0.10f))
        for (p in listOf(Offset(s * 0.33f, s * 0.44f), Offset(s * 0.50f, s * 0.29f), Offset(s * 0.67f, s * 0.44f))) {
            drawCircle(color, radius = s * 0.075f, center = p)
        }
        drawCircle(color, radius = s * 0.09f, center = Offset(s * 0.55f, s * 0.68f), style = Stroke(width = s * 0.08f))
    }
}

@Composable
fun SparkleGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        fun star(cx: Float, cy: Float, r: Float) = Path().apply {
            moveTo(cx, cy - r)
            quadraticTo(cx + r * 0.18f, cy - r * 0.18f, cx + r, cy)
            quadraticTo(cx + r * 0.18f, cy + r * 0.18f, cx, cy + r)
            quadraticTo(cx - r * 0.18f, cy + r * 0.18f, cx - r, cy)
            quadraticTo(cx - r * 0.18f, cy - r * 0.18f, cx, cy - r)
            close()
        }
        drawPath(star(s * 0.42f, s * 0.44f, s * 0.36f), color)
        drawPath(star(s * 0.80f, s * 0.80f, s * 0.16f), color)
    }
}

@Composable
fun MicGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.36f, s * 0.06f),
            size = Size(s * 0.28f, s * 0.52f),
            cornerRadius = CornerRadius(s * 0.14f),
        )
        val cup = Path().apply {
            moveTo(s * 0.20f, s * 0.42f)
            cubicTo(s * 0.20f, s * 0.78f, s * 0.80f, s * 0.78f, s * 0.80f, s * 0.42f)
        }
        drawPath(cup, color, style = stroke)
        drawLine(color, Offset(s * 0.5f, s * 0.72f), Offset(s * 0.5f, s * 0.92f), strokeWidth = s * 0.10f, cap = StrokeCap.Round)
    }
}

@Composable
fun KeyboardGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.06f, s * 0.24f),
            size = Size(s * 0.88f, s * 0.52f),
            cornerRadius = CornerRadius(s * 0.08f),
            style = Stroke(width = s * 0.08f),
        )
        for (x in listOf(0.24f, 0.41f, 0.59f, 0.76f)) {
            drawCircle(color, radius = s * 0.04f, center = Offset(s * x, s * 0.41f))
        }
        drawLine(color, Offset(s * 0.30f, s * 0.60f), Offset(s * 0.70f, s * 0.60f), strokeWidth = s * 0.07f, cap = StrokeCap.Round)
    }
}

@Composable
fun NewsGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, topLeft = Offset(s * 0.10f, s * 0.16f), size = Size(s * 0.62f, s * 0.68f), cornerRadius = CornerRadius(s * 0.08f), style = stroke)
        val side = Path().apply {
            moveTo(s * 0.72f, s * 0.34f)
            lineTo(s * 0.86f, s * 0.34f)
            lineTo(s * 0.86f, s * 0.74f)
            quadraticTo(s * 0.86f, s * 0.84f, s * 0.76f, s * 0.84f)
        }
        drawPath(side, color, style = stroke)
        for (y in listOf(0.34f, 0.50f, 0.66f)) {
            drawLine(color, Offset(s * 0.24f, s * y), Offset(s * (if (y > 0.6f) 0.44f else 0.58f), s * y), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun FilesGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val folder = Path().apply {
            moveTo(s * 0.08f, s * 0.26f)
            lineTo(s * 0.38f, s * 0.26f)
            lineTo(s * 0.46f, s * 0.36f)
            lineTo(s * 0.92f, s * 0.36f)
            lineTo(s * 0.92f, s * 0.82f)
            lineTo(s * 0.08f, s * 0.82f)
            close()
        }
        drawPath(folder, color, style = Stroke(width = s * 0.09f, join = StrokeJoin.Round))
    }
}

@Composable
fun PhoneGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        drawRoundRect(color, topLeft = Offset(s * 0.28f, s * 0.06f), size = Size(s * 0.44f, s * 0.88f), cornerRadius = CornerRadius(s * 0.10f), style = Stroke(width = s * 0.09f))
        drawLine(color, Offset(s * 0.44f, s * 0.80f), Offset(s * 0.56f, s * 0.80f), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
    }
}

@Composable
fun PlayGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        drawPath(Path().apply { moveTo(s * 0.28f, s * 0.16f); lineTo(s * 0.84f, s * 0.5f); lineTo(s * 0.28f, s * 0.84f); close() }, color)
    }
}

@Composable
fun PauseGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        drawRoundRect(color, topLeft = Offset(s * 0.22f, s * 0.16f), size = Size(s * 0.18f, s * 0.68f), cornerRadius = CornerRadius(s * 0.05f))
        drawRoundRect(color, topLeft = Offset(s * 0.60f, s * 0.16f), size = Size(s * 0.18f, s * 0.68f), cornerRadius = CornerRadius(s * 0.05f))
    }
}

@Composable
fun NextGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        drawPath(Path().apply { moveTo(s * 0.18f, s * 0.18f); lineTo(s * 0.66f, s * 0.5f); lineTo(s * 0.18f, s * 0.82f); close() }, color)
        drawLine(color, Offset(s * 0.80f, s * 0.20f), Offset(s * 0.80f, s * 0.80f), strokeWidth = s * 0.12f, cap = StrokeCap.Round)
    }
}

@Composable
fun PrevGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        drawPath(Path().apply { moveTo(s * 0.82f, s * 0.18f); lineTo(s * 0.34f, s * 0.5f); lineTo(s * 0.82f, s * 0.82f); close() }, color)
        drawLine(color, Offset(s * 0.20f, s * 0.20f), Offset(s * 0.20f, s * 0.80f), strokeWidth = s * 0.12f, cap = StrokeCap.Round)
    }
}

@Composable
fun CheckGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val tick = Path().apply {
            moveTo(s * 0.18f, s * 0.52f)
            lineTo(s * 0.42f, s * 0.76f)
            lineTo(s * 0.84f, s * 0.28f)
        }
        drawPath(tick, color, style = Stroke(width = s * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun WifiGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val s = size.minDimension
        val stroke = s * 0.11f
        val center = Offset(s * 0.5f, s * 0.86f)
        for (r in listOf(s * 0.28f, s * 0.52f, s * 0.76f)) {
            drawArc(
                color = color,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        drawCircle(color, radius = stroke * 0.8f, center = center)
    }
}
