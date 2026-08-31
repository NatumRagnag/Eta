package io.github.mangi.eta.ui.screens.enhance
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import io.github.mangi.eta.ui.model.AgentSystemEnhanceUiState
import io.github.mangi.eta.ui.model.SystemEnhanceItemUi
import io.github.mangi.eta.ui.model.SystemEnhanceStatusUi
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SystemEnhanceScreen(
    state: AgentSystemEnhanceUiState,
    onAction: (AgentSystemEnhanceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_system_enhancement_dcd4ad),
        onBack = { onAction(AgentSystemEnhanceAction.NavigateBack) },
        modifier = modifier,
    ) {
        items(
            items = state.sections,
            key = { it.id },
        ) { section ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                SmallTitle(
                    text = section.title,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                section.items.forEach { item ->
                    SystemEnhanceItemRow(
                        item = item,
                        onToggle = { onAction(AgentSystemEnhanceAction.ToggleItem(item.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemEnhanceItemRow(
    item: SystemEnhanceItemUi,
    onToggle: () -> Unit,
) {
    BasicComponent(
        title = item.title,
        summary = item.summary,
        endActions = {
            Text(
                text = when (item.status) {
                    SystemEnhanceStatusUi.Active -> stringResource(R.string.status_enabled)
                    SystemEnhanceStatusUi.Inactive -> stringResource(R.string.permission_status_disabled)
                    SystemEnhanceStatusUi.Unsupported -> stringResource(R.string.status_unsupported)
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
        onClick = onToggle,
    )
}
