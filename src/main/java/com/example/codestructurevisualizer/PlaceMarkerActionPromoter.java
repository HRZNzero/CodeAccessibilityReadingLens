package com.example.codestructurevisualizer;

import com.intellij.openapi.actionSystem.ActionPromoter;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DataContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Promotes {@link PlaceMarkerAction} to the front of the action list whenever
 * {@code Ctrl+Alt+M} is dispatched inside an editor.
 *
 * <p>Without this, IntelliJ's built-in {@code ExtractMethod} action (which also
 * owns {@code Ctrl+Alt+M} in the default keymap) would win the conflict because
 * built-in actions are resolved before plugin actions.  The {@code ActionPromoter}
 * extension point is the officially-supported way to resolve such conflicts.</p>
 *
 * <p>A secondary binding of {@code Alt+M} is also registered as a guaranteed
 * fallback — {@code Alt+M} has no built-in platform binding so it always fires.</p>
 */
public final class PlaceMarkerActionPromoter implements ActionPromoter {

    @Override
    public List<AnAction> promote(@NotNull List<? extends AnAction> actions,
                                  @NotNull DataContext context) {
        boolean hasPlaceMarker = false;
        for (AnAction action : actions) {
            if (action instanceof PlaceMarkerAction) { hasPlaceMarker = true; break; }
        }
        if (!hasPlaceMarker) return null;        // nothing to promote

        List<AnAction> result = new ArrayList<>(actions.size());
        // PlaceMarker first
        for (AnAction action : actions) {
            if (action instanceof PlaceMarkerAction) result.add(action);
        }
        // then everything else
        for (AnAction action : actions) {
            if (!(action instanceof PlaceMarkerAction)) result.add(action);
        }
        return result;
    }
}



