package in.shazverse.srklauncher;

import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;

import java.util.List;

/** An opened folder: App Library folders and Home Screen folders (renamable, iOS 3-column grid). */
final class FolderOverlay extends FrameLayout implements Motion.OverlayAnim {
    private final LauncherActivity act;
    private final LinearLayout col;
    private final View origin;
    private final String homeFolderId;
    private EditText title;

    /** App Library folder. */
    FolderOverlay(LauncherActivity a, AppStore.Folder f, View origin) {
        this(a, f.name, f.apps, null, origin, 4);
    }

    /** Home Screen folder. */
    FolderOverlay(LauncherActivity a, String folderId, View origin) {
        this(a, a.store.folders.get(folderId).name, a.store.folderApps(folderId), folderId, origin, 3);
    }

    private FolderOverlay(final LauncherActivity a, String name, final List<AppInfo> apps,
                          final String folderId, View origin, int columns) {
        super(a);
        act = a;
        this.origin = origin;
        this.homeFolderId = folderId;
        setBackgroundColor(0x59000000);
        setClickable(true);
        setOnClickListener(v -> a.closeOverlay());

        col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        title = new EditText(a);
        title.setText(name);
        title.setTextColor(Ui.WHITE);
        title.setTypeface(Ui.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setGravity(Gravity.CENTER);
        title.setBackground(null);
        title.setSingleLine(true);
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        title.setImeOptions(EditorInfo.IME_ACTION_DONE);
        title.setShadowLayer(Ui.dp(4), 0, Ui.dp(1), 0x66000000);
        if (folderId == null) {
            title.setFocusable(false);
            title.setCursorVisible(false);
        } else {
            title.setOnEditorActionListener((v, id, ev) -> {
                a.hideKeyboard(title);
                title.clearFocus();
                return true;
            });
        }
        title.setPadding(0, 0, 0, Ui.dp(14));
        col.addView(title, new LinearLayout.LayoutParams(a.lastW - Ui.dp(60), ViewGroup.LayoutParams.WRAP_CONTENT));

        final int cellH = a.iconSize + Ui.dp(38);
        int rowsNeeded = Math.max(1, (apps.size() + columns - 1) / columns);
        int panelW = columns == 3 ? Math.min(a.lastW - Ui.dp(64), (a.iconSize + Ui.dp(40)) * 3 + Ui.dp(40)) : a.lastW - Ui.dp(40);
        int panelH = Math.min(rowsNeeded * (cellH + Ui.dp(6)) + Ui.dp(40), (int) (a.lastH * 0.6f));

        GridView grid = new GridView(a);
        grid.setNumColumns(columns);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(6));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setBackground(Ui.round(0x40FFFFFF, 38));
        grid.setPadding(Ui.dp(14), Ui.dp(20), Ui.dp(14), Ui.dp(20));
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int i) {
                return apps.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                AppIconView v = new AppIconView(a, apps.get(i), a.iconSize, true, a);
                if (folderId != null) {
                    v.place = AppIconView.IN_FOLDER;
                    v.ownerFolder = folderId;
                }
                v.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellH));
                return v;
            }
        });
        col.addView(grid, new LinearLayout.LayoutParams(panelW, panelH));
        addView(col, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /** Saves a renamed Home Screen folder. */
    void commitName() {
        if (homeFolderId == null || title == null) return;
        String n = title.getText().toString();
        AppStore.HomeFolder f = act.store.folders.get(homeFolderId);
        if (f != null && !n.trim().equals(f.name)) {
            act.store.renameFolder(homeFolderId, n);
            act.requestRebuild();
        }
    }

    private void pivotToOrigin() {
        if (origin == null || col.getWidth() == 0) return;
        int[] o = new int[2], c = new int[2];
        origin.getLocationOnScreen(o);
        col.getLocationOnScreen(c);
        col.setPivotX(o[0] - c[0] + origin.getWidth() / 2f);
        col.setPivotY(o[1] - c[1] + origin.getHeight() / 2f);
    }

    @Override
    public void enter() {
        setAlpha(0f);
        animate().alpha(1f).setDuration(200).start();
        col.setScaleX(0.25f);
        col.setScaleY(0.25f);
        col.setAlpha(0f);
        col.post(() -> {
            pivotToOrigin();
            col.animate().scaleX(1f).scaleY(1f).alpha(1f).setInterpolator(Motion.SPRING).setDuration(520).start();
        });
    }

    @Override
    public void exit(Runnable done) {
        commitName();
        pivotToOrigin();
        col.animate().scaleX(0.25f).scaleY(0.25f).alpha(0f).setInterpolator(Motion.EASE_IN).setDuration(220).start();
        animate().alpha(0f).setDuration(240).withEndAction(done).start();
    }
}
