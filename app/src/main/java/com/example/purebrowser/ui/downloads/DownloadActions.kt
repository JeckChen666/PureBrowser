package com.example.purebrowser.ui.downloads

import androidx.compose.runtime.Composable
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadProtocol

internal enum class DownloadAction { CANCEL, FORGET, DELETE, RETRY, RETRY_PUBLIC }

/** Both surfaces recheck the current item and busy flag while a confirmation is open. */
@Composable
internal fun DownloadActionConfirmation(
    item: DownloadItem,
    action: DownloadAction,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    connected: Boolean = true,
) {
    val permitted = connected && !busy && when (action) {
        DownloadAction.CANCEL -> item.canCancelTask()
        DownloadAction.FORGET -> item.canForgetRecord()
        DownloadAction.DELETE -> item.canDeleteSavedFile()
        DownloadAction.RETRY -> item.retryAvailable()
        DownloadAction.RETRY_PUBLIC -> item.retryAvailable() && item.useAccessContext
    }
    LocalFileConfirmation(
        title = when (action) {
            DownloadAction.CANCEL -> "取消下载？"
            DownloadAction.FORGET -> "移除记录，保留文件？"
            DownloadAction.DELETE -> "删除设备上的文件？"
            DownloadAction.RETRY, DownloadAction.RETRY_PUBLIC -> "创建新的下载任务？"
        },
        displayName = item.displayName,
        explanation = when (action) {
            DownloadAction.CANCEL -> "取消此下载任务，并清理它的临时文件；已传输的数据不会保留用于续传。保留已取消记录，之后可单独移除。不会取消其他任务。"
            DownloadAction.FORGET -> "只从本应用的下载管理和视频库移除这条记录。不会删除设备文件，也不会取消系统任务；如文件仍存在，可在系统文件管理器中查找。本应用不会自动重新导入它。"
            DownloadAction.DELETE -> "这会实际删除设备上此任务保存的文件，不只是隐藏列表记录。删除成功后才移除本应用的相关记录；删除失败或未获授权会保留记录。此操作无法撤销。"
            DownloadAction.RETRY, DownloadAction.RETRY_PUBLIC -> "重新下载会创建新的受控任务，不是暂停后续传。旧任务记录和已有文件会保留，并关联到新记录。原链接可能过期或需要登录；不会补算签名；不沿用保存的凭据，建议先返回来源网页重新发现资源。" +
                (if (action == DownloadAction.RETRY_PUBLIC) " 此次不使用网站会话或来源条件；仅适合公开资源。" else " 适用会话会重新读取。") +
                if (item.protocol == DownloadProtocol.HLS) " HLS 会重新解析原来所选档位；档位消失或不再支持时会明确失败，请返回来源重新选择，不会自动更换画质。" else ""
        },
        confirmLabel = when (action) {
            DownloadAction.CANCEL -> "取消下载并清理临时文件"
            DownloadAction.FORGET -> "仅移除记录"
            DownloadAction.DELETE -> "删除文件"
            DownloadAction.RETRY, DownloadAction.RETRY_PUBLIC -> "创建新任务"
        },
        tag = "download-${action.name.lowercase()}-dialog-${item.id}",
        enabled = permitted,
        onDismiss = onDismiss,
        onConfirm = { if (permitted) onConfirm() },
    )
}
