package local.jiege.hook.filters;

import java.util.ArrayList;
import java.util.List;

/** Built-in filters of the normal scope, in display order. */
final class NormalFilterDefaults {
    private NormalFilterDefaults() {}

    static List<FilterEntry> entries() {
        List<FilterEntry> list = new ArrayList<>();
        X10Filters.add(list);
        return list;
    }
}
