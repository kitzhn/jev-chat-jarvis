package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.kb.BackupDeleteResult
import com.jev.probe.core.kb.LocalBackupInfo
import com.jev.probe.core.kb.LocalBackupStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException

class LocalBackupActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val backupStore by lazy { LocalBackupStore(this) }

    private val accent = Color.rgb(58, 122, 254)
    private val ink = Color.rgb(17, 24, 39)
    private val sub = Color.rgb(107, 114, 128)
    private val danger = Color.rgb(185, 28, 28)

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var createButton: TextView
    private lateinit var deleteAllButton: TextView
    private var busy = false
    private var backupCount = 0

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.rgb(242, 243, 245))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()
        scroll.addView(root)
        root.addView(text("本地加密备份", 24f, ink, true).apply { setPadding(0, 0, 0, dp(10)) })
        root.addView(text(
            "备份仅包含笔记、联系人、关系和聊天历史，不包含 API 密钥或接口配置。备份使用 Android Keystore 的 AES-GCM 加密，保存在本机应用私有目录；不能导出或迁移到另一台设备。删除全部备份时会同时清理解密密钥；卸载或清除应用数据会删除备份文件。不要把本地备份当作跨设备或重装恢复方案。",
            13f, sub
        ).apply { setPadding(0, 0, 0, dp(8)) })

        createButton = button("立即创建备份", accent, Color.WHITE) {
            runAction("正在加密并保存备份…") {
                val info = backupStore.createBackup()
                "备份已创建：${formatDate(info.createdAt)}（${formatSize(info.sizeBytes)}）"
            }
        }
        root.addView(createButton)

        deleteAllButton = button("删除全部备份", Color.WHITE, danger, outlined = true) {
            confirmDeleteAll()
        }
        root.addView(deleteAllButton)

        status = text("正在读取备份…", 12.5f, sub).apply {
            setPadding(dp(2), dp(12), dp(2), dp(8))
        }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(scroll)
        refresh()
    }

    private fun refresh() {
        if (busy) return
        status.text = "正在读取备份…"
        worker.execute {
            val result = runCatching { backupStore.listBackups() }
            main.post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess { renderBackups(it) }
                    .onFailure {
                        list.removeAllViews()
                        status.text = "读取失败：${friendlyError(it)}"
                        updateControls(0)
                    }
            }
        }
    }

    private fun renderBackups(backups: List<LocalBackupInfo>) {
        list.removeAllViews()
        backupCount = backups.size
        updateControls(backups.size)
        status.text = if (backups.isEmpty()) "暂无备份" else "共 ${backups.size} 份备份"
        if (backups.isEmpty()) {
            list.addView(text("创建备份后会显示在这里。删除知识库与历史不会自动删除备份。", 12.5f, sub).apply {
                setPadding(dp(4), dp(8), dp(4), dp(12))
            })
            return
        }
        backups.forEach { backup ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(dp(14), Color.WHITE)
                setPadding(dp(14), dp(12), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            card.addView(text(formatDate(backup.createdAt), 15f, ink, true))
            card.addView(text("${formatSize(backup.sizeBytes)} · 本机 AES-GCM 加密", 12f, sub).apply {
                setPadding(0, dp(4), 0, dp(2))
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val restore = button("恢复", Color.WHITE, accent, outlined = true) { confirmRestore(backup) }
            val delete = button("删除", Color.WHITE, danger, outlined = true) {
                confirmDeleteOne(backup)
            }
            restore.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) }
            delete.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(6) }
            actions.addView(restore)
            actions.addView(delete)
            card.addView(actions)
            list.addView(card)
        }
    }

    private fun updateControls(count: Int) {
        createButton.isEnabled = !busy
        deleteAllButton.isEnabled = !busy && count > 0
        deleteAllButton.alpha = if (deleteAllButton.isEnabled) 1f else 0.48f
        deleteAllButton.text = if (count > 0) "删除全部备份（$count）" else "删除全部备份"
    }

    private fun runAction(progress: String, action: () -> String) {
        if (busy) return
        busy = true
        createButton.isEnabled = false
        deleteAllButton.isEnabled = false
        status.text = progress
        worker.execute {
            val message = try {
                action()
            } catch (error: Exception) {
                "操作失败：${friendlyError(error)}"
            }
            val backups = runCatching { backupStore.listBackups() }
            main.post {
                if (isFinishing || isDestroyed) return@post
                busy = false
                status.text = message
                backups.onSuccess { renderBackups(it) }
                    .onFailure { updateControls(0) }
                // Keep the operation result visible after rendering the updated count.
                if (backups.isSuccess) status.text = message
            }
        }
    }

    private fun confirmRestore(backup: LocalBackupInfo) {
        AlertDialog.Builder(this)
            .setTitle("恢复这份备份？")
            .setMessage(
                "恢复会完整替换当前笔记、联系人、关系和聊天历史。API 密钥与接口配置不受影响。\n\n" +
                    "建议先备份当前数据；你可以先自动创建一份当前备份，再恢复所选版本。"
            )
            .setPositiveButton("先备份当前数据") { _, _ ->
                runAction("正在先备份当前知识库，再恢复所选版本…") {
                    backupStore.createBackup()
                    backupStore.restoreBackup(backup.id)
                    "已先备份当前数据，并恢复了 ${formatDate(backup.createdAt)} 的版本"
                }
            }
            .setNeutralButton("直接恢复") { _, _ ->
                runAction("正在校验并恢复备份…") {
                    backupStore.restoreBackup(backup.id)
                    "已恢复 ${formatDate(backup.createdAt)} 的知识库"
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteOne(backup: LocalBackupInfo) {
        AlertDialog.Builder(this)
            .setTitle("删除这份备份？")
            .setMessage("删除后无法再从这份备份恢复。当前正在使用的知识库不会改变。")
            .setPositiveButton("删除") { _, _ ->
                runAction("正在删除备份…") {
                    val result = backupStore.deleteBackup(backup.id)
                    deletionMessage(result, "已删除这份备份")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("删除全部备份？")
            .setMessage("将删除本机保存的全部 $backupCount 份加密备份。当前正在使用的知识库不会改变；删除后无法再恢复这些备份。")
            .setPositiveButton("全部删除") { _, _ ->
                runAction("正在删除全部备份…") {
                    val result = backupStore.deleteAllBackups()
                    deletionMessage(result, "已删除全部备份")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deletionMessage(result: BackupDeleteResult, base: String): String {
        if (result.encryptionKeyDeleted) return "$base，并已清除本机解密密钥"
        return if (backupStore.listBackups().isEmpty()) {
            "$base；备份文件已删除，但本机解密密钥清理失败"
        } else {
            "$base；为保留其它备份的可恢复性，继续保留共用解密密钥"
        }
    }

    private fun friendlyError(error: Throwable): String {
        var cause: Throwable? = error
        repeat(6) {
            if (cause is AEADBadTagException) return "备份校验未通过，文件可能损坏或密钥已失效"
            cause = cause?.cause
        }
        return error.message?.take(120)?.ifBlank { error.javaClass.simpleName }
            ?: error.javaClass.simpleName
    }

    private fun formatDate(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))

    private fun formatSize(size: Long): String = when {
        size < 1024L -> "$size B"
        size < 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f KB", size / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(
        label: String,
        backgroundColor: Int,
        textColor: Int,
        outlined: Boolean = false,
        onClick: () -> Unit
    ) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor)
        background = round(dp(10), backgroundColor, if (outlined) textColor else null)
        setPadding(dp(14), dp(11), dp(14), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(color)
        stroke?.let { setStroke(dp(1), it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }
}
