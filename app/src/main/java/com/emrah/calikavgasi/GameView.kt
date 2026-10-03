package com.emrah.calikavgasi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val T = 48f
private const val COLS = 36
private const val ROWS = 26
private const val WW = COLS * T
private const val WH = ROWS * T

private class Hero(
    val name: String, val color: Int, val hp: Int, val speed: Float, val reload: Float,
    val fireCd: Float, val dmg: Int, val life: Float, val sup: Int, val supName: String, val desc: String
)

private val HEROES = listOf(
    Hero("Mavi", 0xFF3D8BFF.toInt(), 100, 190f, 1.1f, .35f, 25, .62f, 0, "Halka", "Çevrene 16 mermi saçar"),
    Hero("Tank", 0xFFFFA31A.toInt(), 150, 160f, 1.2f, .45f, 25, .55f, 1, "Kalkan", "3 sn hasar almaz, can yeniler"),
    Hero("Şimşek", 0xFF2ECC71.toInt(), 80, 225f, .8f, .30f, 20, .55f, 2, "Hız", "3 sn hızlı koşar, durmadan ateş eder"),
    Hero("Nişancı", 0xFFC04DFF.toInt(), 90, 185f, 1.3f, .50f, 35, .85f, 3, "Delici", "Rakiplerin içinden geçen 5 güçlü mermi")
)
private val BOT_COLORS = intArrayOf(0xFFFF4D4D.toInt(), 0xFFB0B7C3.toInt(), 0xFFFF7AB8.toInt(), 0xFF8D5A3B.toInt())

private class Brawler(val id: Int, var color: Int) {
    var x = 0f; var y = 0f; val r = 16f
    var maxHp = 100; var hp = 100; var ammo = 3; var rel = 0f; var relT = 1.1f; var cd = 0f
    var dmg = 25; var rng = .62f
    var dir = 0f; var dead = 0f
    var shield = 0f; var rush = 0f; var charge = 0f
    var mvx = 0f; var mvy = 0f; var wt = 0f; var sg = 1
}

