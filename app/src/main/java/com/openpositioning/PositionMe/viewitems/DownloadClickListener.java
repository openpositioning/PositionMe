package com.openpositioning.PositionMe.viewitems;

/**
 * Interface to enable listening for clicks in RecyclerViews.
 *
 * @author Mate Stodulka
 */
public interface DownloadClickListener {

    /**
     * Function executed when the item is clicked.
     *
     * @param position  integer position of the item in the list.
     */
    void onPositionClicked(int position);

}
