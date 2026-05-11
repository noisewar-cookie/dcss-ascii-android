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
	private RegionTermView menuView;
	private RegionTermView skillsView;
	private RegionTermView[] extraScrollTargets = null;
	private RegionRouter router;
	private View activeForwardTarget = null;
	private boolean targetAreaTouch = false;
	private boolean forwardingToTarget = false;
	private float downX, downY;
	private int touchSlop;

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

	public void setMenuView(RegionTermView view)
	{
		this.menuView = view;
	}

	public void setSkillsView(RegionTermView view)
	{
		this.skillsView = view;
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
		View[] candidates = new View[3 + extras];
		candidates[0] = messageView;
		candidates[1] = menuView;
		candidates[2] = skillsView;
		for (int i = 0; i < extras; i++)
			candidates[3 + i] = extraScrollTargets[i];
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
		if (router != null
				&& (router.getCurrentMenuType() == RegionRouter.MenuType.NEWGAME_SPECIES
					|| router.getCurrentMenuType() == RegionRouter.MenuType.NEWGAME_BACKGROUND)
				&& c != 1)
		{
			return true;
		}

		int key = (2 - r) * 3 + c + 1;

		switch (key)
		{
		case 1:
			key = GameKeyListener.KEY_C1;
			break;
		case 2:
			key = GameKeyListener.KEY_DOWN;
			break;
		case 3:
			key = GameKeyListener.KEY_C3;
			break;
		case 4:
			key = GameKeyListener.KEY_LEFT;
			break;
		case 5: 
		   key = GameKeyListener.KEY_B2;
		   break; 
		case 6:
			key = GameKeyListener.KEY_RIGHT;
			break;
		case 7:
			key = GameKeyListener.KEY_A1;
			break;
		case 8:
			key = GameKeyListener.KEY_UP;
			break;
		case 9:
			key = GameKeyListener.KEY_A3;
			break;
		default:
			break;
		}

		keyListener.addDirectionKey(key);

		return true;
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
		}

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
    return passThroughListener.onScale(detector);
  }

  @Override
  public boolean onScaleBegin(ScaleGestureDetector detector)
  {
    return passThroughListener.onScaleBegin(detector);
  }

  @Override
  public void onScaleEnd(ScaleGestureDetector detector)
  {
  }

}
