package local.jiege.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** A Miuix dialog with two buttons: [dismissText] closes it, [confirmText] (primary) runs [onConfirm]. */
@Composable
internal fun OverlayConfirmDialog(
    title: String,
    summary: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "取消",
) {
    OverlayDialog(show = true, title = title, summary = summary, onDismissRequest = onDismiss) {
        DialogButtons(dismissText, onDismiss, confirmText, onConfirm)
    }
}

/** The button row of a dialog: [dismissText] on the left, the primary [confirmText] on the right. */
@Composable
internal fun DialogButtons(dismissText: String, onDismiss: () -> Unit, confirmText: String, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(text = dismissText, onClick = onDismiss, modifier = Modifier.weight(1f))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.weight(1f),
        )
    }
}
