package com.crawlmb.keyboard;

import android.content.Context;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.ScaleGestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.crawlmb.keylistener.GameKeyListener;
import com.crawlmb.PassThroughListener;
import com.crawlmb.Preferences;
import com.crawlmb.keylistener.KeyListener;
import com.crawlmb.view.RegionRouter;
import com.crawlmb.view.RegionTermView;

public class DirectionalTouchView extends View implements  GestureDetector.OnGestureListener, ScaleGestureDetector.OnScaleGestureListener
{

	private GestureDetector gestureDetector;
	private ScaleGestureDetector scaleGestureDetector;
	private KeyListener keyListener = null;
	private PassThroughListener passThroughListener;
	private View messageView;
	private View statusBarView;
	private RegionTermView menuView;
	private RegionTermView skillsView;
	private RegionTermView itemsView;
	private RegionTermView[] extraScrollTargets = null;
	private RegionRouter router;
	private View activeForwardTarget = null;
	private boolean targetAreaTouch = false;
	private boolean forwardingToTarget = false;
	private float downX, downY;
	private int touchSlop;

	// Map panel pinch-zoom (portrait). A scale gesture whose focal point
	// starts over mapView zooms the rendered bitmap inside the panel — the
	// panel's measured size doesn't change, so content past its edges is
	// clipped. Zoom is stepped through three fixed levels [1.0, step1, step2]
	// and zoom-in only — pinch-out advances one level, pinch-in retreats one
	// level. Exactly one step fires per pinch gesture (regardless of how far
	// the fingers spread), so reaching step2 from the default takes two
	// separate pinch-out gestures. Zoom is session-only: mapZoomLevel resets
	// to 0 every time the view is wired.
	private RegionTermView mapView;
	private float mapZoomStep1 = 1.25f;
	private float mapZoomStep2 = 1.5f;
	private int mapZoomLevel = 0;
	private float mapZoomAnchorSpan = 0f;
	private boolean scalingMap = false;
	private boolean mapZoomStepFiredThisPinch = false;
	private static final float MAP_ZOOM_STEP_RATIO = 1.12f;

	// 9-grid hold: holding a direction cell past HOLD_INITIAL_DELAY_MS starts
	// repeating that direction every touchRepeatInterval ms (0 = disabled).
	// Holding the center cell instead fires a one-shot long rest ('5'). A
	// fired hold suppresses the release tap so the action isn't doubled.
	private static final int HOLD_INITIAL_DELAY_MS = 400;
	private int gridHoldCell = 0;
	private float gridHoldX, gridHoldY;
	private boolean gridHoldFired = false;
	private int touchRepeatInterval = 0;
	private final Runnable gridHoldStart = new Runnable()
	{
		@Override
		public void run()
		{
			if (gridHoldCell == 0)
				return;
			gridHoldFired = true;
			performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
			if (gridHoldCell == 5)
			{
				// Long rest: DCSS's default CMD_REST binding is the '5' key.
				// The console port has no command channel, so we send the
				// keystroke; this breaks only if the user rebinds '5'.
				keyListener.addKey('5', 0);
				return;
			}
			sendGridDirection(gridHoldCell);
			if (touchRepeatInterval > 0)
				postDelayed(gridHoldRepeat, touchRepeatInterval);
		}
	};
	private final Runnable gridHoldRepeat = new Runnable()
	{
		@Override
		public void run()
		{
			if (gridHoldCell == 0 || gridHoldCell == 5)
				return;
			sendGridDirection(gridHoldCell);
			if (touchRepeatInterval > 0)
				postDelayed(gridHoldRepeat, touchRepeatInterval);
		}
	};

	// Two-finger long-press state. Single-finger long-press is disabled
	// because it collides with single-finger drag-scroll on the msg/menu
	// panels. Two fingers held still for the long-press timeout opens the
	// app context menu via passThroughListener.onLongPress.
	private boolean twoFingerArmed = false;
	private boolean twoFingerLongPressFired = false;
	private float p0DownX, p0DownY, p1DownX, p1DownY;
	private final Runnable twoFingerLongPressFire = new Runnable()
	{
		@Override
		public void run()
		{
			if (!twoFingerArmed || twoFingerLongPressFired
					|| passThroughListener == null)
				return;
			twoFingerArmed = false;
			twoFingerLongPressFired = true;
			// Drop any in-flight drag-scroll forwarding so the menu open
			// isn't followed by a stray drag once the popup closes.
			forwardingToTarget = false;
			targetAreaTouch = false;
			activeForwardTarget = null;
			passThroughListener.onLongPress(null);
		}
	};
	
