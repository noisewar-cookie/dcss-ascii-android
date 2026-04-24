package com.crawlmb.view;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

public class RegionRouter implements TerminalRenderer
{
	// 0-based region boundaries for the 80x24 inline layout
	public static final int HUD_START_ROW = 0;
	public static final int HUD_END_ROW = 17;
	public static final int HUD_START_COL = 37;
	public static final int HUD_END_COL = 80;

	public static final int MAP_START_ROW = 0;
	public static final int MAP_END_ROW = 17;
	public static final int MAP_START_COL = 0;
	public static final int MAP_END_COL = 37;

	public static final int MSG_START_ROW = 17;
	public static final int MSG_END_ROW = 24;
	public static final int MSG_START_COL = 0;
	public static final int MSG_END_COL = 80;

	private final List<RegionTermView> splitRegions = new ArrayList<>();
	private RegionTermView fullView;
	private LinearLayout splitContainer;
	private volatile boolean splitMode = false;
	private final Context context;

	public RegionRouter(Context context)
	{
		this.context = context;
	}

	public void addRegion(RegionTermView region)
	{
		splitRegions.add(region);
	}

	public void setFullView(RegionTermView view)
	{
		this.fullView = view;
	}

	public void setSplitContainer(LinearLayout container)
	{
		this.splitContainer = container;
	}

	private void applyFullMode()
	{
		splitMode = false;
		if (fullView != null)
			fullView.setVisibility(View.VISIBLE);
		if (splitContainer != null)
			splitContainer.setVisibility(View.INVISIBLE);
	}

	private void applySplitMode()
	{
		splitMode = true;
		if (fullView != null)
			fullView.setVisibility(View.GONE);
		if (splitContainer != null)
			splitContainer.setVisibility(View.VISIBLE);
	}

	@Override
	public boolean onGameStart()
	{
		// Reset to full mode for menu screens on each game start/restart
		if (fullView != null)
		{
			fullView.post(() -> applyFullMode());
		}

		boolean allOk = true;
		if (fullView != null && !fullView.onGameStart())
			allOk = false;
		for (RegionTermView region : splitRegions)
		{
			if (!region.onGameStart())
				allOk = false;
		}
		return allOk;
	}

	@Override
	public void drawPoint(int r, int c, char ch, int fcolor, int bcolor, boolean extendedErase)
	{
		// Always draw to all views so content is ready when visibility switches
		if (fullView != null)
			fullView.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);
		for (RegionTermView region : splitRegions)
			region.drawPoint(r, c, ch, fcolor, bcolor, extendedErase);

		// Detect gameplay: "Health:" label starts with 'H' at row 2, col 37
		if (!splitMode && r == 2 && c == 37 && ch == 'H' && fullView != null)
		{
			fullView.post(() -> applySplitMode());
		}
	}

	@Override
	public void postInvalidate()
	{
		if (fullView != null)
			fullView.postInvalidate();
		for (RegionTermView region : splitRegions)
			region.postInvalidate();
	}

	@Override
	public void increaseFontSize()
	{
		if (fullView != null)
			fullView.increaseFontSize();
		for (RegionTermView region : splitRegions)
			region.increaseFontSize();
	}

	@Override
	public void decreaseFontSize()
	{
		if (fullView != null)
			fullView.decreaseFontSize();
		for (RegionTermView region : splitRegions)
			region.decreaseFontSize();
	}

	@Override
	public Context getContext()
	{
		return context;
	}

	@Override
	public Resources getResources()
	{
		return context.getResources();
	}
}
