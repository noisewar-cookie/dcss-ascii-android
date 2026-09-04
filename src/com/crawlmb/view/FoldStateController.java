package com.crawlmb.view;

import android.app.Activity;
import android.graphics.Rect;

import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

import com.crawlmb.Preferences;

import java.util.concurrent.Executor;

// Watches the activity's WindowLayoutInfo for a vertical fold (book posture)
// and reports whether unfolded mode should be active. "Unfolded" here
// means one logical display split left/right by the hinge — the Samsung/Pixel
// Fold model — not two OS-level displays. A vertical FoldingFeature that is
// FLAT or HALF_OPENED counts as "open"; the closed/cover state reports no
// folding feature and collapses back to the single-screen layout.
//
// The listener is bound in onStart and released in onStop (per the adapter's
// lifecycle contract). The callback runs on the main thread.
public class FoldStateController
{
	public interface Listener
	{
		// unfoldedActive reflects (open vertical fold) AND the user's gate pref.
		// posture is null when no usable fold is present.
		void onFoldStateChanged(boolean unfoldedActive, Posture posture);
	}

	// Geometry of the vertical fold, in window (screenLayout) coordinates.
	// The two halves are [0, leftWidth) and [rightStart, windowWidth). On a
	// seamless inner display the hinge has zero thickness, so leftWidth ==
	// rightStart; hinged devices report a real gap between them.
	public static final class Posture
	{
		public final int leftWidth;   // px left of the hinge
		public final int rightStart;  // px where the right half begins
		public final int totalWidth;  // full window width (both halves + hinge)
		public final Rect hinge;      // fold bounds in window coords

		Posture(Rect hinge, int totalWidth)
		{
			this.hinge = hinge;
			this.leftWidth = hinge.left;
			this.rightStart = hinge.right;
			this.totalWidth = totalWidth;
		}

		// Synthetic posture: place the centerline at fraction of totalWidth,
		// preserving the physical hinge gap (gap = rightStart - leftWidth).
		// The gap stays centered on the new centerline.
		public Posture withCenterline(float fraction)
		{
			int gap = rightStart - leftWidth;
			int center = Math.round(fraction * totalWidth);
			int newLeft = center - gap / 2;
			int newRight = newLeft + gap;
			// Clamp so neither half collapses below 1 px.
			newLeft = Math.max(1, Math.min(newLeft, totalWidth - gap - 1));
			newRight = newLeft + gap;
			Rect syntheticHinge = new Rect(newLeft, hinge.top,
					newRight, hinge.bottom);
			return new Posture(syntheticHinge, totalWidth);
		}
	}

	private final Activity activity;
	private final Listener listener;
	private final WindowInfoTrackerCallbackAdapter adapter;
	private final Executor mainExecutor;
	private final Consumer<WindowLayoutInfo> consumer;

	private boolean started = false;
	private boolean lastUnfoldedActive = false;

	public FoldStateController(Activity activity, Listener listener)
	{
		this.activity = activity;
		this.listener = listener;
		this.adapter = new WindowInfoTrackerCallbackAdapter(
				WindowInfoTracker.Companion.getOrCreate(activity));
		this.mainExecutor = ContextCompat.getMainExecutor(activity);
		this.consumer = this::handleLayoutInfo;
	}

	public void start()
	{
		if (started)
			return;
		started = true;
		adapter.addWindowLayoutInfoListener(activity, mainExecutor, consumer);
	}

	public void stop()
	{
		if (!started)
			return;
		started = false;
		adapter.removeWindowLayoutInfoListener(consumer);
	}

	// The unfolded state last reported to the listener — lets the activity decide
	// whether a posture change needs a view rebuild without re-querying.
	public boolean isUnfoldedActive()
	{
		return lastUnfoldedActive;
	}

	private void handleLayoutInfo(WindowLayoutInfo info)
	{
		FoldingFeature vfold = null;
		for (DisplayFeature f : info.getDisplayFeatures())
		{
			if (f instanceof FoldingFeature)
			{
				FoldingFeature ff = (FoldingFeature) f;
				if (ff.getOrientation() == FoldingFeature.Orientation.VERTICAL)
					vfold = ff;
			}
		}

		if (vfold != null)
			Preferences.setFoldableSeen(true);

		boolean open = vfold != null
				&& (vfold.getState() == FoldingFeature.State.FLAT
					|| vfold.getState() == FoldingFeature.State.HALF_OPENED);
		boolean unfoldedActive = open && Preferences.getUnfoldedEnabled();

		int windowWidth = activity.getResources()
				.getDisplayMetrics().widthPixels;
		Posture posture = (open && vfold.getBounds().width() >= 0
				&& vfold.getBounds().left > 0 && windowWidth > 0)
				? new Posture(vfold.getBounds(), windowWidth) : null;
		// A vertical fold with a degenerate/edge hinge can't be split usefully.
		if (posture == null)
			unfoldedActive = false;

		lastUnfoldedActive = unfoldedActive;
		listener.onFoldStateChanged(unfoldedActive, posture);
	}
}
