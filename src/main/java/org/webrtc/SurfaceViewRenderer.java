/*
 *  Copyright 2015 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.content.Context;
import android.content.res.Resources.NotFoundException;
import android.graphics.Point;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewConfiguration;
import android.view.ViewParent;

/**
 * Display the video stream on a SurfaceView.
 */
public class SurfaceViewRenderer extends SurfaceView
    implements SurfaceHolder.Callback, VideoSink, RendererCommon.RendererEvents {
  private static final String TAG = "SurfaceViewRenderer";

  // Cached resource name.
  private final String resourceName;
  private final RendererCommon.VideoLayoutMeasure videoLayoutMeasure =
      new RendererCommon.VideoLayoutMeasure();
  private final SurfaceEglRenderer eglRenderer;

  // Callback for reporting renderer events. Read-only after initialization so no lock required.
  private RendererCommon.RendererEvents rendererEvents;

  // Accessed only on the main thread.
  private int rotatedFrameWidth;
  private int rotatedFrameHeight;
  private boolean enableFixedSize;
  private int surfaceWidth;
  private int surfaceHeight;

  private static final float MAX_ZOOM = 8f;
  private ScaleGestureDetector scaleDetector;
  private int touchSlop;
  private float zoom = 1f;
  private float panX = 0f;
  private float panY = 0f;
  private float lastTouchX;
  private float lastTouchY;
  private float downX;
  private float downY;
  private boolean moved;
  private int activePointerId = MotionEvent.INVALID_POINTER_ID;
  private long lastTapMs = 0;
  private int lastMeasuredWidth = -1;
  private int lastMeasuredHeight = -1;

  /**
   * Standard View constructor. In order to render something, you must first call init().
   */
  public SurfaceViewRenderer(Context context) {
    super(context);
    this.resourceName = getResourceName();
    eglRenderer = new SurfaceEglRenderer(resourceName);
    getHolder().addCallback(this);
    getHolder().addCallback(eglRenderer);
    setupGestures(context);
  }

  /**
   * Standard View constructor. In order to render something, you must first call init().
   */
  public SurfaceViewRenderer(Context context, AttributeSet attrs) {
    super(context, attrs);
    this.resourceName = getResourceName();
    eglRenderer = new SurfaceEglRenderer(resourceName);
    getHolder().addCallback(this);
    getHolder().addCallback(eglRenderer);
    setupGestures(context);
  }

  /**
   * Initialize this class, sharing resources with `sharedContext`. It is allowed to call init() to
   * reinitialize the renderer after a previous init()/release() cycle.
   */
  public void init(EglBase.Context sharedContext, RendererCommon.RendererEvents rendererEvents) {
    init(sharedContext, rendererEvents, EglBase.CONFIG_PLAIN, new GlRectDrawer());
  }

  /**
   * Initialize this class, sharing resources with `sharedContext`. The custom `drawer` will be used
   * for drawing frames on the EGLSurface. This class is responsible for calling release() on
   * `drawer`. It is allowed to call init() to reinitialize the renderer after a previous
   * init()/release() cycle.
   */
  public void init(final EglBase.Context sharedContext,
      RendererCommon.RendererEvents rendererEvents, final int[] configAttributes,
      RendererCommon.GlDrawer drawer) {
    ThreadUtils.checkIsOnMainThread();
    this.rendererEvents = rendererEvents;
    rotatedFrameWidth = 0;
    rotatedFrameHeight = 0;
    eglRenderer.init(sharedContext, this /* rendererEvents */, configAttributes, drawer);
  }

  /**
   * Block until any pending frame is returned and all GL resources released, even if an interrupt
   * occurs. If an interrupt occurs during release(), the interrupt flag will be set. This function
   * should be called before the Activity is destroyed and the EGLContext is still valid. If you
   * don't call this function, the GL resources might leak.
   */
  public void release() {
    eglRenderer.release();
  }

  /**
   * Register a callback to be invoked when a new video frame has been received.
   *
   * @param listener The callback to be invoked. The callback will be invoked on the render thread.
   *                 It should be lightweight and must not call removeFrameListener.
   * @param scale    The scale of the Bitmap passed to the callback, or 0 if no Bitmap is
   *                 required.
   * @param drawer   Custom drawer to use for this frame listener.
   */
  public void addFrameListener(
      EglRenderer.FrameListener listener, float scale, RendererCommon.GlDrawer drawerParam) {
    eglRenderer.addFrameListener(listener, scale, drawerParam);
  }

  /**
   * Register a callback to be invoked when a new video frame has been received. This version uses
   * the drawer of the EglRenderer that was passed in init.
   *
   * @param listener The callback to be invoked. The callback will be invoked on the render thread.
   *                 It should be lightweight and must not call removeFrameListener.
   * @param scale    The scale of the Bitmap passed to the callback, or 0 if no Bitmap is
   *                 required.
   */
  public void addFrameListener(EglRenderer.FrameListener listener, float scale) {
    eglRenderer.addFrameListener(listener, scale);
  }

  public void removeFrameListener(EglRenderer.FrameListener listener) {
    eglRenderer.removeFrameListener(listener);
  }

  /**
   * Enables fixed size for the surface. This provides better performance but might be buggy on some
   * devices. By default this is turned off.
   */
  public void setEnableHardwareScaler(boolean enabled) {
    ThreadUtils.checkIsOnMainThread();
    enableFixedSize = enabled;
    updateSurfaceSize();
  }

  /**
   * Set if the video stream should be mirrored or not.
   */
  public void setMirror(final boolean mirror) {
    eglRenderer.setMirror(mirror);
  }

  /**
   * Set how the video will fill the allowed layout area.
   */
  public void setScalingType(RendererCommon.ScalingType scalingType) {
    ThreadUtils.checkIsOnMainThread();
    videoLayoutMeasure.setScalingType(scalingType);
    requestLayout();
  }

  public void setScalingType(RendererCommon.ScalingType scalingTypeMatchOrientation,
      RendererCommon.ScalingType scalingTypeMismatchOrientation) {
    ThreadUtils.checkIsOnMainThread();
    videoLayoutMeasure.setScalingType(scalingTypeMatchOrientation, scalingTypeMismatchOrientation);
    requestLayout();
  }

  /**
   * Limit render framerate.
   *
   * @param fps Limit render framerate to this value, or use Float.POSITIVE_INFINITY to disable fps
   *            reduction.
   */
  public void setFpsReduction(float fps) {
    eglRenderer.setFpsReduction(fps);
  }

  public void disableFpsReduction() {
    eglRenderer.disableFpsReduction();
  }

  public void pauseVideo() {
    eglRenderer.pauseVideo();
  }

  // VideoSink interface.
  @Override
  public void onFrame(VideoFrame frame) {
    eglRenderer.onFrame(frame);
  }

  private void setupGestures(Context context) {
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
      @Override
      public boolean onScale(ScaleGestureDetector detector) {
        zoom = Math.clamp(zoom * detector.getScaleFactor(), 1f, MAX_ZOOM);
        applyZoom();
        return true;
      }
    });
  }

  private void applyZoom() {
    float lim = 0.5f * (1f - 1f / zoom);
    panX = Math.max(-lim, Math.min(lim, panX));
    panY = Math.max(-lim, Math.min(lim, panY));
    eglRenderer.setZoom(zoom, panX, panY);
  }

  private void resetZoom() {
    zoom = 1f;
    panX = 0f;
    panY = 0f;
    applyZoom();
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    Logging.d(TAG, Logging.str(resourceName, "dispatchTouchEvent(). event: " + event));
    scaleDetector.onTouchEvent(event);
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        long now = SystemClock.uptimeMillis();
        if (now - lastTapMs < 300 && zoom > 1f) {
          resetZoom();
          lastTapMs = 0;
        } else {
          lastTapMs = now;
        }
        downX = lastTouchX = event.getX();
        downY = lastTouchY = event.getY();
        activePointerId = event.getPointerId(0);
        moved = false;
        if (zoom > 1f) disallowIntercept(true);
        break;
      case MotionEvent.ACTION_POINTER_DOWN:
        disallowIntercept(true);
        break;
      case MotionEvent.ACTION_MOVE:
        if (!scaleDetector.isInProgress() && zoom > 1f
            && activePointerId != MotionEvent.INVALID_POINTER_ID) {
          int idx = event.findPointerIndex(activePointerId);
          if (idx >= 0) {
            float x = event.getX(idx);
            float y = event.getY(idx);
            panX -= (x - lastTouchX) / (Math.max(1, getWidth()) * zoom);
            panY -= (y - lastTouchY) / (Math.max(1, getHeight()) * zoom);
            lastTouchX = x;
            lastTouchY = y;
            applyZoom();
          }
        }
        if (scaleDetector.isInProgress() || zoom > 1f) disallowIntercept(true);
        if (Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
          moved = true;
        }
        break;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        disallowIntercept(false);
        break;
      case MotionEvent.ACTION_POINTER_UP:
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        break;
    }
    if (scaleDetector.isInProgress() || event.getPointerCount() > 1 || (zoom > 1f && moved)) {
      return true;
    }
    return super.dispatchTouchEvent(event);
  }

  private void disallowIntercept(boolean disallow) {
    Logging.d(TAG, Logging.str(resourceName, "disallowIntercept(). disallow: " + disallow));
    ViewParent parent = getParent();
    if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
  }

  // View layout interface.
  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    ThreadUtils.checkIsOnMainThread();
    Point size =
        videoLayoutMeasure.measure(widthSpec, heightSpec, rotatedFrameWidth, rotatedFrameHeight);
    setMeasuredDimension(size.x, size.y);
    if (size.x != lastMeasuredWidth || size.y != lastMeasuredHeight) {
      lastMeasuredWidth = size.x;
      lastMeasuredHeight = size.y;
      Logging.d(TAG, Logging.str(resourceName, "onMeasure(). New size: " + size.x + "x" + size.y));
    }
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    ThreadUtils.checkIsOnMainThread();
    eglRenderer.setLayoutAspectRatio((right - left) / (float) (bottom - top));
    updateSurfaceSize();
  }

  private void updateSurfaceSize() {
    ThreadUtils.checkIsOnMainThread();
    if (enableFixedSize && rotatedFrameWidth != 0 && rotatedFrameHeight != 0 && getWidth() != 0
        && getHeight() != 0) {
      final float layoutAspectRatio = getWidth() / (float) getHeight();
      final float frameAspectRatio = rotatedFrameWidth / (float) rotatedFrameHeight;
      final int drawnFrameWidth;
      final int drawnFrameHeight;
      if (frameAspectRatio > layoutAspectRatio) {
        drawnFrameWidth = (int) (rotatedFrameHeight * layoutAspectRatio);
        drawnFrameHeight = rotatedFrameHeight;
      } else {
        drawnFrameWidth = rotatedFrameWidth;
        drawnFrameHeight = (int) (rotatedFrameWidth / layoutAspectRatio);
      }
      // Aspect ratio of the drawn frame and the view is the same.
      final int width = Math.min(getWidth(), drawnFrameWidth);
      final int height = Math.min(getHeight(), drawnFrameHeight);
      Logging.d(TAG, Logging.str(resourceName, "updateSurfaceSize. Layout size: " + getWidth() + "x" + getHeight() + ", frame size: "
          + rotatedFrameWidth + "x" + rotatedFrameHeight + ", requested surface size: " + width
          + "x" + height + ", old surface size: " + surfaceWidth + "x" + surfaceHeight));
      if (width != surfaceWidth || height != surfaceHeight) {
        surfaceWidth = width;
        surfaceHeight = height;
        getHolder().setFixedSize(width, height);
      }
    } else {
      surfaceWidth = surfaceHeight = 0;
      getHolder().setSizeFromLayout();
    }
  }

  // SurfaceHolder.Callback interface.
  @Override
  public void surfaceCreated(final SurfaceHolder holder) {
    ThreadUtils.checkIsOnMainThread();
    surfaceWidth = surfaceHeight = 0;
    updateSurfaceSize();
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {}

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

  private String getResourceName() {
    try {
      return getResources().getResourceEntryName(getId());
    } catch (NotFoundException e) {
      return "";
    }
  }

  /**
   * Post a task to clear the SurfaceView to a transparent uniform color.
   */
  public void clearImage() {
    eglRenderer.clearImage();
  }

  @Override
  public void onFirstFrameRendered() {
    if (rendererEvents != null) {
      rendererEvents.onFirstFrameRendered();
    }
  }

  @Override
  public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
    if (rendererEvents != null) {
      rendererEvents.onFrameResolutionChanged(videoWidth, videoHeight, rotation);
    }
    int rotatedWidth = rotation == 0 || rotation == 180 ? videoWidth : videoHeight;
    int rotatedHeight = rotation == 0 || rotation == 180 ? videoHeight : videoWidth;
    // run immediately if possible for ui thread tests
    postOrRun(() -> {
      rotatedFrameWidth = rotatedWidth;
      rotatedFrameHeight = rotatedHeight;
      resetZoom();
      updateSurfaceSize();
      requestLayout();
    });
  }

  private void postOrRun(Runnable r) {
    if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
      r.run();
    } else {
      post(r);
    }
  }
}
