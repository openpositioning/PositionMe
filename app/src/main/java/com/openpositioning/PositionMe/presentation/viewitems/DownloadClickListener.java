package com.openpositioning.PositionMe.presentation.viewitems;

/**
 * Interface to enable listening for clicks in RecyclerViews.
 *
 * @author Yueyan Zhao
 * @author Zizhen Wang
 * @author Chen Zhao
 */
public interface DownloadClickListener {

    /**
     * Function executed when the item is clicked.
     *
     * @param position  integer position of the item in the list.
     */
    void onPositionClicked(int position);

}
