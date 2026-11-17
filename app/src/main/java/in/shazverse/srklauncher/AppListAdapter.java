package in.shazverse.srklauncher;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A–Z / search results list used by Spotlight and App Library. */
final class AppListAdapter extends BaseAdapter {
    private final Context ctx;
    private List<AppInfo> items = new ArrayList<>();
    private String web;

    AppListAdapter(Context c) {
        ctx = c;
    }

    void set(List<AppInfo> list, String webQuery) {
        items = list;
        web = webQuery;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size() + (web != null ? 1 : 0);
    }

    @Override
    public Object getItem(int i) {
        return i < items.size() ? items.get(i) : web;
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    private static final class Holder {
        ImageView img;
        TextView label;
    }

    @Override
    public View getView(int i, View convert, ViewGroup parent) {
        Holder h;
        if (convert == null) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
            row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(58)));
            h = new Holder();
            h.img = new ImageView(ctx);
            h.img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(h.img, new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
            h.label = Ui.text(ctx, "", 17, Ui.WHITE, Ui.REGULAR);
            h.label.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = Ui.dp(14);
            row.addView(h.label, lp);
            row.setTag(h);
            convert = row;
        } else {
            h = (Holder) convert.getTag();
        }
        if (i < items.size()) {
            AppInfo a = items.get(i);
            h.img.setImageBitmap(a.icon);
            h.label.setText(a.label);
        } else {
            h.img.setImageDrawable(Ui.icon(ctx, R.drawable.ic_search, Ui.WHITE));
            h.label.setText("Search web for “" + web + "”");
        }
        return convert;
    }
}