	public DirectionalTouchView(Context context, KeyListener keyListener)
	{
		super(context);
		gestureDetector = new GestureDetector(context, this);
		scaleGestureDetector = new ScaleGestureDetector(context, this);
		this.keyListener = keyListener;
		touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
	}
	
	public void setPassThroughListener(PassThroughListener onGestureListener)
	{
		this.passThroughListener = onGestureListener;
	}

	public void setMessageView(View view)
	{
		this.messageView = view;
	}

	public void setStatusBarView(View view)
	{
		this.statusBarView = view;
	}

	public void setMenuView(RegionTermView view)
	{
		this.menuView = view;
	}

	public void setSkillsView(RegionTermView view)
	{
		this.skillsView = view;
	}

	public void setItemsView(RegionTermView view)
	{
		this.itemsView = view;
	}

	public void setRouter(RegionRouter router)
	{
		this.router = router;
	}

	// Register additional RegionTermView panels (newgame welcome / category
	// / desc / sub-options) as drag-scroll forwarding targets. Eligibility
	// is still checked per-touch via isScrollEnabled() and visibility, so
	// passing every newgame panel is safe — only ones that are both VISIBLE
	// and have a scroll axis enabled will be picked.
	public void setExtraScrollTargets(RegionTermView... targets)
	{
		this.extraScrollTargets = targets;
	}

	// Wire the map panel as a pinch-zoom target. step1/step2 are the two
	// zoom-in levels above the default (1.0). One pinch-out advances to
	// step1, a second pinch-out advances to step2; pinch-in reverses one
	// level per gesture. Zoom is applied as a content-scale transform in
	// RegionTermView.onDraw, so no DCSS redraw is needed — only an invalidate.
	public void setMapZoom(RegionTermView mapView, float step1, float step2)
	{
		this.mapView = mapView;
		this.mapZoomStep1 = step1;
		this.mapZoomStep2 = step2;
		this.mapZoomLevel = 0;
	}

	// Returns the currently-eligible drag-scroll forwarding target whose
	// bounds contain this touch, or null if none. Eligibility = view is
	// VISIBLE and (for RegionTermView) at least one scroll axis is enabled.
	// msgView is gameplay-only; menuView/skillsView are menu-only and
	// mutually exclusive — only one is VISIBLE at a time, so order doesn't
	// matter beyond visibility.
	private View pickForwardTarget(MotionEvent e)
	{
		if (e == null)
			return null;
		int extras = extraScrollTargets == null ? 0 : extraScrollTargets.length;
		View[] candidates = new View[5 + extras];
		candidates[0] = messageView;
		candidates[1] = statusBarView;
		candidates[2] = menuView;
		candidates[3] = skillsView;
		candidates[4] = itemsView;
		for (int i = 0; i < extras; i++)
			candidates[5 + i] = extraScrollTargets[i];
		for (View v : candidates)
		{
			if (v == null)
				continue;
			// isShown() walks ancestors; necessary because the newgame
			// desc panels live inside species/background container
			// LinearLayouts that the router toggles INVISIBLE. The desc
			// view's own visibility stays VISIBLE, so a plain
			// getVisibility() check picks ngsDesc even when the user is
			// on the background screen, forwarding drags to an empty
			// off-screen panel instead of ngbDesc.
			if (!v.isShown())
				continue;
			if (v instanceof RegionTermView
					&& !((RegionTermView) v).isScrollEnabled())
				continue;
			int[] loc = new int[2];
			v.getLocationOnScreen(loc);
			float ey = e.getRawY();
			if (ey >= loc[1] && ey < loc[1] + v.getHeight())
				return v;
		}
		return null;
	}
	

	@Override
	public boolean onDown(MotionEvent e) 
	{
		return true;
	}

