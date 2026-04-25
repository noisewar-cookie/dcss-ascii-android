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

public class DirectionalTouchView extends View implements  GestureDetector.OnGestureListener, ScaleGestureDetector.OnScaleGestureListener
{

	private GestureDetector gestureDetector;
	private ScaleGestureDetector scaleGestureDetector;
	private KeyListener keyListener = null;
	private PassThroughListener passThroughListener;
	private View messageView;
	private boolean messageAreaTouch = false;
	private boolean forwardingToMessage = false;
	private float downX, downY;
	private int touchSlop;
	
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

	private boolean isInMessageView(MotionEvent e)
	{
		if (messageView == null || e == null)
			return false;
		int[] loc = new int[2];
		messageView.getLocationOnScreen(loc);
		float ey = e.getRawY();
		return ey >= loc[1] && ey < loc[1] + messageView.getHeight();
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
		passThroughListener.onLongPress(e);
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY)
	{
		if (forwardingToMessage || messageAreaTouch)
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

		if (action == MotionEvent.ACTION_DOWN)
		{
			forwardingToMessage = false;
			messageAreaTouch = isInMessageView(event);
			if (messageAreaTouch)
			{
				downX = event.getRawX();
				downY = event.getRawY();
			}
		}

		if (messageAreaTouch && !forwardingToMessage
				&& action == MotionEvent.ACTION_MOVE)
		{
			float dx = event.getRawX() - downX;
			float dy = event.getRawY() - downY;
			if (dx * dx + dy * dy > touchSlop * touchSlop)
			{
				forwardingToMessage = true;
				MotionEvent syntheticDown = MotionEvent.obtain(event);
				syntheticDown.setAction(MotionEvent.ACTION_DOWN);
				MotionEvent translated = translateToView(syntheticDown,
						messageView);
				messageView.onTouchEvent(translated);
				translated.recycle();
				syntheticDown.recycle();
			}
		}

		if (forwardingToMessage)
		{
			MotionEvent translated = translateToView(event, messageView);
			messageView.onTouchEvent(translated);
			translated.recycle();
			if (action == MotionEvent.ACTION_UP
					|| action == MotionEvent.ACTION_CANCEL)
			{
				forwardingToMessage = false;
				messageAreaTouch = false;
			}
			return true;
		}

		if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
			messageAreaTouch = false;
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
