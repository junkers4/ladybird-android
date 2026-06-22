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
    private var lockVerticalScrollAxis = false
    private var lockHorizontalScrollAxis = false
    private var isScalingGesture = false
    private var pinchZoomEnabled = true
    private val flinger = OverScroller(context)
    private var lastFlingX = 0
    private var lastFlingY = 0
    // Coalesce wheel input to Android frame boundaries. The previous
    // engine-ack-paced path avoided IPC backlogs, but it also made touch feel
    // one engine repaint behind; the later visual-offset workaround could expose
    // blank/shifted bitmap edges. Now that paint/blit are fast, one wheel batch
    // per Choreographer frame is both responsive and stable.
    private var pendingWheelDx = 0
    private var pendingWheelDy = 0
    private var pendingWheelX = 0f
    private var pendingWheelY = 0f
    private var pendingWheelRawX = 0f
    private var pendingWheelRawY = 0f
    private var wheelFrameScheduled = false
    private var pinchGestureScale = 1f
    private var pinchPreviewScale = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchCommitPending = false
    private val frameCallback: android.view.Choreographer.FrameCallback =
        android.view.Choreographer.FrameCallback {
            wheelFrameScheduled = false
            flushWheel()
        }
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
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!pinchZoomEnabled) return false
            isScalingGesture = true
            flinger.forceFinished(true)
            pinchGestureScale = 1f
            pinchPreviewScale = 1f
            pinchCommitPending = false
            pinchFocusX = detector.focusX
            pinchFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Keep pinch visually smooth without flooding WebContent with zoom
            // re-layouts for every MotionEvent. The real discrete zoom is applied
            // once when the fingers lift.
            pinchFocusX = detector.focusX
            pinchFocusY = detector.focusY
            pinchGestureScale = (pinchGestureScale * detector.scaleFactor).coerceIn(0.55f, 1.85f)
            // Never preview zoom-out by shrinking the whole bitmap: that exposes
            // grey side gutters. Zoom-out commits at gesture end; zoom-in can be
            // previewed safely because it crops rather than revealing margins.
            pinchPreviewScale = pinchGestureScale.coerceAtLeast(1f)
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            var scale = pinchGestureScale
            isScalingGesture = false
            val oldZoomLevel = viewImpl.zoomLevel()
            var steps = 0
            while (scale > 1.08f && steps < 5) {
                viewImpl.zoomIn()
                syncViewport()
                scale /= 1.10f
                steps++
            }
            while (scale < 0.92f && steps < 5 && viewImpl.zoomLevel() > 1.0) {
                viewImpl.zoomOut()
                syncViewport()
                scale *= 1.10f
                steps++
            }
            if (viewImpl.zoomLevel() < 1.0) {
                viewImpl.zoomReset()
                syncViewport()
            }
            if (steps > 0 && viewImpl.zoomLevel() != oldZoomLevel) {
                // Keep the smooth bitmap preview visible until WebContent paints
                // the committed zoom level; otherwise the page snaps back for a
                // frame and looks like it is glitching.
                pinchCommitPending = true
            } else {
                pinchGestureScale = 1f
                pinchPreviewScale = 1f
            }
            postInvalidateOnAnimation()
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

    fun setContentBlockingEnabled(enabled: Boolean) {
        viewImpl.debugRequest("content-blocking", if (enabled) "on" else "off")
    }

    fun clearCache() {
        viewImpl.debugRequest("clear-cache")
    }

    /** Clear cookies created within the last [seconds] (0 = all cookies). */
    fun clearCookies(seconds: Long) {
        viewImpl.debugRequest("clear-cookies", seconds.toString())
    }

    fun collectGarbage() {
        viewImpl.debugRequest("collect-garbage")
    }

    fun setPinchZoomEnabled(enabled: Boolean) {
        pinchZoomEnabled = enabled
    }

    /** Route traffic through the active network compartment's proxy (or direct). */
    fun setNetworkProxy(type: String?, host: String?, port: Int) {
        viewImpl.setNetworkProxy(type, host, port)
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
                lockVerticalScrollAxis = false
                lockHorizontalScrollAxis = false
                pendingWheelDx = 0
                pendingWheelDy = 0
                parent?.requestDisallowInterceptTouchEvent(true)
                viewImpl.mouseEvent(MotionEvent.ACTION_MOVE, event.x, event.y, event.rawX, event.rawY)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.x - downX
                val totalDy = event.y - downY
                if (!isScrollingGesture && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                    isScrollingGesture = true
                    // Match mobile browser feel: once the gesture is clearly
                    // vertical, ignore horizontal jitter from the finger (and
                    // vice versa). This prevents pages from wobbling sideways
                    // while the user is simply trying to scroll down.
                    lockVerticalScrollAxis = abs(totalDy) > abs(totalDx) * 1.2f
                    lockHorizontalScrollAxis = abs(totalDx) > abs(totalDy) * 1.2f
                }

                if (isScrollingGesture) {
                    val stepDx = event.x - lastX
                    val stepDy = event.y - lastY
                    val allowHorizontalPan = viewImpl.zoomLevel() > 1.0
                    val wheelDx = if (!allowHorizontalPan || lockVerticalScrollAxis) 0 else (-stepDx).roundToInt()
                    val wheelDy = if (lockHorizontalScrollAxis) 0 else (-stepDy).roundToInt()
                    if (wheelDx != 0 || wheelDy != 0)
                        enqueueWheel(wheelDx, wheelDy, event.x, event.y, event.rawX, event.rawY)
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
                    flushWheel()
                    // If user swiped down from the very top while not scrolled,
                    // emit a pull-to-refresh signal. Real overscroll detection
                    // would require knowing the current scroll position from
                    // the engine; this is a useful approximation.
                    val totalDy = event.y - downY
                    if (totalDy > resources.displayMetrics.density * 96 && abs(event.x - downX) < totalDy && downY < resources.displayMetrics.density * 80)
                        onSwipeRefresh()
                }
                isScrollingGesture = false
                lockVerticalScrollAxis = false
                lockHorizontalScrollAxis = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                flushWheel()
                isScrollingGesture = false
                lockVerticalScrollAxis = false
                lockHorizontalScrollAxis = false
                return true
            }

            else -> {
                return super.onTouchEvent(event)
            }
        }
    }

    private fun enqueueWheel(wheelDx: Int, wheelDy: Int, x: Float, y: Float, rawX: Float, rawY: Float) {
        pendingWheelDx += wheelDx
        pendingWheelDy += wheelDy
        pendingWheelX = x
        pendingWheelY = y
        pendingWheelRawX = rawX
        pendingWheelRawY = rawY
        scheduleWheelFrame()
    }

    private fun scheduleWheelFrame() {
        if (wheelFrameScheduled)
            return
        wheelFrameScheduled = true
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun flushWheel() {
        if (pendingWheelDx == 0 && pendingWheelDy == 0) return
        val dx = pendingWheelDx
        val dy = pendingWheelDy
        pendingWheelDx = 0
        pendingWheelDy = 0
        viewImpl.wheelEvent(pendingWheelX, pendingWheelY, pendingWheelRawX, pendingWheelRawY, dx, dy)
    }

    /** Called from WebViewImplementation.invalidateLayout() after every engine paint. */
    fun onEnginePainted() {
        if (pendingWheelDx != 0 || pendingWheelDy != 0)
            scheduleWheelFrame()
        if (pinchCommitPending) {
            pinchCommitPending = false
            pinchGestureScale = 1f
            pinchPreviewScale = 1f
            postInvalidateOnAnimation()
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
            if (dx != 0 || dy != 0)
                enqueueWheel(dx, dy, width / 2f, height / 2f, width / 2f, height / 2f)
            postInvalidateOnAnimation()
        }
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

        viewImpl.drawIntoBitmap(contentBitmap);
        if (pinchPreviewScale != 1f) {
            val checkpoint = canvas.save()
            canvas.scale(pinchPreviewScale, pinchPreviewScale, pinchFocusX, pinchFocusY)
            canvas.drawBitmap(contentBitmap, 0f, 0f, null)
            canvas.restoreToCount(checkpoint)
        } else {
            canvas.drawBitmap(contentBitmap, 0f, 0f, null)
        }
    }

    companion object {
    }

}
