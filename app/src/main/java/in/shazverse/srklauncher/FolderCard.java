package in.shazverse.srklauncher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** An App Library folder: three big icons plus a mini-grid that opens the full folder. */
final class FolderCard extends View {
    private final LauncherActivity act;
    private final AppStore.Folder folder;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), cell = new RectF(), sub = new RectF();
    private int downCell = -1;

    FolderCard(LauncherActivity a, AppStore.Folder f) {
        super(a);
        act = a;
        folder = f;
        bg.setColor(0x38FFFFFF);
        dim.setColor(0x40000000);
        setClickable(true);
        setContentDescription(f.name);
    }

    private boolean miniGrid() {
        return folder.apps.size() > 4;
    }

    private void cellRect(int i, RectF out) {
        float w = getWidth();
        float pad = w * 0.1f, gap = w * 0.08f;
        float cs = (w - pad * 2 - gap) / 2f;
        int col = i % 2, row = i / 2;
        float l = pad + col * (cs + gap), t = pad + row * (cs + gap);
        out.set(l, t, l + cs, t + cs);
    }

    private int cellAt(float x, float y) {
        for (int i = 0; i < 4; i++) {
            cellRect(i, cell);
            if (cell.contains(x, y)) return i;
        }
        return -1;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        r.set(0, 0, w, getHeight());
        c.drawRoundRect(r, w * 0.2f, w * 0.2f, bg);
        int n = folder.apps.size();
        for (int i = 0; i < 4; i++) {
            cellRect(i, cell);
            if (i < 3 || !miniGrid()) {
                if (i < n && folder.apps.get(i).icon != null) {
                    c.drawBitmap(folder.apps.get(i).icon, null, cell, bmp);
                    if (isPressed() && downCell == i) c.drawRoundRect(cell, cell.width() * 0.22f, cell.width() * 0.22f, dim);
                }
            } else {
                float g = cell.width() * 0.1f, m = (cell.width() - g) / 2f;
                for (int j = 0; j < 4; j++) {
                    int idx = 3 + j;
                    if (idx >= n) break;
                    float l = cell.left + (j % 2) * (m + g), t = cell.top + (j / 2) * (m + g);
                    sub.set(l, t, l + m, t + m);
                    if (folder.apps.get(idx).icon != null) c.drawBitmap(folder.apps.get(idx).icon, null, sub, bmp);
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downCell = cellAt(e.getX(), e.getY());
                setPressed(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                int c = cellAt(e.getX(), e.getY());
                if (c == downCell) {
                    int n = folder.apps.size();
                    if (c >= 0 && (c < 3 || !miniGrid())) {
                        if (c < n) act.launch(folder.apps.get(c), this);
                    } else {
                        act.showFolder(folder);
                    }
                }
                downCell = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                downCell = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
