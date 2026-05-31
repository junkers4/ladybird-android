/**
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

// FIXME: This should (eventually) implement NestedScrollingChild3 and ScrollingView
class WebView(context: Context, attributeSet: AttributeSet) : View(context, attributeSet) {
    private val viewImpl = WebViewImplementation(this)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private lateinit var contentBitmap: Bitmap
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isScrollingGesture = false
    private var isScalingGesture = false
    private var pinchZoomEnabled = true
    private val flinger = OverScroller(context)
    private var lastFlingX = 0
    private var lastFlingY = 0

    // --- Async compositing state -------------------------------------------------
    // The engine only repaints in response to wheel/zoom events sent over IPC, so a
    // naive "send event -> wait for repaint" loop stutters whenever the engine can't
    // hit 60fps. To stay smooth we composite scroll and pinch on the Android side
    // immediately and reconcile once the freshly painted engine frame arrives.
    //
    // composScroll{X,Y}: how far the displayed bitmap is currently shifted from the
    // engine's painted position (instant visual feedback for the finger).
    // outstandingWheel{X,Y}: wheel deltas sent to the engine but not yet reflected in
    // a painted frame; subtracted from composScroll when the engine paints.
    private var composScrollX = 0f
    private var composScrollY = 0f
    private var outstandingWheelX = 0
    private var outstandingWheelY = 0

    // Visual pinch scale applied to the bitmap during a pinch gesture. The real
    // engine zoom is only applied once the gesture ends; this keeps the pinch itself
    // smooth instead of triggering a full re-layout on every finger movement.
    private var pinchScale = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pendingZoomSteps = 0
    var onLoadStart: (url: String, isRedirect: Boolean) -> Unit = { _, _ -> }
    var onLoadFinish: (url: String) -> Unit = { }
    var onTitleChange: (title: String) -> Unit = { }
    var onUrlChange: (url: String) -> Unit = { }
    var onFindInPage: (current: Int, total: Int) -> Unit = { _, _ -> }
    var onLinkHover: (url: String?) -> Unit = { }
    var onContentReady: () -> Unit = { }
    var onWebContentCrash: () -> Unit = { }
    var onLongPress: (x: Float, y: Float) -> Unit = { _, _ -> }
    var onSwipeRefresh: () -> Unit = { }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var accumulated = 0.0

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!pinchZoomEnabled) return false
            accumulated = 0.0
            pendingZoomSteps = 0
            pinchScale = 1f
            pinchFocusX = detector.focusX
            pinchFocusY = detector.focusY
            isScalingGesture = true
            flinger.forceFinished(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Scale the displayed bitmap immediately for buttery pinch feedback.
            // The real engine zoom (which forces a re-layout + full repaint) is
            // deferred to onScaleEnd so we don't thrash the engine mid-gesture.
            pinchScale = (pinchScale * detector.scaleFactor).coerceIn(0.2f, 5.0f)
            pinchFocusX = detector.focusX
            pinchFocusY = detector.focusY

            // Track how many discrete engine zoom steps we'll need to apply so the
            // final engine zoom lands close to what the user pinched to.
            accumulated += (detector.scaleFactor - 1.0)
            while (accumulated > 0.10) { pendingZoomSteps++; accumulated -= 0.10 }
            while (accumulated < -0.10) { pendingZoomSteps--; accumulated += 0.10 }

            postInvalidateOnAnimation()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScalingGesture = false
            // Apply the accumulated zoom to the engine in one batch. The new engine
            // frame (rendered at the new zoom) will arrive via onEnginePainted(),
            // which resets pinchScale so the visual scale hands off without flicker.
            if (pendingZoomSteps > 0) {
                repeat(pendingZoomSteps) { viewImpl.zoomIn() }
            } else if (pendingZoomSteps < 0) {
                repeat(-pendingZoomSteps) { viewImpl.zoomOut() }
            } else {
                // No real zoom change: drop the visual scale right away.
                pinchScale = 1f
                postInvalidateOnAnimation()
            }
            pendingZoomSteps = 0
            syncViewport()
        }
    }).apply {
        isQuickScaleEnabled = false
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // Forward to the activity so it can show a contextual menu near
            // the touch. We also synthesize a select-word on the page.
            onLongPress(e.x, e.y)
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (isScalingGesture) return false
            flinger.forceFinished(true)
            lastFlingX = 0
            lastFlingY = 0
            // Velocity is in px/s; flip sign because page scrolls opposite to swipe.
            flinger.fling(
                0, 0,
                (-velocityX).toInt(), (-velocityY).toInt(),
                Int.MIN_VALUE, Int.MAX_VALUE,
                Int.MIN_VALUE, Int.MAX_VALUE
            )
            this@WebView.postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Quick zoom toggle like mobile Chromium.
            val lvl = viewImpl.zoomLevel()
            if (lvl > 1.01) viewImpl.zoomReset() else viewImpl.zoomIn()
            return true
        }
    }).apply {
        setIsLongpressEnabled(true)
    }


    fun initialize(resourceDir: String) {
        viewImpl.initialize(resourceDir)
    }

    fun dispose() {
        viewImpl.dispose()
    }

    fun loadURL(url: String) {
        viewImpl.loadURL(url)
    }

    fun reload() {
        viewImpl.reload()
    }

    fun goBack() {
        viewImpl.goBack()
    }

    fun goForward() {
        viewImpl.goForward()
    }

    fun findInPage(query: String, caseSensitive: Boolean = false) = viewImpl.findInPage(query, caseSensitive)
    fun findNext() = viewImpl.findNext()
    fun findPrevious() = viewImpl.findPrevious()
    fun zoomIn() = viewImpl.zoomIn().also { syncViewport() }
    fun zoomOut() = viewImpl.zoomOut().also { syncViewport() }
    fun zoomReset() = viewImpl.zoomReset().also { syncViewport() }
    fun zoomLevel(): Double = viewImpl.zoomLevel()
    fun setPreferredColorScheme(scheme: Int) = viewImpl.setPreferredColorScheme(scheme)
    fun runJavascript(js: String) = viewImpl.runJavascript(js)
    fun selectAllOnPage() = viewImpl.selectAllOnPage()

    fun setUserAgent(preset: UserAgentPreset) {
        // Keep navigator.platform in sync with the spoofed UA so sites don't see
        // an Android platform string paired with a desktop Chrome UA.
        viewImpl.debugRequest("platform", preset.platformString ?: "")
        // Pass the full UA string as argument; null/empty means "reset to default".
        viewImpl.debugRequest("spoof-user-agent", preset.uaString ?: "")
    }

    fun setNavigatorCompatibility(mode: NavigatorCompatibility) {
        viewImpl.debugRequest("navigator-compatibility-mode", mode.nativeName)
    }

    fun setScriptingEnabled(enabled: Boolean) {
        viewImpl.debugRequest("scripting", if (enabled) "on" else "off")
    }

    fun clearCache() {
        viewImpl.debugRequest("clear-cache")
    }

    fun collectGarbage() {
        viewImpl.debugRequest("collect-garbage")
    }

    fun setPinchZoomEnabled(enabled: Boolean) {
        pinchZoomEnabled = enabled
    }

    fun stopScrolling() {
        flinger.forceFinished(true)
    }

    /** Re-emit the current viewport size and pixel ratio to WebContent. */
    fun syncViewport() {
        if (width <= 0 || height <= 0) return
        val pixelDensity = context.resources.displayMetrics.density
        viewImpl.setDevicePixelRatio(pixelDensity)
        viewImpl.setViewportGeometry(width, height)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // Multi-touch (typically pinch) is consumed by the scale detector;
        // don't double-dispatch it as a scroll.
        if (event.pointerCount > 1) {
            isScrollingGesture = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (isScalingGesture)
            return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flinger.forceFinished(true)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                isScrollingGesture = false
                parent?.requestDisallowInterceptTouchEvent(true)
                viewImpl.mouseEvent(MotionEvent.ACTION_MOVE, event.x, event.y, event.rawX, event.rawY)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                if (!isScrollingGesture && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop))
                    isScrollingGesture = true

                if (isScrollingGesture) {
                    // Send the scroll to the engine AND shift the displayed bitmap
                    // by the same delta right now. This gives instant 1:1 finger
                    // tracking even while the engine is still painting the previous
                    // frame; onEnginePainted() reconciles the offset once the engine
                    // catches up. The previous code waited for the engine round-trip
                    // on every pixel, which is what made dragging feel stuck.
                    val stepDx = event.x - lastX
                    val stepDy = event.y - lastY
                    val wheelDx = (-stepDx).roundToInt()
                    val wheelDy = (-stepDy).roundToInt()
                    if (wheelDx != 0 || wheelDy != 0) {
                        composScrollX += wheelDx
                        composScrollY += wheelDy
                        outstandingWheelX += wheelDx
                        outstandingWheelY += wheelDy
                        clampComposScroll()
                        viewImpl.wheelEvent(event.x, event.y, event.rawX, event.rawY, wheelDx, wheelDy)
                        postInvalidateOnAnimation()
                    }
                }

                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isScrollingGesture) {
                    viewImpl.mouseEvent(MotionEvent.ACTION_DOWN, event.x, event.y, event.rawX, event.rawY)
                    viewImpl.mouseEvent(MotionEvent.ACTION_UP, event.x, event.y, event.rawX, event.rawY)
                    performClick()
                } else {
                    // If user swiped down from the very top while not scrolled,
                    // emit a pull-to-refresh signal. Real overscroll detection
                    // would require knowing the current scroll position from
                    // the engine; this is a useful approximation.
                    val totalDy = event.y - downY
                    if (totalDy > resources.displayMetrics.density * 96 && abs(event.x - downX) < totalDy && downY < resources.displayMetrics.density * 80)
                        onSwipeRefresh()
                }
                isScrollingGesture = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isScrollingGesture = false
                return true
            }

            else -> {
                return super.onTouchEvent(event)
            }
        }
    }

    override fun computeScroll() {
        if (flinger.computeScrollOffset()) {
            val x = flinger.currX
            val y = flinger.currY
            val dx = x - lastFlingX
            val dy = y - lastFlingY
            lastFlingX = x
            lastFlingY = y
            if (dx != 0 || dy != 0) {
                // Same predictive compositing as touch drag so momentum flings
                // track smoothly instead of waiting on the engine each frame.
                composScrollX += dx
                composScrollY += dy
                outstandingWheelX += dx
                outstandingWheelY += dy
                clampComposScroll()
                viewImpl.wheelEvent(
                    width / 2f, height / 2f,
                    width / 2f, height / 2f,
                    dx, dy
                )
            }
            postInvalidateOnAnimation()
        }
    }

    /** Keep the predicted offset bounded so an edge or a slow engine frame can't
     *  open a huge blank gap; reconciliation normally keeps this near zero. */
    private fun clampComposScroll() {
        val maxX = width.toFloat().coerceAtLeast(1f)
        val maxY = height.toFloat().coerceAtLeast(1f)
        composScrollX = composScrollX.coerceIn(-maxX, maxX)
        composScrollY = composScrollY.coerceIn(-maxY, maxY)
    }

    /** Called from the native bridge when the engine has painted a fresh frame.
     *  The new front bitmap already reflects every wheel delta we've sent, so we
     *  subtract the outstanding deltas from the visual offset (handing the motion
     *  off from our predicted translate to the engine's real render) and drop the
     *  pinch scale now that the engine has re-rendered at the new zoom. */
    fun onEnginePainted() {
        composScrollX -= outstandingWheelX
        composScrollY -= outstandingWheelY
        outstandingWheelX = 0
        outstandingWheelY = 0
        clampComposScroll()
        if (!isScalingGesture && pinchScale != 1f)
            pinchScale = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Only (re)allocate the scratch bitmap when it actually needs to grow.
        // System bar animations cause a flood of small size changes; allocating
        // a screen-sized RGBA8888 each time costs several MB of GC pressure and
        // visibly hurts scroll smoothness.
        if (!::contentBitmap.isInitialized || contentBitmap.width < w || contentBitmap.height < h) {
            val targetW = maxOf(w, if (::contentBitmap.isInitialized) contentBitmap.width else 0)
            val targetH = maxOf(h, if (::contentBitmap.isInitialized) contentBitmap.height else 0)
            contentBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        }

        val pixelDensity = context.resources.displayMetrics.density
        viewImpl.setDevicePixelRatio(pixelDensity)

        // FIXME: Account for scroll offset when view supports scrolling
        viewImpl.setViewportGeometry(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        viewImpl.drawIntoBitmap(contentBitmap)

        val transformed = composScrollX != 0f || composScrollY != 0f || pinchScale != 1f
        if (transformed) {
            canvas.save()
            // Pinch zoom is applied around the gesture focus; scroll is a straight
            // translate of the painted bitmap. Both are purely visual and reconciled
            // against the engine in onEnginePainted().
            if (pinchScale != 1f)
                canvas.scale(pinchScale, pinchScale, pinchFocusX, pinchFocusY)
            if (composScrollX != 0f || composScrollY != 0f)
                canvas.translate(-composScrollX, -composScrollY)
            canvas.drawBitmap(contentBitmap, 0f, 0f, null)
            canvas.restore()
        } else {
            canvas.drawBitmap(contentBitmap, 0f, 0f, null)
        }
    }

    companion object {
    }

}
