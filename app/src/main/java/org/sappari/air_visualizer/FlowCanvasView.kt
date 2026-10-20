package org.sappari.air_visualizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Displays one matched analysis frame. Arrows describe image deformation, not air velocity.
 * submitFrame transfers bitmap ownership to this view; callers must not reuse them afterwards.
 */
class FlowCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var baseFrame: Bitmap? = null
    private var overlayFrame: Bitmap? = null
    private var frameVectors: List<FlowVector> = emptyList()
    private var frameShiftX = 0f
    private var frameShiftY = 0f
    private val imageBounds = RectF()
    private val renderer = FrameRenderer()
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragging = false
    private var regionBeforeDrag: NormalizedRegion? = null

    var region: NormalizedRegion = NormalizedRegion()
        set(value) {
            field = constrainRegion(value)
            invalidate()
        }

    /** Invoked after a completed drag, not on each move while the region is being selected. */
    var onRegionChanged: ((NormalizedRegion) -> Unit)? = null

    var editingRegion: Boolean = false
        set(value) {
            if (!value && dragging) cancelDrag()
            field = value
            invalidate()
        }

    var overlayAlpha: Float = 0.65f
        set(value) {
            field = if (value.isFinite()) value.coerceIn(0f, 1f) else 0.65f
            invalidate()
        }

    /** False shows only the camera image and the region boundary. */
    var showOverlay: Boolean = true
        set(value) { field = value; invalidate() }

    init {
        // Software drawing copies frame pixels into the view layer on the UI thread.
        // RenderThread therefore cannot retain a just-replaced input bitmap for a later draw.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(BACKGROUND_COLOR)
        contentDescription = "Зображення камери. Стрілки показують деформацію зображення в пікселях."
        isClickable = true
    }

    @MainThread
    fun submitFrame(base: Bitmap, overlay: Bitmap?, vectors: List<FlowVector>, shiftX: Float = 0f, shiftY: Float = 0f) {
        checkMainThread()
        require(!base.isRecycled) { "Base bitmap has been recycled" }
        require(overlay == null || (!overlay.isRecycled &&
            overlay.width == base.width && overlay.height == base.height)) {
            "Overlay must match the base frame dimensions"
        }
        val previousBase = baseFrame
        val previousOverlay = overlayFrame
        baseFrame = base
        overlayFrame = overlay
        frameVectors = vectors.toList()
        frameShiftX = shiftX
        frameShiftY = shiftY
        recycleIfUnretained(previousBase, base, overlay)
        if (previousOverlay !== previousBase) recycleIfUnretained(previousOverlay, base, overlay)
        updateImageBounds()
        invalidate()
    }

    @MainThread
    fun clearFrame() {
        checkMainThread()
        cancelDrag()
        val oldBase = baseFrame
        val oldOverlay = overlayFrame
        baseFrame = null
        overlayFrame = null
        frameVectors = emptyList()
        imageBounds.setEmpty()
        recycleIfUnretained(oldBase, null, null)
        if (oldOverlay !== oldBase) recycleIfUnretained(oldOverlay, null, null)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageBounds()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val base = baseFrame ?: return
        if (base.isRecycled || imageBounds.isEmpty) return
        renderer.draw(canvas, base, if (showOverlay) overlayFrame else null,
            if (showOverlay) frameVectors else emptyList(), region, overlayAlpha, imageBounds,
            frameShiftX, frameShiftY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editingRegion || baseFrame == null || imageBounds.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageBounds.contains(event.x, event.y)) return false
                dragging = true
                regionBeforeDrag = region
                dragStartX = normalizedX(event.x)
                dragStartY = normalizedY(event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                if (abs(normalizedX(event.x) - dragStartX) + abs(normalizedY(event.y) - dragStartY) > 0.01f) {
                    selectRegion(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                val endX = normalizedX(event.x)
                val endY = normalizedY(event.y)
                // A tap does not unexpectedly collapse an existing selection to minimum size.
                if (abs(endX - dragStartX) + abs(endY - dragStartY) > 0.01f) {
                    selectRegion(event.x, event.y)
                } else {
                    regionBeforeDrag?.let { region = it }
                }
                val previous = regionBeforeDrag
                dragging = false
                regionBeforeDrag = null
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                if (region != previous) onRegionChanged?.invoke(region)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDrag()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelDrag()
                return true
            }
        }
        return dragging
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun selectRegion(x: Float, y: Float) {
        val horizontal = dragInterval(dragStartX, normalizedX(x))
        val vertical = dragInterval(dragStartY, normalizedY(y))
        region = NormalizedRegion(horizontal.first, vertical.first, horizontal.second, vertical.second)
    }

    private fun cancelDrag() {
        if (dragging) regionBeforeDrag?.let { region = it }
        dragging = false
        regionBeforeDrag = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun normalizedX(x: Float): Float =
        ((x - imageBounds.left) / imageBounds.width()).coerceIn(REGION_MARGIN, 1f - REGION_MARGIN)

    private fun normalizedY(y: Float): Float =
        ((y - imageBounds.top) / imageBounds.height()).coerceIn(REGION_MARGIN, 1f - REGION_MARGIN)

    private fun updateImageBounds() {
        val base = baseFrame ?: return imageBounds.setEmpty()
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val scale = min(availableWidth.toFloat() / base.width, availableHeight.toFloat() / base.height)
        val left = paddingLeft + (availableWidth - base.width * scale) / 2f
        val top = paddingTop + (availableHeight - base.height * scale) / 2f
        imageBounds.set(left, top, left + base.width * scale, top + base.height * scale)
    }

    companion object {
        private const val REGION_MARGIN = 0.05f
        private const val MIN_REGION_SIZE = 0.20f
        private const val VECTOR_DISPLAY_SCALE = 10f
        private const val BACKGROUND_COLOR = 0xFF101820.toInt()
        private const val CYAN = 0xFF62E5E8.toInt()

        /** Returns a new bitmap; inputs remain owned by the caller. Safe on an export worker. */
        fun renderFrame(
            base: Bitmap,
            overlay: Bitmap?,
            vectors: List<FlowVector>,
            region: NormalizedRegion,
            alpha: Float,
            shiftX: Float = 0f,
            shiftY: Float = 0f
        ): Bitmap {
            require(!base.isRecycled) { "Base bitmap has been recycled" }
            require(overlay == null || (!overlay.isRecycled &&
                overlay.width == base.width && overlay.height == base.height)) {
                "Overlay must match the base frame dimensions"
            }
            val output = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            FrameRenderer().draw(Canvas(output), base, overlay, vectors, constrainRegion(region),
                if (alpha.isFinite()) alpha.coerceIn(0f, 1f) else 0.65f,
                RectF(0f, 0f, base.width.toFloat(), base.height.toFloat()), shiftX, shiftY)
            return output
        }

        private fun checkMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) { "Frame ownership must change on the UI thread" }
        }

        private fun recycleIfUnretained(bitmap: Bitmap?, base: Bitmap?, overlay: Bitmap?) {
            if (bitmap != null && bitmap !== base && bitmap !== overlay && !bitmap.isRecycled) bitmap.recycle()
        }

        private fun dragInterval(anchor: Float, position: Float): Pair<Float, Float> {
            val lower: Float
            val upper: Float
            if (position >= anchor) {
                upper = max(position, anchor + MIN_REGION_SIZE).coerceAtMost(1f - REGION_MARGIN)
                lower = min(anchor, upper - MIN_REGION_SIZE).coerceAtLeast(REGION_MARGIN)
            } else {
                lower = min(position, anchor - MIN_REGION_SIZE).coerceAtLeast(REGION_MARGIN)
                upper = max(anchor, lower + MIN_REGION_SIZE).coerceAtMost(1f - REGION_MARGIN)
            }
            return lower to upper
        }

        private fun constrainRegion(value: NormalizedRegion): NormalizedRegion {
            require(listOf(value.left, value.top, value.right, value.bottom).all { it.isFinite() }) {
                "Region coordinates must be finite"
            }
            val left = min(value.left, value.right).coerceIn(REGION_MARGIN, 1f - REGION_MARGIN - MIN_REGION_SIZE)
            val top = min(value.top, value.bottom).coerceIn(REGION_MARGIN, 1f - REGION_MARGIN - MIN_REGION_SIZE)
            val right = max(value.left, value.right).coerceIn(left + MIN_REGION_SIZE, 1f - REGION_MARGIN)
            val bottom = max(value.top, value.bottom).coerceIn(top + MIN_REGION_SIZE, 1f - REGION_MARGIN)
            return NormalizedRegion(left, top, right, bottom)
        }
    }

    /** Shared by the live preview and MP4 renderer to keep geometry and labels consistent. */
    private class FrameRenderer {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val arrow = Path()
        private val roi = RectF()
        private val labelBounds = RectF()
        private val baseBounds = RectF()

        fun draw(canvas: Canvas, base: Bitmap, overlay: Bitmap?, vectors: List<FlowVector>,
                 region: NormalizedRegion, alpha: Float, target: RectF, shiftX: Float, shiftY: Float) {
            val scale = target.width() / base.width
            val unit = min(target.width(), target.height()) / 300f
            val saved = canvas.save()
            canvas.clipRect(target)
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            // The engine's overlay is in reference coordinates. Display the camera in
            // that same coordinate system; a shifted base with an unshifted overlay is misleading.
            paint.color = BACKGROUND_COLOR
            canvas.drawRect(target, paint)
            baseBounds.set(target)
            baseBounds.offset(-shiftX * scale, -shiftY * scale)
            canvas.drawBitmap(base, null, baseBounds, paint)
            if (overlay != null && !overlay.isRecycled && alpha > 0f) {
                paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(overlay, null, target, paint)
                paint.alpha = 255
            }
            roi.set(target.left + region.left * target.width(), target.top + region.top * target.height(),
                target.left + region.right * target.width(), target.top + region.bottom * target.height())
            paint.color = CYAN
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, 0.9f * unit)
            canvas.drawRect(roi, paint)

            val arrowsSaved = canvas.save()
            canvas.clipRect(roi)
            paint.strokeCap = Paint.Cap.ROUND
            for (vector in vectors) {
                if (!vector.x.isFinite() || !vector.y.isFinite() ||
                    !vector.dx.isFinite() || !vector.dy.isFinite()) continue
                val x = target.left + vector.x * scale
                val y = target.top + vector.y * scale
                if (!roi.contains(x, y)) continue
                val dx = vector.dx * VECTOR_DISPLAY_SCALE * scale
                val dy = vector.dy * VECTOR_DISPLAY_SCALE * scale
                val length = hypot(dx, dy)
                if (!length.isFinite() || length < 0.01f) continue
                val endX = x + dx
                val endY = y + dy
                val head = min(4f * unit, length * 0.4f)
                val ux = dx / length
                val uy = dy / length
                arrow.reset()
                arrow.moveTo(x, y)
                arrow.lineTo(endX, endY)
                arrow.moveTo(endX - head * ux + head * 0.55f * uy, endY - head * uy - head * 0.55f * ux)
                arrow.lineTo(endX, endY)
                arrow.lineTo(endX - head * ux - head * 0.55f * uy, endY - head * uy + head * 0.55f * ux)
                paint.color = Color.BLACK
                paint.alpha = 170
                paint.strokeWidth = max(2f, 2f * unit)
                canvas.drawPath(arrow, paint)
                paint.color = CYAN
                paint.alpha = 255
                paint.strokeWidth = max(1f, 0.9f * unit)
                canvas.drawPath(arrow, paint)
            }
            canvas.restoreToCount(arrowsSaved)
            if (vectors.isNotEmpty()) {
                val label = "Деформація ×10 • px"
                paint.style = Paint.Style.FILL
                paint.textSize = 13f * unit
                val inset = 6f * unit
                val textHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent
                labelBounds.set(target.left + inset, target.top + inset,
                    target.left + 3f * inset + paint.measureText(label), target.top + 3f * inset + textHeight)
                paint.color = BACKGROUND_COLOR
                paint.alpha = 225
                canvas.drawRoundRect(labelBounds, 3f * unit, 3f * unit, paint)
                paint.color = CYAN
                paint.alpha = 255
                canvas.drawText(label, labelBounds.left + inset,
                    labelBounds.top + inset - paint.fontMetrics.ascent, paint)
            }
            canvas.restoreToCount(saved)
        }
    }
}
