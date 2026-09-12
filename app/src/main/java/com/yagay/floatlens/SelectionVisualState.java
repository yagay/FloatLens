package com.yagay.floatlens;

/** Shared FV drag-selection visual state. Business selection state is owned by ViewSelectionEngine. */
enum SelectionVisualState {
    TRACKING,
    READY;

    boolean isReady() { return this == READY; }
}
