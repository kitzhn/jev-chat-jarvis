package com.jev.probe.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.jev.probe.core.kb.AffectionScale
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.ContactRelation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Sci-fi relationship graph. The highest-degree contact becomes the hub; other
 * contacts are placed on orbital rings. Nodes and edges remain tappable.
 */
class RelationshipGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Node(
        val contact: Contact,
        var x: Float = 0f,
        var y: Float = 0f,
        var radius: Float = 0f
    )

    private var contacts: List<Contact> = emptyList()
    private var relations: List<ContactRelation> = emptyList()
    private var nodes: List<Node> = emptyList()

    var onContactClick: ((Contact) -> Unit)? = null
    var onRelationClick: ((ContactRelation) -> Unit)? = null

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeRing = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeSub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubRing = Paint(Paint.ANTI_ALIAS_FLAG)

    private val cyan = Color.rgb(74, 222, 255)
    private val bgTop = Color.rgb(4, 11, 27)
    private val bgBottom = Color.rgb(8, 21, 43)
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        minimumHeight = dp(390f).toInt()

        grid.color = Color.argb(28, 85, 181, 255)
        grid.strokeWidth = dp(0.6f)

        edge.style = Paint.Style.STROKE
        edge.strokeCap = Paint.Cap.ROUND
        edge.color = Color.argb(220, 74, 222, 255)

        edgeGlow.style = Paint.Style.STROKE
        edgeGlow.strokeCap = Paint.Cap.ROUND
        edgeGlow.color = Color.argb(80, 74, 222, 255)
        edgeGlow.setShadowLayer(dp(12f), 0f, 0f, cyan)

        edgeText.color = Color.rgb(189, 231, 255)
        edgeText.textSize = dp(10.5f)
        edgeText.textAlign = Paint.Align.CENTER
        edgeText.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        nodeRing.style = Paint.Style.STROKE
        nodeRing.strokeWidth = dp(2f)
        nodeRing.color = cyan
        nodeRing.setShadowLayer(dp(14f), 0f, 0f, cyan)

        hubRing.style = Paint.Style.STROKE
        hubRing.strokeWidth = dp(1.2f)
        hubRing.color = Color.argb(145, 155, 106, 255)

        nodeText.color = Color.WHITE
        nodeText.textAlign = Paint.Align.CENTER
        nodeText.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        nodeSub.color = Color.rgb(170, 205, 230)
        nodeSub.textAlign = Paint.Align.CENTER
    }

    fun submit(contacts: List<Contact>, relations: List<ContactRelation>) {
        this.contacts = contacts
        this.relations = relations
        layoutNodes(width, height)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize(dp(420f).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutNodes(w, h)
    }

    private fun layoutNodes(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || contacts.isEmpty()) {
            nodes = emptyList()
            return
        }

        val degree = HashMap<String, Int>()
        relations.forEach {
            degree[it.fromId] = (degree[it.fromId] ?: 0) + 1
            degree[it.toId] = (degree[it.toId] ?: 0) + 1
        }

        val hub = contacts.maxWithOrNull(
            compareBy<Contact> { degree[it.id] ?: 0 }.thenBy { it.affection }
        ) ?: contacts.first()

        val rest = contacts.filter { it.id != hub.id }
            .sortedByDescending { degree[it.id] ?: 0 }

        val cx = w / 2f
        val cy = h / 2f
        val base = min(w, h).toFloat()
        val inner = base * 0.29f
        val outer = base * 0.42f
        val result = ArrayList<Node>()
        result.add(Node(hub, cx, cy, dp(39f)))

        val firstRingCount = min(rest.size, 6)
        rest.forEachIndexed { index, contact ->
            val firstRing = index < firstRingCount
            val ringItems = if (firstRing) firstRingCount else (rest.size - firstRingCount).coerceAtLeast(1)
            val ringIndex = if (firstRing) index else index - firstRingCount
            val angle = -PI / 2.0 + 2.0 * PI * ringIndex / ringItems +
                if (firstRing) 0.0 else PI / ringItems
            val radius = if (firstRing) inner else outer
            result.add(Node(
                contact,
                cx + cos(angle).toFloat() * radius,
                cy + sin(angle).toFloat() * radius,
                dp(31f)
            ))
        }
        nodes = result
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        if (nodes.isEmpty()) {
            drawEmpty(canvas)
            return
        }
        drawEdges(canvas)
        drawNodes(canvas)
        drawHud(canvas)
    }

    private fun drawBackground(canvas: Canvas) {
        bg.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(), bgTop, bgBottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(20f), dp(20f), bg)
        bg.shader = null

        val step = dp(28f)
        var x = 0f
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), grid)
            x += step
        }
        var y = 0f
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, grid)
            y += step
        }

        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = RadialGradient(
            width * .5f, height * .5f, min(width, height) * .48f,
            intArrayOf(Color.argb(58, 69, 106, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
    }

    private fun drawEdges(canvas: Canvas) {
        val byId = nodes.associateBy { it.contact.id }
        relations.forEach { relation ->
            val a = byId[relation.fromId] ?: return@forEach
            val b = byId[relation.toId] ?: return@forEach
            val strength = relation.strength.coerceIn(0, 100)
            val stroke = dp(1.2f + strength / 45f)
            edge.strokeWidth = stroke
            edgeGlow.strokeWidth = stroke + dp(3f)

            val path = curvedPath(a.x, a.y, b.x, b.y)
            canvas.drawPath(path, edgeGlow)
            canvas.drawPath(path, edge)

            val mx = (a.x + b.x) / 2f
            val my = (a.y + b.y) / 2f
            val label = relation.type.ifBlank { "关系" }.take(8) + " · " + strength
            canvas.drawText(label, mx, my - dp(5f), edgeText)
        }
    }

    private fun curvedPath(x1: Float, y1: Float, x2: Float, y2: Float): Path {
        val mx = (x1 + x2) / 2f
        val my = (y1 + y2) / 2f
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val bend = dp(10f)
        val controlX = mx - dy / len * bend
        val controlY = my + dx / len * bend
        return Path().apply {
            moveTo(x1, y1)
            quadTo(controlX, controlY, x2, y2)
        }
    }

    private fun drawNodes(canvas: Canvas) {
        nodes.forEachIndexed { index, node ->
            nodePaint.shader = RadialGradient(
                node.x - node.radius * .25f,
                node.y - node.radius * .30f,
                node.radius * 1.3f,
                intArrayOf(
                    if (index == 0) Color.rgb(105, 92, 255) else Color.rgb(28, 78, 128),
                    Color.rgb(10, 30, 58)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(node.x, node.y, node.radius, nodePaint)
            nodePaint.shader = null
            canvas.drawCircle(node.x, node.y, node.radius, nodeRing)
            if (index == 0) canvas.drawCircle(node.x, node.y, node.radius + dp(8f), hubRing)

            nodeText.textSize = if (index == 0) dp(12.5f) else dp(10.5f)
            canvas.drawText(node.contact.name.ifBlank { "?" }.take(6), node.x, node.y - dp(2f), nodeText)

            nodeSub.textSize = dp(9f)
            val stage = node.contact.relationshipStage.ifBlank {
                AffectionScale.label(node.contact.affection)
            }.take(6)
            canvas.drawText(
                stage + " · " + node.contact.affection,
                node.x,
                node.y + dp(14f),
                nodeSub
            )
        }
    }

    private fun drawHud(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 210, 255)
            textSize = dp(9f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("RELATIONSHIP NETWORK // LOCAL", dp(12f), dp(18f), p)
        p.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            "NODES " + nodes.size + "  LINKS " + relations.size,
            width - dp(12f),
            dp(18f),
            p
        )
    }

    private fun drawEmpty(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(165, 200, 230)
            textSize = dp(13f)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("至少需要两个联系人和一条关系边", width / 2f, height / 2f, p)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y

        nodes.firstOrNull {
            hypot(x - it.x, y - it.y) <= it.radius + dp(10f)
        }?.let {
            onContactClick?.invoke(it.contact)
            return true
        }

        val byId = nodes.associateBy { it.contact.id }
        val hit = relations.minByOrNull { relation ->
            val a = byId[relation.fromId] ?: return@minByOrNull Float.MAX_VALUE
            val b = byId[relation.toId] ?: return@minByOrNull Float.MAX_VALUE
            distanceToSegment(x, y, a.x, a.y, b.x, b.y)
        }
        if (hit != null) {
            val a = byId[hit.fromId]
            val b = byId[hit.toId]
            if (a != null && b != null &&
                distanceToSegment(x, y, a.x, a.y, b.x, b.y) <= dp(18f)) {
                onRelationClick?.invoke(hit)
                return true
            }
        }
        return true
    }

    private fun distanceToSegment(
        px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float
    ): Float {
        val vx = x2 - x1
        val vy = y2 - y1
        val wx = px - x1
        val wy = py - y1
        val len2 = vx * vx + vy * vy
        if (len2 <= 0.0001f) return hypot(px - x1, py - y1)
        val t = ((wx * vx + wy * vy) / len2).coerceIn(0f, 1f)
        val qx = x1 + t * vx
        val qy = y1 + t * vy
        return hypot(px - qx, py - qy)
    }
}
