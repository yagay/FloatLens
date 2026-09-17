package com.yagay.floatlens;

/**
 * Presentation boundary for the text action menu.
 *
 * Result/Dialog and external-overlay hosts can use different window implementations while sharing
 * the same immutable SelectionSnapshot contract.
 */
interface TextActionMenuPresenter {
    void show(SelectionSnapshot snapshot, Runnable selectAll);
    void dismiss();
}
