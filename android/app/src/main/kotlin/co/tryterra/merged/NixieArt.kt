package co.tryterra.merged

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Tube sizes, in stage px (the 680×596 usable rear-screen area). See SPEC §3.1. */
enum class Cls(val w: Float, val h: Float, val text: Float, val mesh: Float, val heavy: Boolean, val big: Boolean) {
    HERO(150f, 452f, 390f, 11f, false, true),
    HERO3(104f, 452f, 300f, 11f, false, true),
    SMALL(44f, 118f, 100f, 7f, true, false),
    LIFE(146f, 276f, 236f, 10f, false, true),
    LIFE5(116f, 276f, 188f, 10f, false, true),
    RAIL(36f, 112f, 88f, 6f, true, false),
}

enum class Gas(val core: Int, val hot: Int, val mid: Int, val glow: Int, val ghost: Int, val mesh: Int) {
    NEON(0xFFFFD9B0.toInt(), 0xFFFFF1E0.toInt(), 0xFFFF8A2A.toInt(), 0xFFFF5200.toInt(), 0xFF3A2010.toInt(), 0xFFFF8C3C.toInt()),
    ARGON(0xFFECE4FF.toInt(), 0xFFF7F3FF.toInt(), 0xFFA98CFF.toInt(), 0xFF6A45FF.toInt(), 0xFF2A2350.toInt(), 0xFF9682FF.toInt()),
}

/** A pre-rendered bitmap and where its top-left sits relative to the tube's top-left. */
class Sprite(val bmp: Bitmap, val dx: Float, val dy: Float)

class Glass(val base: Sprite, val lit: Sprite, val hot: Sprite)

/**
 * Bakes every blurred/glowing layer once into bitmaps (software canvas, so
 * BlurMaskFilter is exact), so a frame is just drawBitmap calls — never a
 * blur in onDraw. Thread-safe: a background thread pre-warms, the UI thread
 * bakes anything still missing on first use.
 */
class NixieArt(assets: AssetManager) {
    val light: Typeface = Typeface.createFromAsset(assets, "fonts/BigShouldersDisplay-Light.ttf")
    val medium: Typeface = Typeface.createFromAsset(assets, "fonts/BigShouldersDisplay-Medium.ttf")
    private val thin: Typeface = Typeface.createFromAsset(assets, "fonts/BigShouldersDisplay-Thin.ttf")
    val label: Typeface = Typeface.createFromAsset(assets, "fonts/SpaceGrotesk-SemiBold.ttf")

    private val glyphs = ConcurrentHashMap<String, Sprite>()
    private val glasses = ConcurrentHashMap<String, Glass>()
    private val baselines = ConcurrentHashMap<Cls, Float>()

    fun face(cls: Cls): Typeface = if (cls.heavy) medium else light

    /** Text baseline (tube-relative) that puts the ink centre of "0" at 0.51h. */
    fun baseline(cls: Cls): Float = baselines.getOrPut(cls) {
        val b = Rect()
        textPaint(cls).getTextBounds("0", 0, 1, b)
        cls.h * 0.51f - (b.top + b.bottom) / 2f
    }

    /** Left x (tube-relative) at which to draw [ch] so it's horizontally centred. */
    fun textX(cls: Cls, ch: Char, p: Paint = textPaint(cls)): Float = cls.w / 2 - p.measureText(ch.toString()) / 2

