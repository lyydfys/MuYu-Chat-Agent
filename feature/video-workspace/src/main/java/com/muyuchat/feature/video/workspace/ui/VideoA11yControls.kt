package com.muyuchat.feature.video.workspace.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription

/**
 * Wraps one functional control so the *clickable container itself* carries the spoken name.
 *
 * Material3 buttons keep their label in a child `Text`, so the clickable node the screen reader
 * focuses has neither text nor contentDescription; the name lived one level too deep. This wrapper
 * clears the subtree semantics and declares name / role / state / action on the container, while
 * the wrapped control still owns the real touch, ripple and press feedback. It adds no size,
 * padding or colour: the `Box` measures exactly to the control, so layout and visuals are frozen.
 *
 * When [enabled] is false the container keeps its name and a spoken disabled reason but exposes
 * no click action, so a screen reader cannot invoke an action the visual state forbids.
 */
@Composable
internal fun A11yLabeledControl(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    enabled: Boolean = true,
    clickActionLabel: String = contentDescription,
    role: Role = Role.Button,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.clearAndSetSemantics {
            this.contentDescription = contentDescription
            this.role = role
            if (stateDescription != null) this.stateDescription = stateDescription
            if (enabled) {
                this.onClick(label = clickActionLabel) {
                    onClick()
                    true
                }
            }
        },
    ) {
        content()
    }
}
