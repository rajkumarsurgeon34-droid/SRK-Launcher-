package in.shazverse.srklauncher;

import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The last page: App Library with auto-sorted folders and an A–Z search list. */
final class AppLibraryPage extends FrameLayout {
    private final LauncherActivity act;
    private final EditText search;
    private final TextView cancel;
    private final ScrollView folderScroll;
    private final LinearLayout folderCol;
    private final ListView list;
    private final AppListAdapter adapter;

    AppLibraryPage(LauncherActivity a) {
        super(a);
        act = a;
        int side = Ui.dp(20);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(side, a.insetTop + Ui.dp(10), side, 0);
        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        search = Ui.searchField(a, "App Library");
        search.setBackground(new Glass.GlassDrawable(search, Ui.dp(12), 0x2EFFFFFF, Ui.FILL));
        a.registerGlass(search);
        bar.addView(search, new LinearLayout.LayoutParams(0, Ui.dp(44), 1));
        cancel = Ui.text(a, "Cancel", 17, Ui.WHITE, Ui.REGULAR);
        cancel.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(2), Ui.dp(8));
        cancel.setVisibility(GONE);
        cancel.setOnClickListener(v -> clearSearch());
        bar.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(a);
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        folderScroll = new ScrollView(a);
        folderScroll.setVerticalScrollBarEnabled(false);
        folderScroll.setClipToPadding(false);
        folderCol = new LinearLayout(a);
        folderCol.setOrientation(LinearLayout.VERTICAL);
        folderCol.setPadding(0, Ui.dp(18), 0, a.insetBottom + Ui.dp(24));
        folderScroll.addView(folderCol);
        body.addView(folderScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        adapter = new AppListAdapter(a);
        list = new ListView(a);
        list.setAdapter(adapter);
        list.setDivider(new ColorDrawable(0x26FFFFFF));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(0x1FFFFFFF));
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(8), 0, a.insetBottom + Ui.dp(16));
        list.setVisibility(GONE);
        list.setFastScrollEnabled(true);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object o = adapter.getItem(pos);
            if (o instanceof AppInfo) act.launch((AppInfo) o, v);
            else if (o instanceof String) act.webSearch((String) o);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Object o = adapter.getItem(pos);
            if (o instanceof AppInfo) {
                act.showAppMenu((AppInfo) o, v);
                return true;
            }
            return false;
        });
        body.addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        search.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                cancel.setVisibility(VISIBLE);
                filter();
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a1, int a2, int a3) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a1, int a2, int a3) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (search.hasFocus()) filter();
            }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            if (adapter.getCount() > 0) {
                Object o = adapter.getItem(0);
                if (o instanceof AppInfo) act.launch((AppInfo) o, null);
                else if (o instanceof String) act.webSearch((String) o);
            }
            return true;
        });
        buildFolders();
    }

    private void filter() {
        String q = search.getText().toString().trim();
        if (q.isEmpty()) adapter.set(new ArrayList<>(act.store.apps.values()), null);
        else adapter.set(act.store.search(q), q);
        list.setVisibility(VISIBLE);
        folderScroll.setVisibility(GONE);
    }

    void clearSearch() {
        search.setText("");
        search.clearFocus();
        act.hideKeyboard(search);
        cancel.setVisibility(GONE);
        list.setVisibility(GONE);
        folderScroll.setVisibility(VISIBLE);
    }

    void clearSearchIfActive() {
        if (search.hasFocus() || search.length() > 0 || list.getVisibility() == VISIBLE) clearSearch();
    }

    private void buildFolders() {
        folderCol.removeAllViews();
        int gap = Ui.dp(20);
        int width = act.lastW - Ui.dp(20) * 2;
        int card = (width - gap) / 2;
        List<AppStore.Folder> fs = act.store.libraryFolders();
        for (int i = 0; i < fs.size(); i += 2) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 2; k++) {
                LinearLayout cell = new LinearLayout(getContext());
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                if (i + k < fs.size()) {
                    AppStore.Folder f = fs.get(i + k);
                    cell.addView(new FolderCard(act, f), new LinearLayout.LayoutParams(card, card));
                    TextView t = Ui.text(getContext(), f.name, 13, Ui.WHITE, Ui.MEDIUM);
                    t.setGravity(Gravity.CENTER);
                    t.setSingleLine(true);
                    t.setPadding(0, Ui.dp(6), 0, 0);
                    t.setShadowLayer(Ui.dp(2), 0, Ui.dp(1), 0x99000000);
                    cell.addView(t, new LinearLayout.LayoutParams(card, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(card, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (k == 0) lp.rightMargin = gap;
                row.addView(cell, lp);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.bottomMargin = Ui.dp(16);
            folderCol.addView(row, rl);
        }
        View spacer = new View(getContext());
        folderCol.addView(spacer, new LinearLayout.LayoutParams(1, Ui.dp(8)));
    }
}
