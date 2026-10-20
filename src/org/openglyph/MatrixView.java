package org.openglyph;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import org.openglyph.core.MatrixLayout;

/**
 * The Glyph Matrix on screen. Shows a fixed grid, a drawable (SDK toy previews), or an
 * animated {@link Source} that is polled while the view is attached and its window visible
 * (a stopped Settings screen keeps its views attached), redrawing only when the picture changed.
 */
public final class MatrixView extends View {
    /** Fills {@code grid}; returns ms until the next frame, or -1 for a still picture. */
    interface Source {
        long frame(int[] grid);
    }

    private final int[] mGrid = new int[MatrixLayout.GRID_SIZE];
    private final int[] mNext = new int[MatrixLayout.GRID_SIZE];
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mBounds = new Rect();
    private Source mSource;
    private Drawable mDrawable;

    public MatrixView(Context context) {
        this(context, null);
    }

    public MatrixView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setGrid(int[] grid) {
        mSource = null;
        mDrawable = null;
        removeCallbacks(mTick);
        if (grid == null) java.util.Arrays.fill(mGrid, 0);
        else System.arraycopy(grid, 0, mGrid, 0, mGrid.length);
        invalidate();
    }

    void setSource(Source source) {
        mSource = source;
        mDrawable = null;
        removeCallbacks(mTick);
        if (polling()) mTick.run();
    }

    private boolean polling() {
        return isAttachedToWindow() && getWindowVisibility() == VISIBLE;
    }

    void setDrawable(Drawable d) {
        mSource = null;
        removeCallbacks(mTick);
        mDrawable = d == null ? null : new MatrixArt.RoundDrawable(d, 0);
        invalidate();
    }

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            Source s = mSource;
            if (s == null) return;
            long next = s.frame(mNext);
            if (!java.util.Arrays.equals(mNext, mGrid)) {
                System.arraycopy(mNext, 0, mGrid, 0, mGrid.length);
                invalidate();
            }
            if (next >= 0 && polling()) postDelayed(this, Math.max(33, next));
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mSource != null) mTick.run();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(mTick);
        if (mSource != null && polling()) mTick.run();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(mTick);
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // Square: as wide as allowed, height follows.
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? w : MeasureSpec.getSize(heightSpec);
        int s = Math.min(w, h);
        setMeasuredDimension(s, s);
    }

    @Override protected void onDraw(Canvas canvas) {
        int s = Math.min(getWidth(), getHeight());
        mBounds.set((getWidth() - s) / 2, (getHeight() - s) / 2,
                (getWidth() + s) / 2, (getHeight() + s) / 2);
        if (mDrawable != null) {
            mDrawable.setBounds(mBounds);
            mDrawable.draw(canvas);
        } else {
            MatrixArt.draw(canvas, mBounds, mGrid, mPaint);
        }
    }
}
