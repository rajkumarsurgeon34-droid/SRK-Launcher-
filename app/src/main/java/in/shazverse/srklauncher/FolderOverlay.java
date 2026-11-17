package in.shazverse.srklauncher;

import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** An opened App Library folder showing all of its apps. */
final class FolderOverlay extends FrameLayout {

    FolderOverlay(final LauncherActivity a, final AppStore.Folder f) {
        super(a);
        setBackgroundColor(0x80000000);
        setClickable(true);
        setOnClickListener(v -> a.closeOverlay());

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = Ui.text(a, f.name, 28, Ui.WHITE, Ui.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, Ui.dp(18));
        col.addView(title);

        final int cellH = a.iconSize + Ui.dp(36);
        int rowsNeeded = (f.apps.size() + 3) / 4;
        int panelW = a.lastW - Ui.dp(40);
        int panelH = Math.min(rowsNeeded * (cellH + Ui.dp(8)) + Ui.dp(36), (int) (a.lastH * 0.62f));

        GridView grid = new GridView(a);
        grid.setNumColumns(4);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(8));
        grid.setSelector(new ColorDrawable(0));
        grid.setBackground(Ui.round(0x47FFFFFF, 36));
        grid.setPadding(Ui.dp(12), Ui.dp(18), Ui.dp(12), Ui.dp(18));
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return f.apps.size();
            }

            @Override
            public Object getItem(int i) {
                return f.apps.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                AppIconView v = new AppIconView(a, f.apps.get(i), a.iconSize, true, a);
                v.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellH));
                return v;
            }
        });
        col.addView(grid, new LinearLayout.LayoutParams(panelW, panelH));
        addView(col, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }
}
