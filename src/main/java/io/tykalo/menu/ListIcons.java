package io.tykalo.menu;

import io.tykalo.list.ListType;

/** The type icon shown for a list across the menu screens (My Lists, list view). */
final class ListIcons {

    private ListIcons() {
    }

    static String of(final ListType type) {
        return switch (type) {
            case CHECKLIST -> "🛒";
            case PROJECT -> "📋";
            case ROUTINE -> "🔄";
            case INBOX -> "📥";
        };
    }
}
