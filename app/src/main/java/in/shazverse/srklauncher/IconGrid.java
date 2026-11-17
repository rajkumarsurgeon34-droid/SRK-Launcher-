package in.shazverse.srklauncher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Grid of cells; children can span several columns/rows (icons, widgets, Control Centre tiles). */
public class IconGrid extends ViewGroup {
    final int cols, rows;
    /** When > 0 every row has this fixed height (used for square tiles). */
    int fixedCell = 0;

    static final class LP extends ViewGroup.LayoutParams {
        final int col, row, colSpan, rowSpan, inset;

        LP(int col, int row, int colSpan, int rowSpan, int inset) {
            super(WRAP_CONTENT, WRAP_CONTENT);
            this.col = col;
            this.row = row;
            this.colSpan = colSpan;
            this.rowSpan = rowSpan;
            this.inset = inset;
        }
    }

    public IconGrid(Context c, int cols, int rows) {
        super(c);
        this.cols = cols;
        this.rows = rows;
        setClipChildren(false);
        setClipToPadding(false);
    }

    void add(View v, int col, int row, int colSpan, int rowSpan, int inset) {
        addView(v, new LP(col, row, colSpan, rowSpan, inset));
    }

    private float cellW() {
        return (getMeasuredWidth() - getPaddingLeft() - getPaddingRight()) / (float) cols;
    }

    private float cellH() {
        return fixedCell > 0 ? fixedCell : (getMeasuredHeight() - getPaddingTop() - getPaddingBottom()) / (float) rows;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        int h;
        if (fixedCell > 0 || MeasureSpec.getMode(hs) != MeasureSpec.EXACTLY) {
            int cell = fixedCell > 0 ? fixedCell : (w - getPaddingLeft() - getPaddingRight()) / cols;
            h = getPaddingTop() + getPaddingBottom() + cell * rows;
        } else {
            h = MeasureSpec.getSize(hs);
        }
        setMeasuredDimension(w, h);
        float cw = cellW(), ch = cellH();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            LP lp = (LP) v.getLayoutParams();
            int vw = Math.max(0, Math.round(cw * lp.colSpan) - lp.inset * 2);
            int vh = Math.max(0, Math.round(ch * lp.rowSpan) - lp.inset * 2);
            v.measure(MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float cw = cellW(), ch = cellH();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            LP lp = (LP) v.getLayoutParams();
            int x = Math.round(getPaddingLeft() + lp.col * cw) + lp.inset;
            int y = Math.round(getPaddingTop() + lp.row * ch) + lp.inset;
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LP;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LP(0, 0, 1, 1, 0);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LP(0, 0, 1, 1, 0);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LP(0, 0, 1, 1, 0);
    }
}
