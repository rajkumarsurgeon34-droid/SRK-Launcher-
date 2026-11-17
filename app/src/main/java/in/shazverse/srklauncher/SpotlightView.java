package in.shazverse.srklauncher;

import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

/** Swipe-down search (Spotlight): suggestions, app results and web search. */
final class SpotlightView extends LinearLayout {
    private final LauncherActivity act;
    private final EditText search;
    private final View suggestTitle, suggestRow;
    private final ListView list;
    private final AppListAdapter adapter;

    SpotlightView(LauncherActivity a) {
        super(a);
        act = a;
        setOrientation(VERTICAL);
        setBackgroundColor(0x8C000000);
        setClickable(true);
        setPadding(Ui.dp(16), a.insetTop + Ui.dp(12), Ui.dp(16), 0);
        setOnClickListener(v -> a.closeOverlay());

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        search = Ui.searchField(a, "Search");
        bar.addView(search, new LayoutParams(0, Ui.dp(44), 1));
        TextView cancel = Ui.text(a, "Cancel", 17, Ui.WHITE, Ui.REGULAR);
        cancel.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(4), Ui.dp(8));
        cancel.setOnClickListener(v -> a.closeOverlay());
        bar.addView(cancel, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView st = Ui.text(a, "Suggestions", 14, 0xCCFFFFFF, Ui.MEDIUM);
        st.setPadding(Ui.dp(4), Ui.dp(22), 0, Ui.dp(8));
        suggestTitle = st;
        addView(st);

        LinearLayout row = new LinearLayout(a);
        row.setBackground(Ui.round(0x33FFFFFF, 22));
        row.setPadding(Ui.dp(6), Ui.dp(10), Ui.dp(6), Ui.dp(6));
        row.setClickable(true);
        List<AppInfo> sug = a.store.suggestions(4);
        for (AppInfo app : sug) {
            AppIconView iv = new AppIconView(a, app, a.iconSize, true, a);
            row.addView(iv, new LayoutParams(0, a.iconSize + Ui.dp(34), 1));
        }
        for (int i = sug.size(); i < 4; i++) row.addView(new View(a), new LayoutParams(0, 1, 1));
        suggestRow = row;
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        adapter = new AppListAdapter(a);
        list = new ListView(a);
        list.setAdapter(adapter);
        list.setDivider(new ColorDrawable(0x26FFFFFF));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(0x1FFFFFFF));
        list.setVisibility(GONE);
        list.setOnItemClickListener((p, v, pos, id) -> open(pos, v));
        LayoutParams llp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        llp.topMargin = Ui.dp(10);
        addView(list, llp);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a1, int a2, int a3) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a1, int a2, int a3) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(s.toString().trim());
            }
        });
        search.setOnEditorActionListener((v, actionId, ev) -> {
            if (adapter.getCount() > 0) open(0, null);
            return true;
        });
        post(() -> {
            search.requestFocus();
            act.showKeyboard(search);
        });
    }

    private void filter(String q) {
        boolean empty = q.isEmpty();
        suggestTitle.setVisibility(empty ? VISIBLE : GONE);
        suggestRow.setVisibility(empty ? VISIBLE : GONE);
        list.setVisibility(empty ? GONE : VISIBLE);
        if (!empty) adapter.set(act.store.search(q), q);
    }

    private void open(int pos, View from) {
        Object o = adapter.getItem(pos);
        if (o instanceof AppInfo) act.launch((AppInfo) o, from);
        else if (o instanceof String) act.webSearch((String) o);
    }
}
