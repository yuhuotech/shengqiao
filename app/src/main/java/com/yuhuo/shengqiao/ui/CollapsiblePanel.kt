package com.yuhuo.shengqiao.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ListView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Clips and slides one natural-height child; siblings (search/filter/toolbar) remain fixed. */
class CollapsiblePanel(context: Context) : FrameLayout(context) {
    private var fullHeight = 0
    private var offset = 0f
    private var animator: ValueAnimator? = null
    var onExpansionChanged: ((Boolean) -> Unit)? = null
    val collapsed get() = fullHeight > 0 && offset >= fullHeight
    val canExpand get() = offset > 0f
    var startCollapsed = false
    init { clipChildren = true; clipToPadding = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        if (child == null) { setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0); return }
        child.measure(getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight, child.layoutParams.width),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val wasCollapsed = collapsed
        fullHeight = child.measuredHeight
        if (startCollapsed || wasCollapsed) { offset = fullHeight.toFloat(); startCollapsed = false }
        offset = offset.coerceIn(0f, fullHeight.toFloat())
        setMeasuredDimension(resolveSize(child.measuredWidth, widthMeasureSpec), (fullHeight - offset).roundToInt())
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        getChildAt(0)?.let { it.layout(0, -offset.roundToInt(), measuredWidth, fullHeight - offset.roundToInt()) }
        // Fully hidden controls should not remain reachable by accessibility/focus.
        importantForAccessibility = if (collapsed) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
    /** Positive pixels collapse; negative pixels expand. Returns the pixels actually consumed. */
    fun consume(pixels: Float): Float {
        animator?.cancel()
        val old = offset
        offset = (offset + pixels).coerceIn(0f, fullHeight.toFloat())
        if (old != offset) { requestLayout(); onExpansionChanged?.invoke(offset == 0f) }
        return offset - old
    }
    fun toggle() = settle(hide = !canExpand)
    fun settle(hide: Boolean) {
        animator?.cancel()
        val target = if (hide) fullHeight.toFloat() else 0f
        if (offset == target) return
        animator = ValueAnimator.ofFloat(offset, target).apply {
            duration = 180; interpolator = DecelerateInterpolator()
            addUpdateListener { offset = it.animatedValue as Float; requestLayout(); onExpansionChanged?.invoke(offset == 0f) }
            start()
        }
    }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}

/** Native list scrolling/row clicks, with a drag consumed only while the panel needs to move. */
class PanelListView(context: Context) : ListView(context) {
    var panel: CollapsiblePanel? = null
    var expandOnlyAtTop = true
    private val configuration = ViewConfiguration.get(context)
    private var startX = 0f
    private var startY = 0f
    private var lastY = 0f
    private var lastDelta = 0f
    private var movingPanel = false
    private var velocity: VelocityTracker? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val header = panel ?: return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            startX = event.rawX; startY = event.rawY; lastY = event.rawY; lastDelta = 0f; movingPanel = false
            velocity?.recycle(); velocity = VelocityTracker.obtain()
        }
        // Screen coordinates stay stable when the expanding panel moves this ListView.
        val screenEvent = MotionEvent.obtain(event).apply { setLocation(event.rawX, event.rawY) }
        velocity?.addMovement(screenEvent); screenEvent.recycle()
        if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1) {
            val delta = event.rawY - lastY
            val vertical = abs(event.rawY - startY) > configuration.scaledTouchSlop && abs(event.rawY - startY) > abs(event.rawX - startX)
            val needsPanel = delta < 0 && !header.collapsed || delta > 0 && header.canExpand && (!expandOnlyAtTop || !canScrollVertically(-1))
            if (!movingPanel && vertical && needsPanel) {
                movingPanel = true
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel); cancel.recycle()
            }
            if (movingPanel) {
                if (delta < 0) {
                    val consumed = header.consume(-delta)
                    scrollListBy((-delta - consumed).roundToInt())
                } else if (!expandOnlyAtTop || !canScrollVertically(-1)) {
                    val consumed = header.consume(-delta)
                    scrollListBy((-delta - consumed).roundToInt())
                }
                else scrollListBy((-delta).roundToInt())
                lastDelta = delta
            }
            lastY = event.rawY
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val consumed = movingPanel
            if (movingPanel) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    header.settle(hide = lastDelta <= 0)
                    velocity?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                    val speed = -(velocity?.yVelocity ?: 0f).roundToInt()
                    if (header.collapsed && speed > configuration.scaledMinimumFlingVelocity) fling(speed)
                } else header.settle(hide = !header.canExpand || lastDelta <= 0)
            }
            velocity?.recycle(); velocity = null; movingPanel = false
            if (consumed) return true
        }
        return if (movingPanel) true else super.dispatchTouchEvent(event)
    }
    override fun onDetachedFromWindow() { velocity?.recycle(); velocity = null; super.onDetachedFromWindow() }
}