	@Override
	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) 
	{
		return true;
	}

	@Override
	public void onLongPress(MotionEvent e)
	{
		// Single-finger long-press intentionally disabled — it collides
		// with single-finger drag-scroll on the msg/menu panels. The
		// app context menu now opens on a two-finger long-press, handled
		// in onTouchEvent.
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY)
	{
		if (forwardingToTarget || targetAreaTouch)
			return true;
		passThroughListener.onScroll(e1, e2, distanceX, distanceY);
		return true;
	}

	@Override
	public void onShowPress(MotionEvent e) 
	{
		
	}

	@Override
	public boolean onSingleTapUp(MotionEvent event)
	{
		if (gridHoldFired)
		{
			// A hold action (direction repeat or long rest) already fired
			// for this touch — swallow the release tap so it isn't doubled.
			gridHoldFired = false;
			return true;
		}
		if (!Preferences.getEnableTouch())
			return false;
		performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);

		int x = (int) event.getX();
		int y = (int) event.getY();

		int r, c;
		c = (x * 3) / getWidth();
		r = (y * 3) / getHeight();

		// On the newgame species/background screens the upstream menu is
		// a 3-col grid but we render it as a stacked single-column list,
		// so a horizontal tap maps to a column the user can't see and
		// jumps the selection to a seemingly random species/background.
		// Drop the left/middle/right column distinction here: only
		// up/down direction keys are forwarded; horizontal taps are
		// ignored.
		if (isNewgameColumnIgnored(c))
			return true;

		keyListener.addDirectionKey(cellToKey((2 - r) * 3 + c + 1));

		return true;
	}

	// Map a 9-grid cell (1 = bottom-left ... 9 = top-right) to its curses
	// keypad keycode. Center (5) = KEY_B2 (wait a turn on a plain tap).
	private static int cellToKey(int cell)
	{
		switch (cell)
		{
		case 1: return GameKeyListener.KEY_C1;
		case 2: return GameKeyListener.KEY_DOWN;
		case 3: return GameKeyListener.KEY_C3;
		case 4: return GameKeyListener.KEY_LEFT;
		case 5: return GameKeyListener.KEY_B2;
		case 6: return GameKeyListener.KEY_RIGHT;
		case 7: return GameKeyListener.KEY_A1;
		case 8: return GameKeyListener.KEY_UP;
		case 9: return GameKeyListener.KEY_A3;
		default: return 0;
		}
	}

	// Queue the direction key for a 9-grid cell. Center (5) is handled by
	// callers (tap = wait, hold = long rest) and is skipped here.
	private void sendGridDirection(int cell)
	{
		if (cell == 5)
			return;
		int key = cellToKey(cell);
		if (key != 0)
			keyListener.addDirectionKey(key);
	}

	// True when a horizontal tap column must be ignored: the newgame
	// species/background pickers render as a single column, so only the
	// middle column (c == 1) is meaningful.
	private boolean isNewgameColumnIgnored(int c)
	{
		return router != null
				&& (router.getCurrentMenuType() == RegionRouter.MenuType.NEWGAME_SPECIES
					|| router.getCurrentMenuType() == RegionRouter.MenuType.NEWGAME_BACKGROUND)
				&& c != 1;
	}

	// Begin tracking a potential 9-grid hold for this touch. Schedules the
	// hold action (direction repeat, or center long rest) at
	// HOLD_INITIAL_DELAY_MS. Directional repeat is only armed when a non-zero
	// repeat interval is configured; the center long-rest hold is always
	// armed.
	private void startGridHold(MotionEvent event)
	{
		cancelGridHold();
		if (!Preferences.getEnableTouch())
			return;
		int w = getWidth();
		int h = getHeight();
		if (w == 0 || h == 0)
			return;
		int c = ((int) event.getX() * 3) / w;
		int r = ((int) event.getY() * 3) / h;
		if (c < 0 || c > 2 || r < 0 || r > 2)
			return;
		if (isNewgameColumnIgnored(c))
			return;
		int cell = (2 - r) * 3 + c + 1;
		touchRepeatInterval = Preferences.getTouchDirectionRepeat();
		if (cell != 5 && touchRepeatInterval <= 0)
			return;
		gridHoldCell = cell;
		gridHoldX = event.getX();
		gridHoldY = event.getY();
		postDelayed(gridHoldStart, HOLD_INITIAL_DELAY_MS);
	}

	private void cancelGridHold()
	{
		gridHoldCell = 0;
		removeCallbacks(gridHoldStart);
		removeCallbacks(gridHoldRepeat);
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event)
	{
		int action = event.getAction();
		int actionMasked = event.getActionMasked();

		if (action == MotionEvent.ACTION_DOWN)
		{
			forwardingToTarget = false;
			activeForwardTarget = pickForwardTarget(event);
			targetAreaTouch = activeForwardTarget != null;
			if (targetAreaTouch)
			{
				downX = event.getRawX();
				downY = event.getRawY();
			}
			twoFingerArmed = false;
			twoFingerLongPressFired = false;
			removeCallbacks(twoFingerLongPressFire);
			gridHoldFired = false;
			// Arm the hold even when the touch starts over a drag-scroll
			// target: hold and drag are mutually exclusive in practice
			// because the ACTION_MOVE slop check below cancels the hold as
			// soon as the finger moves far enough to be a real scroll.
			// Letting the hold arm here means hold-to-repeat also works
			// over scrollable menus (e.g. holding 'up' to keep paging
			// inventory) and over the level map panel (where fullView is
			// registered as a scroll target).
			startGridHold(event);
		}

		// A second finger means a pinch/two-finger gesture, never a 9-grid
		// hold — cancel any pending hold so it can't fire mid-pinch.
		if (actionMasked == MotionEvent.ACTION_POINTER_DOWN)
			cancelGridHold();

		// Two-finger long-press: arm when a second finger touches down,
		// disarm if either finger moves beyond touch slop or any finger
		// lifts before the timeout fires.
		if (actionMasked == MotionEvent.ACTION_POINTER_DOWN
				&& event.getPointerCount() >= 2 && !twoFingerLongPressFired)
		{
			p0DownX = event.getX(0);
			p0DownY = event.getY(0);
			p1DownX = event.getX(1);
			p1DownY = event.getY(1);
			twoFingerArmed = true;
			removeCallbacks(twoFingerLongPressFire);
			postDelayed(twoFingerLongPressFire,
					ViewConfiguration.getLongPressTimeout());
		}
		if (twoFingerArmed && actionMasked == MotionEvent.ACTION_MOVE
				&& event.getPointerCount() >= 2)
		{
			float dx0 = event.getX(0) - p0DownX;
			float dy0 = event.getY(0) - p0DownY;
			float dx1 = event.getX(1) - p1DownX;
			float dy1 = event.getY(1) - p1DownY;
			int slopSq = touchSlop * touchSlop;
			if (dx0 * dx0 + dy0 * dy0 > slopSq
					|| dx1 * dx1 + dy1 * dy1 > slopSq)
			{
				twoFingerArmed = false;
				removeCallbacks(twoFingerLongPressFire);
			}
		}
		if (twoFingerArmed
				&& (actionMasked == MotionEvent.ACTION_POINTER_UP
					|| actionMasked == MotionEvent.ACTION_UP
					|| actionMasked == MotionEvent.ACTION_CANCEL))
		{
			twoFingerArmed = false;
			removeCallbacks(twoFingerLongPressFire);
		}

		// A drag beyond touch slop is a scroll/swipe, not a hold — drop any
		// pending 9-grid hold so it doesn't fire after the finger moved off
		// the original cell.
		if (gridHoldCell != 0 && actionMasked == MotionEvent.ACTION_MOVE)
		{
			float gdx = event.getX() - gridHoldX;
			float gdy = event.getY() - gridHoldY;
			if (gdx * gdx + gdy * gdy > touchSlop * touchSlop)
				cancelGridHold();
		}

		// After the menu opens, swallow remaining gesture events until
		// all fingers lift so we don't generate stray taps or scrolls
		// that would land under the popup.
		if (twoFingerLongPressFired)
		{
			if (actionMasked == MotionEvent.ACTION_UP
					|| actionMasked == MotionEvent.ACTION_CANCEL)
				twoFingerLongPressFired = false;
			return true;
		}

		if (targetAreaTouch && !forwardingToTarget
				&& action == MotionEvent.ACTION_MOVE)
		{
			float dx = event.getRawX() - downX;
			float dy = event.getRawY() - downY;
			if (dx * dx + dy * dy > touchSlop * touchSlop)
			{
				forwardingToTarget = true;
				MotionEvent syntheticDown = MotionEvent.obtain(event);
				syntheticDown.setAction(MotionEvent.ACTION_DOWN);
				MotionEvent translated = translateToView(syntheticDown,
						activeForwardTarget);
				activeForwardTarget.onTouchEvent(translated);
				translated.recycle();
				syntheticDown.recycle();
			}
		}

		if (forwardingToTarget && activeForwardTarget != null)
		{
			MotionEvent translated = translateToView(event, activeForwardTarget);
			activeForwardTarget.onTouchEvent(translated);
			translated.recycle();
			if (action == MotionEvent.ACTION_UP
					|| action == MotionEvent.ACTION_CANCEL)
			{
				forwardingToTarget = false;
				targetAreaTouch = false;
				activeForwardTarget = null;
			}
			return true;
		}

		if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
		{
			targetAreaTouch = false;
			activeForwardTarget = null;
			// Drop the timers but keep gridHoldFired — onSingleTapUp (fired
			// from the gestureDetector call below) needs it to decide
			// whether to swallow this release.
			cancelGridHold();
		}
		if (action == MotionEvent.ACTION_UP)
			passThroughListener.savePosition();

		boolean scaleGestureHandled = scaleGestureDetector.onTouchEvent(event);
		boolean gestureHandled = gestureDetector.onTouchEvent(event);
		return scaleGestureHandled || gestureHandled;
	}

	private MotionEvent translateToView(MotionEvent e, View target)
	{
		int[] loc = new int[2];
		target.getLocationOnScreen(loc);
		int[] myLoc = new int[2];
		getLocationOnScreen(myLoc);
		MotionEvent translated = MotionEvent.obtain(e);
		translated.offsetLocation(myLoc[0] - loc[0], myLoc[1] - loc[1]);
		return translated;
	}

  @Override
  public boolean onScale(ScaleGestureDetector detector)
  {
    if (scalingMap)
    {
      // One step per pinch gesture: once a step has fired this pinch we
      // ignore further span changes until the user lifts and pinches again.
      if (!mapZoomStepFiredThisPinch && mapZoomAnchorSpan > 0f)
      {
        float ratio = detector.getCurrentSpan() / mapZoomAnchorSpan;
        if (ratio >= MAP_ZOOM_STEP_RATIO)
        {
          if (applyMapZoomStep(+1))
            mapZoomStepFiredThisPinch = true;
        }
        else if (ratio <= 1f / MAP_ZOOM_STEP_RATIO)
        {
          if (applyMapZoomStep(-1))
            mapZoomStepFiredThisPinch = true;
        }
      }
      return true;
    }
    return passThroughListener.onScale(detector);
  }

  @Override
  public boolean onScaleBegin(ScaleGestureDetector detector)
  {
    cancelGridHold();
    scalingMap = isOverMapView(detector.getFocusX(), detector.getFocusY());
    if (scalingMap)
    {
      mapZoomAnchorSpan = detector.getCurrentSpan();
      mapZoomStepFiredThisPinch = false;
      return true;
    }
    return passThroughListener.onScaleBegin(detector);
  }

  @Override
  public void onScaleEnd(ScaleGestureDetector detector)
  {
    scalingMap = false;
    mapZoomAnchorSpan = 0f;
    mapZoomStepFiredThisPinch = false;
  }

  // True when (focusX, focusY) — in this view's coordinate space — falls
  // within the visible map panel's on-screen bounds.
  private boolean isOverMapView(float focusX, float focusY)
  {
    if (mapView == null || !mapView.isShown())
      return false;
    int[] mapLoc = new int[2];
    mapView.getLocationOnScreen(mapLoc);
    int[] myLoc = new int[2];
    getLocationOnScreen(myLoc);
    float screenX = focusX + myLoc[0];
    float screenY = focusY + myLoc[1];
    return screenX >= mapLoc[0] && screenX < mapLoc[0] + mapView.getWidth()
        && screenY >= mapLoc[1] && screenY < mapLoc[1] + mapView.getHeight();
  }

  // Step the map zoom one level in the given direction (+1 = zoom in,
  // -1 = zoom out) through the fixed level list [1.0, step1, step2].
  // Zoom-in only: clamped to [0, 2]. Returns true when the level actually
  // changed (so callers can mark the pinch's step as consumed).
  private boolean applyMapZoomStep(int direction)
  {
    if (mapView == null)
      return false;
    int next = mapZoomLevel + direction;
    if (next < 0)
      next = 0;
    else if (next > 2)
      next = 2;
    if (next == mapZoomLevel)
      return false;
    mapZoomLevel = next;
    float factor = mapZoomLevel == 0 ? 1.0f
        : mapZoomLevel == 1 ? mapZoomStep1
        : mapZoomStep2;
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    mapView.setContentZoom(factor);
    return true;
  }

}
