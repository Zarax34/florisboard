/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import dev.patrickgold.compose.tooltip.PlainTooltip
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.keyboard3.interaction.InteractionKind
import dev.patrickgold.florisboard.ime.keyboard3.interaction.LocalInteractionController
import dev.patrickgold.florisboard.ime.keyboard3.ui.Icon3
import dev.patrickgold.florisboard.ime.keyboard3.ui.rememberDerivedEnabledState
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggText

enum class QuickActionBarType {
    INTERACTIVE_BUTTON,
    INTERACTIVE_TILE,
    EDITOR_TILE;
}

@Composable
fun QuickActionButton(
    action: QuickAction,
    modifier: Modifier = Modifier,
    type: QuickActionBarType = QuickActionBarType.INTERACTIVE_BUTTON,
) {
    val imeController = LocalImeController.current
    val imeState by imeController.activeState.collectAsState()
    val interactionController = LocalInteractionController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val descriptor  = remember(action) {
        if (action is QuickAction.InsertK3Descriptor) {
            action.descriptor
        } else {
            ImeActions.NoopSpacer
        }
    }
    val derivedEnabledState by rememberDerivedEnabledState(descriptor)
    val isEnabled = type == QuickActionBarType.EDITOR_TILE || derivedEnabledState
    val elementName = when (type) {
        QuickActionBarType.INTERACTIVE_BUTTON -> FlorisImeUi.SmartbarActionKey
        QuickActionBarType.INTERACTIVE_TILE -> FlorisImeUi.SmartbarActionTile
        QuickActionBarType.EDITOR_TILE -> FlorisImeUi.SmartbarActionsEditorTile
    }.elementName
    val attributes: SnyggQueryAttributes = remember(descriptor) {
        mapOf(
            FlorisImeUi.Attr.Output to descriptor.toString(),
        )
    }
    val selector = when {
        isPressed -> SnyggSelector.PRESSED
        !isEnabled -> SnyggSelector.DISABLED
        else -> null
    }

    PlainTooltip(action.computeTooltip(imeState), enabled = type == QuickActionBarType.INTERACTIVE_BUTTON) {
        SnyggBox(
            elementName = elementName,
            attributes = attributes,
            selector = selector,
            modifier = modifier,
            clickAndSemanticsModifier = Modifier
                .aspectRatio(1f)
                .indication(interactionSource, LocalIndication.current)
                .pointerInput(action, isEnabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        if (isEnabled && type != QuickActionBarType.EDITOR_TILE) {
                            val press = PressInteraction.Press(down.position)
                            interactionController.performFeedback(InteractionKind.KeyPress)
                            interactionSource.tryEmit(press)
                            // action.onPointerDown(context)
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                interactionSource.tryEmit(PressInteraction.Release(press))
                                // action.onPointerUp(context)
                                imeController.updateStateBlocking {
                                    emitDescriptor(descriptor)
                                }
                            } else {
                                interactionSource.tryEmit(PressInteraction.Cancel(press))
                                // action.onPointerCancel(context)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Render foreground
                when (action) {
                    is QuickAction.InsertK3Descriptor -> {
                        SnyggBox(
                            elementName = "$elementName-icon",
                            attributes = attributes,
                            selector = selector,
                        ) {
                            val icon = action.computeIcon(imeState)
                            if (icon != null) {
                                Icon3(icon)
                            }
                        }
                    }
                    is QuickAction.InsertKey -> {}
                    is QuickAction.InsertText -> {}
                }

                // Render additional info if this is a tile
                if (type != QuickActionBarType.INTERACTIVE_BUTTON) {
                    SnyggText(
                        elementName = "$elementName-text",
                        attributes = attributes,
                        selector = selector,
                        text = action.computeDisplayName(imeState),
                    )
                }
            }
        }
    }
}
