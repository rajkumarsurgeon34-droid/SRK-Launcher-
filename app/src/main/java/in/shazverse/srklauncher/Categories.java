package in.shazverse.srklauncher;

import android.content.pm.ApplicationInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Sorts apps into App Library folders, like iOS does automatically. */
final class Categories {
    private Categories() {}

    static final int SOCIAL = 0, ENTERTAINMENT = 1, CREATIVITY = 2, PRODUCTIVITY = 3, FINANCE = 4,
            UTILITIES = 5, SHOPPING = 6, TRAVEL = 7, GAMES = 8, READING = 9, EDUCATION = 10,
            HEALTH = 11, OTHER = 12;

    static final String[] NAMES = {
            "Social", "Entertainment", "Creativity", "Productivity", "Finance",
            "Utilities", "Shopping & Food", "Travel", "Games", "Information & Reading",
            "Education", "Health & Fitness", "Other"
    };

    /** Order of folders in the App Library. */
    static final int[] ORDER = {
            SOCIAL, PRODUCTIVITY, ENTERTAINMENT, EDUCATION, FINANCE, SHOPPING,
            CREATIVITY, TRAVEL, READING, HEALTH, GAMES, UTILITIES, OTHER
    };

    /** Order in which keyword lists are checked (more specific first). */
    private static final int[] CHECK = {
            ENTERTAINMENT, FINANCE, EDUCATION, HEALTH, SOCIAL, SHOPPING, TRAVEL,
            PRODUCTIVITY, CREATIVITY, READING, GAMES, UTILITIES
    };

    private static final String[][] WORDS = new String[13][];

    static {
        WORDS[SOCIAL] = new String[]{"whatsapp", "telegram", "instagram", "facebook", "messenger", "snapchat",
                "twitter", "threads", "linkedin", "discord", "signal", "sharechat", "reddit", "pinterest",
                "truecaller", "dialer", "contacts", "messag", "phone", "mms", "sms", "chat", "moj", "koo"};
        WORDS[ENTERTAINMENT] = new String[]{"youtube", "netflix", "hotstar", "primevideo", "prime video", "avod",
                "jiocinema", "sonyliv", "zee5", "spotify", "music", "gaana", "wynk", "saavn", "mxplayer",
                "vlc", "podcast", "audible", "tv", "video player", "aha", "voot", "shorts"};
        WORDS[FINANCE] = new String[]{"bank", "pay", "upi", "phonepe", "paytm", "gpay", "paisa", "zerodha",
                "kite", "groww", "angel", "upstox", "finance", "wallet", "money", "credit", "sbi", "yono",
                "hdfc", "icici", "axis", "kotak", "cred", "bhim", "mutual", "stock", "trading", "invest"};
        WORDS[EDUCATION] = new String[]{"learn", "byju", "unacademy", "duolingo", "edu", "class", "neet",
                "study", "academy", "marrow", "prepladder", "dams", "school", "coursera", "udemy", "khan",
                "exam", "quiz", "tutor", "teacher", "hindi", "dictionary", "physics wallah", "lecture"};
        WORDS[HEALTH] = new String[]{"health", "fit", "fitness", "practo", "1mg", "pharmeasy", "apollo",
                "hospital", "doctor", "medic", "pharma", "workout", "yoga", "step", "diet", "cure", "rx"};
        WORDS[SHOPPING] = new String[]{"amazon", "flipkart", "myntra", "meesho", "ajio", "swiggy", "zomato",
                "blinkit", "zepto", "bigbasket", "shop", "nykaa", "dunzo", "dominos", "food", "grocer",
                "instamart", "jiomart", "tatacliq", "snapdeal", "ikea", "mart"};
        WORDS[TRAVEL] = new String[]{"maps", "uber", "ola", "rapido", "irctc", "makemytrip", "goibibo",
                "redbus", "cleartrip", "travel", "airbnb", "booking", "indigo", "airline", "metro", "rail",
                "navigation", "waze", "trip", "flight", "hotel", "yatra"};
        WORDS[PRODUCTIVITY] = new String[]{"gmail", "mail", "outlook", "docs", "sheets", "slides", "office",
                "word", "excel", "powerpoint", "drive", "calendar", "notes", "keep", "notion", "teams", "zoom",
                "meet", "slack", "pdf", "scanner", "todo", "tasks", "srk", "onenote", "dropbox", "evernote",
                "authenticator", "jira", "trello", "workspace", "rx clinic"};
        WORDS[CREATIVITY] = new String[]{"camera", "gallery", "photos", "photo", "snapseed", "lightroom",
                "picsart", "canva", "capcut", "inshot", "editor", "studio", "design", "draw", "paint",
                "sketch", "filter", "collage", "lens"};
        WORDS[READING] = new String[]{"news", "inshorts", "kindle", "reader", "book", "dailyhunt", "quora",
                "wikipedia", "medium", "magazine", "times", "hindu", "comics", "manga", "vaani"};
        WORDS[GAMES] = new String[]{"game", "games", "ludo", "pubg", "bgmi", "freefire", "free fire",
                "candy", "chess", "puzzle", "cricket", "racing", "clash", "minecraft", "roblox", "subway"};
        WORDS[UTILITIES] = new String[]{"settings", "clock", "calculator", "calc", "files", "file",
                "filemanager", "browser", "chrome", "firefox", "opera", "brave", "edge", "vpn", "torch",
                "flashlight", "weather", "recorder", "compass", "security", "cleaner", "backup", "vending",
                "store", "launcher", "download", "manager", "tool", "tools", "wifi", "bluetooth", "share",
                "remote", "assistant", "google", "translate", "measure", "magnifier"};
        WORDS[OTHER] = new String[0];
    }

    static int categorize(ApplicationInfo ai, String pkg, String label) {
        if (ai != null && ai.category == ApplicationInfo.CATEGORY_GAME) return GAMES;
        String hay = (pkg + " " + label).toLowerCase(Locale.ROOT);
        Set<String> tokens = new HashSet<>(Arrays.asList(hay.split("[^a-z0-9]+")));
        for (int cat : CHECK) {
            for (String w : WORDS[cat]) {
                boolean hit = (w.length() <= 4 && w.indexOf(' ') < 0) ? tokens.contains(w) : hay.contains(w);
                if (hit) return cat;
            }
        }
        if (ai != null) {
            switch (ai.category) {
                case ApplicationInfo.CATEGORY_AUDIO:
                case ApplicationInfo.CATEGORY_VIDEO:
                    return ENTERTAINMENT;
                case ApplicationInfo.CATEGORY_IMAGE:
                    return CREATIVITY;
                case ApplicationInfo.CATEGORY_SOCIAL:
                    return SOCIAL;
                case ApplicationInfo.CATEGORY_NEWS:
                    return READING;
                case ApplicationInfo.CATEGORY_MAPS:
                    return TRAVEL;
                case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                    return PRODUCTIVITY;
                default:
                    break;
            }
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return UTILITIES;
        }
        return OTHER;
    }
}