    fun textPaint(cls: Cls) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face(cls); textSize = cls.text }

    fun glyph(cls: Cls, gas: Gas, ch: Char): Sprite =
        glyphs.getOrPut("$cls/$gas/$ch") { if (ch == ',') bakeComma(cls, gas) else bakeGlyph(cls, gas, ch) }

    /** '+' or '−' sign, sprite relative to the sign's centre. */
    fun sign(gas: Gas, plus: Boolean): Sprite = glyphs.getOrPut("sign/$gas/$plus") { bakeSign(gas, plus) }

    fun glass(cls: Cls, gas: Gas): Glass = glasses.getOrPut("$cls/$gas") { bakeGlass(cls, gas) }

    fun prewarm() {
        for (gas in Gas.entries) {
            glass(Cls.SMALL, gas); glyph(Cls.SMALL, gas, ','); sign(gas, gas == Gas.NEON)
            for (c in "0123456789kM") glyph(Cls.SMALL, gas, c)
        }
        glass(Cls.HERO, Gas.NEON)
        for (c in '0'..'9') glyph(Cls.HERO, Gas.NEON, c)
        for (cls in listOf(Cls.LIFE, Cls.LIFE5, Cls.RAIL)) { // LIFE5: 100+ weeks on Week, 10,000+ all-time
            glass(cls, Gas.NEON); glyph(cls, Gas.NEON, ',')
            for (c in '0'..'9') glyph(cls, Gas.NEON, c)
        }
    }

    // ---- baking

    private fun bakeGlyph(cls: Cls, gas: Gas, ch: Char): Sprite {
        val p = textPaint(cls)
        val s = ch.toString()
        val b = Rect().also { p.getTextBounds(s, 0, 1, it) }
        val x0 = textX(cls, ch, p)
        val base = baseline(cls)
        val left = floor(x0 + b.left - PAD)
        val top = floor(base + b.top - PAD)
        val bmp = Bitmap.createBitmap(b.width() + 2 * PAD.toInt() + 2, b.height() + 2 * PAD.toInt() + 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tx = x0 - left
        val ty = base - top
        for ((r, col, a) in listOf(Triple(2f, gas.mid, 255), Triple(6f, gas.glow, 255), Triple(16f, gas.glow, 170), Triple(34f, gas.glow, 102))) {
            p.color = col; p.alpha = a; p.maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL)
            c.drawText(s, tx, ty, p)
        }
        p.maskFilter = null; p.color = gas.core
        c.drawText(s, tx, ty, p)
        return Sprite(bmp, left, top)
    }

    /** The DP cathode, lit as a thousands comma at the tube's bottom-right. SPEC §3.2 L9. */
    private fun bakeComma(cls: Cls, gas: Gas): Sprite {
        val sz = max(5f, 0.12f * cls.w)
        val boxX = cls.w - 0.85f * sz
        val boxY = cls.h / 2 + 0.29f * cls.text
        val pad = 12f
        val bmp = Bitmap.createBitmap(ceil(1.4f * sz + 2 * pad).toInt(), ceil(2.2f * sz + 2 * pad).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = Path().apply {
            addCircle(pad + 0.7f * sz, pad + 0.7f * sz, sz / 2, Path.Direction.CW)
        }
        val tail = Path().apply {
            moveTo(pad + 1.15f * sz, pad + 0.85f * sz)
            quadTo(pad + 1.0f * sz, pad + 1.8f * sz, pad + 0.25f * sz, pad + 2.1f * sz)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 0.32f * sz }
        for ((r, col) in listOf(2f to gas.mid, 6f to gas.glow, 0f to gas.core)) {
            p.color = col
            p.maskFilter = if (r > 0) BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) else null
            p.style = Paint.Style.FILL; c.drawPath(path, p)
            p.style = Paint.Style.STROKE; c.drawPath(tail, p)
        }
        return Sprite(bmp, boxX - pad, boxY - pad)
    }

    private fun bakeSign(gas: Gas, plus: Boolean): Sprite {
        val pad = 40f
        val size = (22 + 2 * pad).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((r, col, a) in listOf(Triple(3f, gas.mid, 255), Triple(10f, gas.glow, 255), Triple(20f, gas.glow, 135), Triple(0f, gas.core, 255))) {
            p.color = col; p.alpha = a
            p.maskFilter = if (r > 0) BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) else null
            c.drawRoundRect(RectF(cx - 11, cx - 2, cx + 11, cx + 2), 2f, 2f, p)
            if (plus) c.drawRoundRect(RectF(cx - 2, cx - 11, cx + 2, cx + 11), 2f, 2f, p)
        }
        return Sprite(bmp, -cx, -cx)
    }

    private fun bakeGlass(cls: Cls, gas: Gas): Glass {
        val blankGhost = if (cls.big) 0f else 0.06f
        val base = layer(cls) { c, clip ->
            c.save(); c.clipPath(clip)
            mesh(c, cls, gas.mesh, 0.10f, glow = false)
            c.drawRoundRect(RectF(0.08f * cls.w, 0.925f * cls.h, 0.92f * cls.w, cls.h - 3), 2f, 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(5, 255, 255, 255) })
            ghosts(c, cls, gas, blankGhost)
            c.restore()
            outline(c, cls, 0.10f)
            reflection(c, cls, 0.07f)
        }
        val lit = layer(cls) { c, clip ->
            c.save(); c.clipPath(clip)
            warm(c, cls, gas, 0.14f)
            mesh(c, cls, gas.mesh, 0.08f, glow = false)
            ghosts(c, cls, gas, 0.5f - blankGhost)
            c.restore()
        }
        val hot = layer(cls) { c, clip ->
            c.save(); c.clipPath(clip)
            warm(c, cls, gas, 0.30f)
            mesh(c, cls, gas.mid, 0.37f, glow = true)
            c.restore()
            outline(c, cls, 0.10f)
            reflection(c, cls, 0.08f)
        }
        return Glass(base, lit, hot)
    }

    private inline fun layer(cls: Cls, draw: (Canvas, Path) -> Unit): Sprite {
        val pad = 4
        val bmp = Bitmap.createBitmap(cls.w.toInt() + 2 * pad, cls.h.toInt() + 2 * pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(pad.toFloat(), pad.toFloat())
        draw(c, tubePath(cls, 0f))
        return Sprite(bmp, -pad.toFloat(), -pad.toFloat())
    }

    /** Dome of radius w/2 on top, straight sides, 8px-radius bottom corners. */
    private fun tubePath(cls: Cls, inset: Float) = Path().apply {
        val w = cls.w; val h = cls.h; val r = w / 2
        moveTo(inset, r)
        arcTo(RectF(inset, inset, w - inset, w - inset), 180f, 180f)
        lineTo(w - inset, h - 8)
        quadTo(w - inset, h - inset, w - 8, h - inset)
        lineTo(8f, h - inset)
        quadTo(inset, h - inset, inset, h - 8)
        close()
    }

    private fun outline(c: Canvas, cls: Cls, alpha: Float) {
        c.drawPath(tubePath(cls, 0.75f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f
            color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        })
    }

    /** Soft crescent inside the dome only — no vertical streak (it read as a "1"). */
    private fun reflection(c: Canvas, cls: Cls, peak: Float) {
        val w = cls.w; val r = w / 2
        val rr = 0.8f * r
        val path = Path().apply {
            // arc from (0.16w, 1.02r) to (0.56w, 0.22r), radius 0.8r, clockwise
            val x1 = 0.16f * w; val y1 = 1.02f * r; val x2 = 0.56f * w; val y2 = 0.22f * r
            val mx = (x1 + x2) / 2; val my = (y1 + y2) / 2
            val dx = x2 - x1; val dy = y2 - y1
            val d = kotlin.math.hypot(dx, dy)
            val k = kotlin.math.sqrt(max(0f, rr * rr - d * d / 4)) / d
            val cx = mx - k * dy; val cy = my + k * dx // centre below-right → arc hugs the dome
            val a1 = Math.toDegrees(kotlin.math.atan2((y1 - cy).toDouble(), (x1 - cx).toDouble())).toFloat()
            val a2 = Math.toDegrees(kotlin.math.atan2((y2 - cy).toDouble(), (x2 - cx).toDouble())).toFloat()
            var sweep = a2 - a1
            if (sweep < 0) sweep += 360f
            arcTo(RectF(cx - rr, cy - rr, cx + rr, cy + rr), a1, sweep, true)
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2.5f, 0.045f * w)
            strokeCap = Paint.Cap.ROUND
            val a = (peak * 255).toInt()
            shader = LinearGradient(0f, 0f, w, 0.4f * w,
                intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(a, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            maskFilter = BlurMaskFilter(if (cls.big) 1.5f else 0.6f, BlurMaskFilter.Blur.NORMAL)
        })
    }

    /** Elliptical radial gradient helper: centre (cx,cy), radii (rx,ry). */
    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, inner: Int, outer: Int): Shader =
        RadialGradient(cx, cy, ry, inner, outer, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(rx / ry, 1f, cx, cy) })
        }

    private fun warm(c: Canvas, cls: Cls, gas: Gas, alpha: Float) {
        val a = (alpha * 255).toInt()
        val col = gas.mesh and 0xFFFFFF
        c.drawRect(0f, 0f, cls.w, cls.h, Paint().apply {
            shader = ellipse(0.5f * cls.w, 0.56f * cls.h, 0.55f * cls.w, 0.55f * cls.h, (a shl 24) or col, col)
        })
    }

    /** Hex anode mesh, faded out radially from the tube's centre. */
    private fun mesh(c: Canvas, cls: Cls, color: Int, alpha: Float, glow: Boolean) {
        val m = cls.mesh
        val tile = Bitmap.createBitmap(m.toInt(), (1.732f * m).toInt(), Bitmap.Config.ARGB_8888)
        val tp = Path().apply {
            moveTo(0f, 0f); rLineTo(m / 2, 0.866f * m); rLineTo(-m / 2, 0.866f * m)
            moveTo(m / 2, 0.866f * m); rLineTo(m / 2, 0f)
        }
        val tc = Canvas(tile)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = color }
        if (glow) {
            sp.strokeWidth = 2.4f; sp.alpha = 110; sp.maskFilter = BlurMaskFilter(1.5f, BlurMaskFilter.Blur.NORMAL)
            tc.drawPath(tp, sp)
            sp.maskFilter = null; sp.alpha = 255
        }
        sp.strokeWidth = 0.8f
        tc.drawPath(tp, sp)
        val n = c.saveLayer(0f, 0f, cls.w, cls.h, null)
        c.drawRect(0f, 0f, cls.w, cls.h, Paint().apply {
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            this.alpha = (alpha * 255).toInt()
        })
        c.drawRect(0f, 0f, cls.w, cls.h, Paint().apply {
            shader = ellipse(0.5f * cls.w, 0.52f * cls.h, 0.5f * cls.w, 0.5f * cls.h, Color.WHITE, Color.TRANSPARENT)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        c.restoreToCount(n)
    }

    /** All ten unlit cathodes stacked, composited once so overlaps don't add up. */
    private fun ghosts(c: Canvas, cls: Cls, gas: Gas, alpha: Float) {
        if (alpha <= 0f) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = thin; textSize = cls.text; color = gas.ghost }
        val n = c.saveLayerAlpha(0f, 0f, cls.w, cls.h, (alpha * 255).toInt())
        val base = baseline(cls)
        for (d in '0'..'9') c.drawText(d.toString(), cls.w / 2 - p.measureText(d.toString()) / 2, base, p)
        c.restoreToCount(n)
    }

    companion object {
        private const val PAD = 64f
        @Volatile private var shared: NixieArt? = null

        /**
         * One per process: the bake (~16MB of glow bitmaps) survives the activity
         * being recreated when it moves to the rear display.
         */
        fun get(assets: AssetManager): NixieArt = shared ?: synchronized(this) {
            shared ?: NixieArt(assets).also { shared = it; Thread({ it.prewarm() }, "nixie-prewarm").start() }
        }
    }
}