private class Bullet(
    var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Float,
    val o: Brawler, val dmg: Int, val pierce: Boolean
) { val hit = HashSet<Int>() }

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class S { MENU, PLAY, OVER }

    var onGameOver: (() -> Unit)? = null

    private val dn = resources.displayMetrics.density
    private fun dp(v: Float) = v * dn
    private val prefs = context.getSharedPreferences("ck", Context.MODE_PRIVATE)
    private val fx = SoundFx(context).also { it.enabled = prefs.getBoolean("sound", true) }
    private val map = Array(ROWS) { IntArray(COLS) }
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    private val p = Brawler(0, HEROES[0].color)
    private val bots = List(4) { Brawler(it + 1, BOT_COLORS[it]) }
    private val all = listOf(p) + bots
    private val bullets = ArrayList<Bullet>()
    private var rnd = Random(1)

    @Volatile private var state = S.MENU
    @Volatile private var startReq = false
    @Volatile private var superReq = false
    @Volatile private var lockUntil = 0L
    @Volatile private var heroIdx = prefs.getInt("hero", 0).coerceIn(0, HEROES.size - 1)
    private var time = 60f
    private var score = 0
    private var best = prefs.getInt("best", 0)

    @Volatile private var lId = -1
    @Volatile private var rId = -1
    @Volatile private var lox = 0f; @Volatile private var loy = 0f
    @Volatile private var lx = 0f; @Volatile private var ly = 0f
    @Volatile private var rox = 0f; @Volatile private var roy = 0f
    @Volatile private var rx = 0f; @Volatile private var ry = 0f

    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        holder.addCallback(this)
        buildMap()
    }

    // ---------- map ----------
    private fun buildMap() {
        val r = Random(4242)
        for (y in 0 until ROWS) for (x in 0 until COLS)
            map[y][x] = if (x == 0 || y == 0 || x == COLS - 1 || y == ROWS - 1) 1 else 0
        repeat(16) {
            val cx = 2 + (r.nextFloat() * (COLS - 4)).toInt()
            val cy = 2 + (r.nextFloat() * (ROWS - 4)).toInt()
            val s = 2 + (r.nextFloat() * 3).toInt()
            for (y in cy until cy + s) for (x in cx until cx + s + 1)
                if (x < COLS - 1 && y < ROWS - 1 && r.nextFloat() < .85f) map[y][x] = 2
        }
        repeat(20) {
            val x = 2 + (r.nextFloat() * (COLS - 6)).toInt()
            val y = 2 + (r.nextFloat() * (ROWS - 6)).toInt()
            val h = r.nextFloat() < .5f
            for (k in 0..2) map[if (h) y else y + k][if (h) x + k else x] = 1
        }
        for (y in ROWS / 2 - 3 until ROWS / 2 + 3) for (x in 3 until 9) map[y][x] = 0
    }

    private fun hit(x: Float, y: Float, r: Float): Boolean {
        val x0 = floor((x - r) / T).toInt(); val x1 = floor((x + r) / T).toInt()
        val y0 = floor((y - r) / T).toInt(); val y1 = floor((y + r) / T).toInt()
        for (j in y0..y1) for (i in x0..x1) {
            if (i < 0 || j < 0 || i >= COLS || j >= ROWS || map[j][i] == 1) return true
        }
        return false
    }

    private fun mv(e: Brawler, dx: Float, dy: Float) {
        if (!hit(e.x + dx, e.y, e.r)) e.x += dx
        if (!hit(e.x, e.y + dy, e.r)) e.y += dy
    }

    private fun inBush(e: Brawler): Boolean {
        val i = floor(e.x / T).toInt().coerceIn(0, COLS - 1)
        val j = floor(e.y / T).toInt().coerceIn(0, ROWS - 1)
        return map[j][i] == 2
    }

    private fun dist(a: Brawler, b: Brawler) = hypot(a.x - b.x, a.y - b.y)

    // ---------- game flow ----------
    private fun freeSpot(far: Boolean): Pair<Float, Float> {
        repeat(200) {
            val x = (1 + (rnd.nextFloat() * (COLS - 2)).toInt()) * T + T / 2
            val y = (1 + (rnd.nextFloat() * (ROWS - 2)).toInt()) * T + T / 2
            if (!hit(x, y, 18f) && (!far || hypot(x - p.x, y - p.y) > 380f)) return Pair(x, y)
        }
        return Pair(WW / 2, WH / 2)
    }

    private fun respawn(e: Brawler) {
        val s = if (e === p) Pair(6 * T, WH / 2) else freeSpot(true)
        e.x = s.first; e.y = s.second; e.hp = e.maxHp; e.ammo = 3; e.dead = 0f
        e.shield = 0f; e.rush = 0f
    }

    private fun startGame() {
        rnd = Random(System.nanoTime())
        val h = HEROES[heroIdx]
        p.color = h.color; p.maxHp = h.hp; p.dmg = h.dmg; p.rng = h.life; p.relT = h.reload; p.charge = 0f
        bots.forEach { it.maxHp = 100; it.dmg = 25; it.rng = .62f; it.relT = 1.1f }
        time = 60f; score = 0; bullets.clear()
        all.forEach { respawn(it) }
        state = S.PLAY
    }

    private fun endGame() {
        if (score > best) { best = score; prefs.edit().putInt("best", best).apply() }
        lockUntil = System.currentTimeMillis() + 800
        state = S.OVER
        fx.play("over")
        post { onGameOver?.invoke() }
    }

    private fun shoot(e: Brawler, a: Float, cd: Float) {
        if ((e.ammo < 1 && e.rush <= 0f) || e.cd > 0f || e.dead > 0f) return
        if (e.rush <= 0f) e.ammo--
        e.cd = cd; e.dir = a
        bullets.add(Bullet(e.x + cos(a) * 20, e.y + sin(a) * 20, cos(a) * 540, sin(a) * 540, e.rng, e, e.dmg, false))
        if (e === p) fx.play("shoot", .5f)
    }

    private fun hurt(e: Brawler, dmg: Int, o: Brawler) {
        if (e.shield > 0f) return
        e.hp -= dmg
        if (o === p) p.charge = min(100f, p.charge + 18f)
        if (e.hp <= 0) {
            e.hp = 0; e.dead = if (e === p) 2f else 3f
            if (o === p) { score++; fx.play("kill") } else if (e === p) fx.play("hit")
        } else if (o === p) fx.play("hit", .6f) else if (e === p) fx.play("hit")
    }

    private fun doSuper() {
        if (p.dead > 0f || p.charge < 100f) return
        p.charge = 0f
        fx.play("super")
        when (HEROES[heroIdx].sup) {
            0 -> for (k in 0 until 16) {
                val a = k * 6.2832f / 16
                bullets.add(Bullet(p.x + cos(a) * 20, p.y + sin(a) * 20, cos(a) * 480, sin(a) * 480, .55f, p, 25, false))
            }
            1 -> { p.shield = 3f; p.hp = min(p.maxHp, p.hp + 40) }
            2 -> { p.rush = 3f; p.ammo = 3 }
            3 -> for (k in -2..2) {
                val a = p.dir + k * .1f
                bullets.add(Bullet(p.x + cos(a) * 20, p.y + sin(a) * 20, cos(a) * 700, sin(a) * 700, .85f, p, 40, true))
            }
        }
    }

    private fun nearest(): Brawler? {
        var b: Brawler? = null; var bd = 1e9f
        for (e in bots) {
            if (e.dead > 0f) continue
            val d = dist(p, e)
            if (d < bd && (!inBush(e) || d < 130f)) { bd = d; b = e }
        }
        return b
    }

    private fun autoAim(): Float { val n = nearest(); return if (n != null) atan2(n.y - p.y, n.x - p.x) else p.dir }

    // ---------- update ----------
    private fun update(dt: Float) {
        time -= dt
        if (time <= 0f) { time = 0f; endGame(); return }
        if (superReq) { superReq = false; doSuper() }

        for (e in all) {
            e.cd = max(0f, e.cd - dt)
            e.shield = max(0f, e.shield - dt)
            e.rush = max(0f, e.rush - dt)
            if (e.ammo < 3) { e.rel += dt; if (e.rel > e.relT) { e.rel = 0f; e.ammo++ } }
            if (e.dead > 0f) { e.dead -= dt; if (e.dead <= 0f) respawn(e) }
        }

        // player
        if (p.dead <= 0f) {
            val h = HEROES[heroIdx]
            var mx = 0f; var my = 0f
            if (lId >= 0) {
                val dx = lx - lox; val dy = ly - loy; val l = hypot(dx, dy)
                if (l > dp(6f)) { val m = min(1f, l / dp(50f)); mx = dx / l * m; my = dy / l * m }
            }
            val l = hypot(mx, my)
            if (l > 1f) { mx /= l; my /= l }
            val sp = h.speed * (if (inBush(p)) .9f else 1f) * (if (p.rush > 0f) 1.4f else 1f) * dt
            mv(p, mx * sp, my * sp)
            if (l > .2f) p.dir = atan2(my, mx)

            if (rId >= 0) {
                val dx = rx - rox; val dy = ry - roy
                val a = if (hypot(dx, dy) > dp(14f)) atan2(dy, dx) else autoAim()
                p.dir = a
                shoot(p, a, if (p.rush > 0f) .12f else h.fireCd)
            }
        }

        // bots
        for (e in bots) {
            if (e.dead > 0f) continue
            var tg: Brawler? = null; var bd = 430f
            for (o in all) {
                if (o === e || o.dead > 0f) continue
                val d = dist(e, o)
                if (d < bd && (!inBush(o) || d < 130f)) { bd = d; tg = o }
            }
            e.wt -= dt
            if (tg != null) {
                val a = atan2(tg.y - e.y, tg.x - e.x)
                var mx = 0f; var my = 0f
                if (bd > 230f) { mx = cos(a); my = sin(a) }
                else if (bd < 130f) { mx = -cos(a); my = -sin(a) }
                else {
                    if (e.wt <= 0f) { e.sg = -e.sg; e.wt = 1f + rnd.nextFloat() }
                    mx = -sin(a) * e.sg; my = cos(a) * e.sg
                }
                mv(e, mx * 120 * dt, my * 120 * dt)
                e.dir = a
                if (bd < 400f) shoot(e, a + (rnd.nextFloat() - .5f) * .35f, .9f)
            } else {
                if (e.wt <= 0f) {
                    e.wt = 1f + rnd.nextFloat() * 1.5f
                    val a = rnd.nextFloat() * 6.283f
                    e.mvx = cos(a); e.mvy = sin(a)
                }
                val ox = e.x; val oy = e.y
                mv(e, e.mvx * 100 * dt, e.mvy * 100 * dt)
                if (abs(e.x - ox) + abs(e.y - oy) < .2f) e.wt = 0f
            }
        }

        // bullets
        for (b in bullets) {
            b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt
            val i = floor(b.x / T).toInt(); val j = floor(b.y / T).toInt()
            if (i < 0 || j < 0 || i >= COLS || j >= ROWS || map[j][i] == 1) { b.life = 0f; continue }
            for (e in all) {
                if (e === b.o || e.dead > 0f) continue
                if (b.pierce && b.hit.contains(e.id)) continue
                if (hypot(b.x - e.x, b.y - e.y) < e.r + (if (b.pierce) 8 else 4)) {
                    if (b.pierce) b.hit.add(e.id) else b.life = 0f
                    hurt(e, b.dmg, b.o)
                    if (!b.pierce) break
                }
            }
        }
        bullets.removeAll { it.life <= 0f }
    }

    // ---------- drawing ----------
    private fun fill(color: Int, a: Float = 1f) {
        pt.style = Paint.Style.FILL; pt.color = color; pt.alpha = (pt.alpha * a).toInt()
    }

    private fun stroke(color: Int, w: Float, a: Float = 1f) {
        pt.style = Paint.Style.STROKE; pt.strokeWidth = w; pt.color = color; pt.alpha = (pt.alpha * a).toInt()
    }

    private fun face(c: Canvas, x: Float, y: Float, r: Float, dir: Float, color: Int, al: Float) {
        fill(color, al); c.drawCircle(x, y, r, pt)
        stroke(0x8C000000.toInt(), 3f, al); c.drawCircle(x, y, r, pt)
        val k = r / 16f
        val ex = cos(dir) * 5 * k; val ey = sin(dir) * 5 * k
        val nx = -sin(dir) * 6 * k; val ny = cos(dir) * 6 * k
        for (s in intArrayOf(-1, 1)) {
            fill(0xFFFFFFFF.toInt(), al); c.drawCircle(x + ex + nx * s, y + ey + ny * s, 4f * k, pt)
            fill(0xFF111111.toInt(), al); c.drawCircle(x + ex * 1.5f + nx * s, y + ey * 1.5f + ny * s, 2f * k, pt)
        }
    }

    private fun body(c: Canvas, e: Brawler, al: Float) {
        fill(0x40000000, al); c.drawOval(RectF(e.x - 15, e.y + 7, e.x + 15, e.y + 21), pt)
        c.save(); c.translate(e.x, e.y); c.rotate(Math.toDegrees(e.dir.toDouble()).toFloat())
        fill(0xFF222222.toInt(), al); c.drawRect(8f, -4f, 28f, 4f, pt); c.restore()
        face(c, e.x, e.y, e.r, e.dir, e.color, al)
        if (e.shield > 0f) { stroke(0xFF5CE1FF.toInt(), 4f, al); c.drawCircle(e.x, e.y, e.r + 7, pt) }
        if (e.rush > 0f) { stroke(0xFFFFE14D.toInt(), 3f, al); c.drawCircle(e.x, e.y, e.r + 5, pt) }
        fill(0x88000000.toInt(), al); c.drawRect(e.x - 20, e.y - 30, e.x + 20, e.y - 24, pt)
        fill(if (e.hp * 100 / e.maxHp > 40) 0xFF4CFF6B.toInt() else 0xFFFF5A4C.toInt(), al)
        c.drawRect(e.x - 19, e.y - 29, e.x - 19 + 38f * e.hp / e.maxHp, e.y - 25, pt)
    }

    private fun render(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(0xFF14281D.toInt())
        val s = (min(w, h) / dn / 480f).coerceIn(.7f, 1.3f) * dn
        val vw = w / s; val vh = h / s
        var cx = p.x - vw / 2; var cy = p.y - vh / 2
        cx = if (vw >= WW) (WW - vw) / 2 else cx.coerceIn(0f, WW - vw)
        cy = if (vh >= WH) (WH - vh) / 2 else cy.coerceIn(0f, WH - vh)

        c.save(); c.scale(s, s); c.translate(-cx, -cy)
        val x0 = max(0, floor(cx / T).toInt()); val x1 = min(COLS - 1, floor((cx + vw) / T).toInt())
        val y0 = max(0, floor(cy / T).toInt()); val y1 = min(ROWS - 1, floor((cy + vh) / T).toInt())

        for (y in y0..y1) for (x in x0..x1) {
            fill(if ((x + y) % 2 == 1) 0xFF2F8050.toInt() else 0xFF2A7449.toInt())
            c.drawRect(x * T, y * T, (x + 1) * T, (y + 1) * T, pt)
        }
        for (y in y0..y1) for (x in x0..x1) if (map[y][x] == 1) {
            fill(0xFFA9501F.toInt()); c.drawRect(x * T, y * T + 8, (x + 1) * T, (y + 1) * T, pt)
            fill(0xFFE8803D.toInt()); c.drawRect(x * T, y * T, (x + 1) * T, (y + 1) * T - 8, pt)
            fill(0xFFF3A064.toInt()); c.drawRect(x * T + 4, y * T + 4, (x + 1) * T - 4, y * T + 10, pt)
        }
        for (e in all) if (e.dead <= 0f && !inBush(e)) body(c, e, 1f)
        for (b in bullets) {
            if (b.pierce) {
                fill(0xFFE9B3FF.toInt()); c.drawCircle(b.x, b.y, 9f, pt)
                fill(0xFFFFFFFF.toInt()); c.drawCircle(b.x, b.y, 4f, pt)
            } else {
                fill(0xFFFFCF33.toInt()); c.drawCircle(b.x, b.y, 6f, pt)
                fill(0xFFFFFFFF.toInt()); c.drawCircle(b.x, b.y, 3f, pt)
            }
        }
        for (y in y0..y1) for (x in x0..x1) if (map[y][x] == 2) {
            fill(0xFF6A3BB8.toInt()); c.drawCircle(x * T + T / 2, y * T + T / 2 + 4, T * .62f, pt)
            fill(0xFF8F5AE0.toInt()); c.drawCircle(x * T + T / 2 - 3, y * T + T / 2 - 4, T * .5f, pt)
        }
        for (e in all) if (e.dead <= 0f && inBush(e) && (e === p || dist(e, p) < 130f))
            body(c, e, if (e === p) .55f else .6f)
        c.restore()

        drawHud(c, w, h)
    }

    // ---------- HUD / menus ----------
    private fun muteRect() = RectF(width - dp(58f), dp(12f), width - dp(12f), dp(58f))
    private fun superCenter() = Pair(width - dp(60f), height - dp(150f))
    private fun cardRect(i: Int): RectF {
        val cw = dp(104f); val gap = dp(10f)
        val x = (width - (4 * cw + 3 * gap)) / 2 + i * (cw + gap)
        val y = height * .26f
        return RectF(x, y, x + cw, y + dp(112f))
    }
    private fun startRect() = RectF(width / 2f - dp(110f), height - dp(70f), width / 2f + dp(110f), height - dp(20f))
    private fun againRect() = RectF(width / 2f - dp(200f), height * .68f, width / 2f - dp(10f), height * .68f + dp(50f))
    private fun menuRect() = RectF(width / 2f + dp(10f), height * .68f, width / 2f + dp(200f), height * .68f + dp(50f))

    private fun button(c: Canvas, r: RectF, text: String, gold: Boolean) {
        fill(if (gold) 0xFFFFD23F.toInt() else 0x55FFFFFF); c.drawRoundRect(r, dp(16f), dp(16f), pt)
        fill(if (gold) 0xFF3A1C00.toInt() else 0xFFFFFFFF.toInt())
        pt.textAlign = Paint.Align.CENTER; pt.textSize = dp(19f)
        c.drawText(text, r.centerX(), r.centerY() + dp(7f), pt)
    }

    private fun drawHud(c: Canvas, w: Float, h: Float) {
        val top = dp(14f)
        // ses düğmesi
        fill(0xB31E0A3C.toInt()); c.drawRoundRect(muteRect(), dp(16f), dp(16f), pt)
        fill(0xFFFFFFFF.toInt()); pt.textAlign = Paint.Align.CENTER; pt.textSize = dp(22f)
        c.drawText(if (fx.enabled) "\uD83D\uDD0A" else "\uD83D\uDD07", muteRect().centerX(), muteRect().centerY() + dp(8f), pt)

        if (state != S.MENU) {
            val t = ceil(time).toInt()
            val txt = "${t / 60}:${(t % 60).toString().padStart(2, '0')}"
            fill(0xB31E0A3C.toInt()); c.drawRoundRect(RectF(w / 2 - dp(52f), top, w / 2 + dp(52f), top + dp(40f)), dp(20f), dp(20f), pt)
            fill(0xFFFFFFFF.toInt()); pt.textAlign = Paint.Align.CENTER; pt.textSize = dp(22f)
            c.drawText(txt, w / 2, top + dp(29f), pt)

            val l = dp(36f)
            fill(0xB31E0A3C.toInt()); c.drawRoundRect(RectF(l, top, l + dp(92f), top + dp(40f)), dp(20f), dp(20f), pt)
            fill(0xFFFFD23F.toInt()); pt.textAlign = Paint.Align.LEFT; pt.textSize = dp(20f)
            c.drawText("\u2620 $score", l + dp(16f), top + dp(28f), pt)

            for (i in 0 until 3) {
                fill(if (i < p.ammo) 0xFFFFD23F.toInt() else 0x44FFFFFF)
                val ax = w / 2 - dp(42f) + i * dp(29f)
                c.drawRoundRect(RectF(ax, h - dp(26f), ax + dp(25f), h - dp(16f)), dp(5f), dp(5f), pt)
            }

            // süper düğmesi
            val (sx, sy) = superCenter(); val sr = dp(34f)
            val ready = p.charge >= 100f
            fill(if (ready) 0xFFFFD23F.toInt() else 0x88201040.toInt()); c.drawCircle(sx, sy, sr, pt)
            stroke(if (ready) 0xFFFFFFFF.toInt() else 0xFFFFD23F.toInt(), dp(5f))
            c.drawArc(RectF(sx - sr, sy - sr, sx + sr, sy + sr), -90f, 360f * p.charge / 100f, false, pt)
            fill(if (ready) 0xFF3A1C00.toInt() else 0xFFFFFFFF.toInt()); pt.textAlign = Paint.Align.CENTER; pt.textSize = dp(22f)
            c.drawText("S", sx, sy + dp(8f), pt)

            if (p.dead > 0f && state == S.PLAY) {
                fill(0xFFFFFFFF.toInt()); pt.textAlign = Paint.Align.CENTER; pt.textSize = dp(22f)
                c.drawText("Yeniden doğuyorsun…", w / 2, h / 2, pt)
            }
            if (lId >= 0) stick(c, lox, loy, lx, ly, 0xAAFFFFFF.toInt())
            if (rId >= 0) stick(c, rox, roy, rx, ry, 0xAAFF5A4C.toInt())
        }

        if (state == S.MENU) {
            fill(0xB8140A28.toInt()); c.drawRect(0f, 0f, w, h, pt)
            pt.textAlign = Paint.Align.CENTER
            fill(0xFFFFD23F.toInt()); pt.textSize = dp(36f); c.drawText("Çalı Kavgası", w / 2, h * .16f, pt)
            fill(0xFFFFFFFF.toInt()); pt.textSize = dp(14f)
            c.drawText("Karakterini seç  ·  Sol yarı: hareket  ·  Sağ yarı: nişan ve ateş  ·  S: süper", w / 2, h * .16f + dp(26f), pt)
            for (i in HEROES.indices) {
                val r = cardRect(i); val hr = HEROES[i]
                fill(0x33FFFFFF); c.drawRoundRect(r, dp(14f), dp(14f), pt)
                if (i == heroIdx) { stroke(0xFFFFD23F.toInt(), dp(3f)); c.drawRoundRect(r, dp(14f), dp(14f), pt) }
                face(c, r.centerX(), r.top + dp(38f), dp(22f), 0.3f, hr.color, 1f)
                fill(0xFFFFFFFF.toInt()); pt.textAlign = Paint.Align.CENTER; pt.textSize = dp(17f)
                c.drawText(hr.name, r.centerX(), r.top + dp(82f), pt)
                fill(0xFFFFD23F.toInt()); pt.textSize = dp(13f)
                c.drawText("Süper: ${hr.supName}", r.centerX(), r.top + dp(102f), pt)
            }
            val hs = HEROES[heroIdx]; val by = cardRect(0).bottom + dp(26f)
            fill(0xFFFFFFFF.toInt()); pt.textSize = dp(15f)
            c.drawText("Can ${hs.hp}  ·  Hız ${hs.speed.toInt()}  ·  Hasar ${hs.dmg}", w / 2, by, pt)
            fill(0xFFFFD23F.toInt()); c.drawText("${hs.supName}: ${hs.desc}", w / 2, by + dp(22f), pt)
            button(c, startRect(), "BAŞLA", true)
        }

        if (state == S.OVER) {
            fill(0xB8140A28.toInt()); c.drawRect(0f, 0f, w, h, pt)
            pt.textAlign = Paint.Align.CENTER
            fill(0xFFFFD23F.toInt()); pt.textSize = dp(40f); c.drawText("Süre doldu!", w / 2, h * .30f, pt)
            fill(0xFFFFFFFF.toInt()); pt.textSize = dp(19f)
            c.drawText("İndirdiğin rakip: $score", w / 2, h * .44f, pt)
            c.drawText("En iyi: $best", w / 2, h * .44f + dp(30f), pt)
            button(c, againRect(), "Tekrar oyna", true)
            button(c, menuRect(), "Karakter seç", false)
        }
    }

    private fun stick(c: Canvas, ox: Float, oy: Float, x: Float, y: Float, color: Int) {
        stroke(0x77FFFFFF, dp(3f)); c.drawCircle(ox, oy, dp(50f), pt)
        val dx = x - ox; val dy = y - oy; val l = max(hypot(dx, dy), 1f); val m = min(l, dp(50f))
        fill(color); c.drawCircle(ox + dx / l * m, oy + dy / l * m, dp(22f), pt)
    }

    // ---------- touch ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val x = e.getX(i); val y = e.getY(i); val id = e.getPointerId(i)
                if (muteRect().contains(x, y)) {
                    fx.enabled = !fx.enabled
                    prefs.edit().putBoolean("sound", fx.enabled).apply()
                    fx.play("click")
                    return true
                }
                when (state) {
                    S.MENU -> {
                        for (k in HEROES.indices) if (cardRect(k).contains(x, y)) {
                            heroIdx = k; prefs.edit().putInt("hero", k).apply(); fx.play("click")
                        }
                        if (startRect().contains(x, y)) { fx.play("click"); startReq = true }
                    }
                    S.OVER -> if (System.currentTimeMillis() > lockUntil) {
                        if (againRect().contains(x, y)) { fx.play("click"); startReq = true }
                        else if (menuRect().contains(x, y)) { fx.play("click"); state = S.MENU }
                    }
                    S.PLAY -> {
                        val (sx, sy) = superCenter()
                        if (hypot(x - sx, y - sy) < dp(40f)) { superReq = true; return true }
                        if (x < width / 2f) {
                            if (lId < 0) { lox = x; loy = y; lx = x; ly = y; lId = id }
                        } else if (rId < 0) { rox = x; roy = y; rx = x; ry = y; rId = id }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                if (id == lId) { lx = e.getX(i); ly = e.getY(i) }
                if (id == rId) { rx = e.getX(i); ry = e.getY(i) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == lId) lId = -1
                if (id == rId) rId = -1
            }
            MotionEvent.ACTION_CANCEL -> { lId = -1; rId = -1 }
        }
        return true
    }

    // ---------- loop / surface ----------
    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = min(.05f, (now - last) / 1e9f)
            last = now
            if (startReq) { startReq = false; startGame() }
            if (state == S.PLAY) update(dt)
            val c = try { holder.lockCanvas() } catch (ex: Exception) { null }
            if (c == null) { Thread.sleep(10); continue }
            try { render(c) } finally { holder.unlockCanvasAndPost(c) }
        }
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        running = true
        thread = Thread(this, "game").also { it.start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        thread?.join()
        thread = null
        lId = -1; rId = -1
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fx.release()
    }
}
