package io.github.mangi.eta.ui.screens.permissions
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import io.github.mangi.eta.ui.components.ArrowItem
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PrefDivider
import io.github.mangi.eta.ui.components.color
import io.github.mangi.eta.ui.components.label
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_permission_health_3048bb),
        onBack = { onAction(PermissionHealthAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "title") {
            SmallTitle(stringResource(R.string.ui_permissions_and_status_35f368))
        }
        item(key = "card") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                state.items.forEachIndexed { index, item ->
                    PermissionItemRow(
                        item = item,
                        onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                    )
                    if (index < state.items.lastIndex) {
                        PrefDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionHealthItemUi,
    onActionClick: () -> Unit,
) {
    val icon = when (item.id) {
        "accessibility" -> LucideR.drawable.lucide_ic_accessibility
        "overlay" -> LucideR.drawable.lucide_ic_layers
        "model" -> LucideR.drawable.lucide_ic_cpu
        "terminal" -> LucideR.drawable.lucide_ic_square_terminal
        "notification" -> LucideR.drawable.lucide_ic_bell
        "root" -> LucideR.drawable.lucide_ic_key
        "shizuku" -> LucideR.drawable.lucide_ic_cpu
        "xposed" -> LucideR.drawable.lucide_ic_git_branch
        "background" -> LucideR.drawable.lucide_ic_history
        "app_list" -> LucideR.drawable.lucide_ic_layout_grid
        "location" -> LucideR.drawable.lucide_ic_map_pin
        else -> LucideR.drawable.lucide_ic_shield
    }

    ArrowItem(
        title = item.title,
        summary = item.summary.takeIf { it.isNotBlank() },
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(36.dp)
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
        endActions = {
            Text(
                text = item.status.label(),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = item.status.color(),
            )
        },
        onClick = onActionClick,
    )
}
