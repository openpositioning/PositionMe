package com.openpositioning.PositionMe.presentation.display;

import android.view.View;
import android.widget.ImageButton;

import androidx.annotation.NonNull;

import com.openpositioning.PositionMe.R;

/**
 * Controls collapse/expand state of the map control list panel.
 */
public class MapControlPanelController {

    private final View panelContent;
    private final ImageButton toggleButton;
    private boolean collapsed;

    // Connects the panel views and applies the initial state.
    public MapControlPanelController(@NonNull View panelContent, @NonNull ImageButton toggleButton) {
        this.panelContent = panelContent;
        this.toggleButton = toggleButton;
        this.collapsed = false;
        applyState();
        this.toggleButton.setOnClickListener(v -> toggle());
    }

    // Switches the panel between collapsed and expanded.
    public void toggle() {
        collapsed = !collapsed;
        applyState();
    }

    // Sets the panel state directly from outside this class.
    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        applyState();
    }

    // Updates the panel visibility and button icon together.
    private void applyState() {
        panelContent.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        toggleButton.setImageResource(
                collapsed ? android.R.drawable.arrow_down_float : android.R.drawable.arrow_up_float
        );
        toggleButton.setContentDescription(toggleButton.getContext().getString(
                collapsed ? R.string.map_controls_expand : R.string.map_controls_collapse
        ));
    }
}
