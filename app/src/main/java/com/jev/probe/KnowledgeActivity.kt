package com.jev.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.kb.AffectionScale
import com.jev.probe.core.kb.Contact
import com.jev.probe.core.kb.ContactRelation
import com.jev.probe.core.kb.KbStore
import com.jev.probe.core.kb.Note
import com.jev.probe.core.kb.PlatformIdentity
import com.jev.probe.core.kb.RelationshipEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Knowledge base manager: the notes the assistant may quote, and the contacts
 * that tie a conversation title (across apps) to a person.
 *
 * Everything shown here lives in the app-private `filesDir/kb` directory and
 * nowhere else. Plain code-built views, same card/pill vocabulary as
 * [SettingsActivity].
 */
class KnowledgeActivity : AppCompatActivity() {

    private lateinit var store: KbStore
    private lateinit var container: LinearLayout

    /** 0 = notes, 1 = contacts, 2 = relationship graph. */
    private var tab = 0
    private var pendingLinkApp: String = ""
    private var pendingLinkTitle: String = ""

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val pillOff = Color.parseColor("#EEF1F5")
    private val red = Color.parseColor("#DC2626")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = KbStore.get(this)
        pendingLinkApp = intent.getStringExtra(EXTRA_LINK_APP).orEmpty()
        pendingLinkTitle = intent.getStringExtra(EXTRA_LINK_TITLE).orEmpty()
        if (pendingLinkTitle.isNotBlank()) tab = 1
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        container.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(container)
        setContentView(scroll)
        render()
    }

    // --------------------------------------------------------------- screens

    private fun render() {
        container.removeAllViews()
        container.addView(text("知识库与联系人", 24f, ink, bold = true))
        container.addView(text("只存在本机，不上传。联系人可绑定多个 App 身份；分析时优先按“平台 + 会话标题”精确匹配。",
            12f, sub).apply { setPadding(0, dp(6), 0, dp(4)) })
        container.addView(tabs())
        when (tab) {
            0 -> renderNotes()
            1 -> renderContacts()
            else -> renderRelations()
        }
    }

    private fun tabs(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        listOf("笔记", "联系人", "关系网").forEachIndexed { i, name ->
            val pill = TextView(this).apply {
                text = name; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                setTextColor(if (i == tab) Color.WHITE else sub)
                setTypeface(typeface, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
                background = round(dp(9), if (i == tab) accent else pillOff)
                setOnClickListener { tab = i; render() }
            }
            row.addView(pill)
        }
        return row
    }

    // ----------------------------------------------------------------- notes

    private fun renderNotes() {
        val notes = store.notes().sortedByDescending { it.updatedAt }
        container.addView(twoButtons("新建笔记", { editNoteDialog(null) },
            "从文本导入", { importNotesDialog() }))
        if (notes.isEmpty()) {
            container.addView(emptyCard("还没有笔记。写点该记住的事实：习惯、忌口、项目代号、约定过的时间。"))
            return
        }
        notes.forEach { container.addView(noteRow(it)) }
        container.addView(text("点条目编辑，长按删除。命中规则：任一标签或标题出现在会话标题或最近 6 条消息里。",
            11f, sub).apply { setPadding(dp(2), dp(12), 0, 0) })
    }

    private fun noteRow(n: Note): View {
        val c = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val head = n.title.ifBlank { "（无标题）" } + if (n.alwaysOn) "  · 常驻" else ""
        left.addView(text(head, 15f, ink, bold = true))
        left.addView(text(
            if (n.tags.isEmpty()) "无标签" else "标签：" + n.tags.joinToString("、"),
            12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        left.addView(text(n.content.replace("\n", " ").take(46), 12f, sub)
            .apply { setPadding(0, dp(3), 0, 0) })
        row.addView(left)
        row.addView(smallToggle(n.enabled) {
            store.saveNote(n.copy(enabled = !n.enabled)); render()
        })
        c.addView(row)
        c.setOnClickListener { editNoteDialog(n) }
        c.setOnLongClickListener {
            confirm("删除笔记", "删除「${n.title}」？不可恢复。") {
                store.deleteNote(n.id); render()
            }
            true
        }
        return c
    }

    private fun editNoteDialog(existing: Note?) {
        val box = dialogBox()
        val titleEdit = edit(existing?.title ?: "", "标题，例如：口味忌口")
        val contentEdit = edit(existing?.content ?: "", "正文，写清楚事实本身").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; gravity = Gravity.TOP
        }
        val tagsEdit = edit(existing?.tags?.joinToString("，") ?: "", "逗号分隔，例如：吃饭，周末")
        val alwaysRow = toggleRow("常驻（每次分析都带上）", existing?.alwaysOn ?: false)
        val enabledRow = toggleRow("启用", existing?.enabled ?: true)
        box.addView(label("标题")); box.addView(titleEdit)
        box.addView(label("正文")); box.addView(contentEdit)
        box.addView(label("标签")); box.addView(tagsEdit)
        box.addView(alwaysRow); box.addView(enabledRow)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建笔记" else "编辑笔记")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val title = titleEdit.text.toString().trim()
                val content = contentEdit.text.toString().trim()
                if (title.isBlank() && content.isBlank()) {
                    toast("标题和正文不能都空着"); return@setPositiveButton
                }
                store.saveNote(Note(
                    id = existing?.id ?: KbStore.newId(),
                    title = title,
                    content = content,
                    tags = splitTags(tagsEdit.text.toString()),
                    alwaysOn = (alwaysRow.tag as? Boolean) ?: false,
                    enabled = (enabledRow.tag as? Boolean) ?: true
                ))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importNotesDialog() {
        val box = dialogBox()
        box.addView(text("按空行分段，每段第一行当标题，其余当正文。", 12f, sub))
        val input = edit("", "口味忌口\n不吃香菜，海鲜过敏\n\n项目代号\n内部叫小蓝").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8; gravity = Gravity.TOP
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("从文本导入")
            .setView(wrapScroll(box))
            .setPositiveButton("导入") { _, _ ->
                val chunks = input.text.toString().split(Regex("\\r?\\n[ \\t]*\\r?\\n"))
                var n = 0
                chunks.forEach { chunk ->
                    val lines = chunk.trim().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) {
                        store.saveNote(Note(
                            id = KbStore.newId(),
                            title = lines.first(),
                            content = lines.drop(1).joinToString("\n")
                        ))
                        n++
                    }
                }
                toast(if (n == 0) "没解析出内容" else "已导入 $n 条")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun splitTags(raw: String): List<String> =
        raw.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }

    // -------------------------------------------------------------- contacts

    private fun renderContacts() {
        val contacts = store.contacts().sortedByDescending { it.updatedAt }
        container.addView(twoButtons("新建联系人", { editContactDialog(null) }, null, null))
        if (pendingLinkTitle.isNotBlank()) container.addView(linkBanner())
        container.addView(affectionLegend())
        if (contacts.isEmpty()) {
            container.addView(emptyCard(
                "还没有联系人。可以先新建联系人；不同 App 的会话随后都能关联到同一个人。"))
            return
        }
        contacts.forEach { container.addView(contactRow(it)) }
        container.addView(text(
            "优先按平台身份精确匹配；好感度只由用户手动维护。点卡片编辑画像，长按删除。",
            11f, sub).apply { setPadding(dp(2), dp(12), 0, 0) })
    }

    private fun linkBanner(): View = card().apply {
        addView(text("关联当前会话", 14f, ink, bold = true))
        addView(text(
            "${appLabel(pendingLinkApp)} · $pendingLinkTitle",
            13f, accent, bold = true).apply { setPadding(0, dp(5), 0, 0) })
        addView(text(
            "在下面选择现实中的同一个联系人。绑定后，不同 App 的聊天会共用同一份画像、历史和关系数据。",
            11.5f, sub).apply { setPadding(0, dp(5), 0, 0) })
    }

    private fun affectionLegend(): View = card().apply {
        addView(text("好感度量级", 14f, ink, bold = true))
        addView(text(
            AffectionScale.tiers.joinToString("  ·  ") { "${it.min}-${it.max} ${it.label}" },
            11.5f, sub).apply { setPadding(0, dp(6), 0, 0) })
        addView(text(
            "这是用户自定义的关系记录工具，不会自动推断对方真实感受。",
            11f, sub).apply { setPadding(0, dp(5), 0, 0) })
    }

    private fun contactRow(c0: Contact): View {
        val c = card()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(avatarView(c0.name))
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, dp(8), 0)
        }
        headerText.addView(text(c0.name.ifBlank { "（无名）" }, 16f, ink, bold = true))
        val relationshipLine = listOf(c0.relationship, c0.relationshipStage)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
        if (relationshipLine.isNotEmpty()) {
            headerText.addView(text(relationshipLine.take(60), 11.5f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        }
        header.addView(headerText)
        header.addView(stageBadge(c0))
        c.addView(header)

        c.addView(text(
            "好感 ${c0.affection}/100 · ${AffectionScale.label(c0.affection)}",
            13f, accent, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) })
        c.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = AffectionScale.clamp(c0.affection)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7))
        })
        c.addView(text("信任 ${c0.trust}/100  ·  亲密 ${c0.closeness}/100", 12f, sub)
            .apply { setPadding(0, dp(5), 0, 0) })

        if (c0.identities.isNotEmpty()) {
            c.addView(text(
                "平台：" + c0.identities.joinToString("  ·  ") {
                    "${identityLabel(it.app)}: ${it.title}"
                }.take(120),
                12f, sub).apply { setPadding(0, dp(5), 0, 0) })
        } else if (c0.apps.isNotEmpty()) {
            c.addView(text("平台：" + c0.apps.joinToString("、") { appLabel(it) }, 12f, sub)
                .apply { setPadding(0, dp(5), 0, 0) })
        }

        val lastTs = store.lastInteractionAt(c0.id)
        c.addView(text(
            "最近互动：" + if (lastTs > 0L) formatTime(lastTs) else "暂无记录",
            11.5f, sub).apply { setPadding(0, dp(4), 0, 0) })
        val edgeCount = store.relationsFor(c0.id).size
        if (edgeCount > 0) {
            c.addView(text("关系网：与 $edgeCount 个联系人建立了关系边", 11.5f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        }

        if (c0.profileTags.isNotEmpty())
            c.addView(text("画像：" + c0.profileTags.joinToString("、").take(90), 12f, sub)
                .apply { setPadding(0, dp(4), 0, 0) })
        if (c0.traits.isNotBlank())
            c.addView(text("特征：" + c0.traits.replace("\n", " ").take(90), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })
        if (c0.communicationStyle.isNotBlank())
            c.addView(text("沟通：" + c0.communicationStyle.replace("\n", " ").take(90), 12f, sub)
                .apply { setPadding(0, dp(3), 0, 0) })

        val recentEvents = store.relationshipEvents(c0.id, 2)
        if (recentEvents.isNotEmpty()) {
            c.addView(text("最近好感变化", 11.5f, ink, bold = true)
                .apply { setPadding(0, dp(9), 0, dp(2)) })
            recentEvents.asReversed().forEach { e ->
                c.addView(text(eventLine(e), 11f, sub)
                    .apply { setPadding(0, dp(2), 0, 0) })
            }
        }

        if (pendingLinkTitle.isNotBlank()) {
            c.addView(actionButton("关联当前会话到这个联系人") {
                val msg = store.linkCurrentIdentityToContact(
                    c0.id, pendingLinkApp, pendingLinkTitle)
                toast(msg)
                pendingLinkApp = ""
                pendingLinkTitle = ""
                render()
            })
        }

        c.addView(affectionAdjustRow(c0))

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, 0)
        }
        tools.addView(miniAction("查看好感记录") { showRelationshipHistory(c0) })
        val logN = store.logSize(c0.id)
        tools.addView(miniAction("清空历史（$logN）", danger = true) {
            if (logN == 0) { toast("本来就没有历史") }
            else confirm("清空历史", "删掉「${c0.name}」的 $logN 条聊天历史？联系人画像保留。") {
                store.clearLog(c0.id); render()
            }
        })
        c.addView(tools)

        c.setOnClickListener { editContactDialog(c0) }
        c.setOnLongClickListener {
            confirm("删除联系人", "删除「${c0.name}」及其全部历史、关系记录？不可恢复。") {
                store.deleteContact(c0.id); render()
            }
            true
        }
        return c
    }

    private fun affectionAdjustRow(contact: Contact): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
        }
        row.addView(text("好感快捷调整", 11.5f, sub).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        listOf(-5, 5).forEach { delta ->
            row.addView(TextView(this).apply {
                text = if (delta > 0) "+$delta" else "$delta"
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
                background = round(dp(9), Color.WHITE, stroke = true)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) }
                setOnClickListener { showAffectionChangeDialog(contact, delta) }
            })
        }
        return row
    }

    private fun showAffectionChangeDialog(contact: Contact, delta: Int) {
        val box = dialogBox()
        box.addView(text(
            "好感 ${contact.affection} → ${AffectionScale.clamp(contact.affection + delta)}",
            14f, ink, bold = true))
        val reason = edit("", "可选：为什么这次变化？例如：一次愉快的见面")
        box.addView(label("变化原因")); box.addView(reason)
        AlertDialog.Builder(this)
            .setTitle(if (delta > 0) "增加好感" else "降低好感")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                store.adjustAffection(contact.id, delta, reason.text.toString().trim(), "quick")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRelationshipHistory(contact: Contact) {
        val events = store.relationshipEvents(contact.id, 50).asReversed()
        val msg = if (events.isEmpty()) "还没有好感变化记录。"
        else events.joinToString("\n\n") { eventLine(it) }
        AlertDialog.Builder(this)
            .setTitle("「${contact.name}」好感记录")
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun eventLine(e: RelationshipEvent): String {
        val sign = if (e.delta > 0) "+${e.delta}" else e.delta.toString()
        val why = e.reason.ifBlank { "未填写原因" }
        return "${formatTime(e.ts)}  $sign  ${e.fromScore}→${e.toScore} · $why"
    }

    private fun editContactDialog(existing: Contact?) {
        val box = dialogBox()
        val nameEdit = edit(existing?.name ?: "", "联系人显示名称")
        val aliasEdit = edit(existing?.aliases?.joinToString("\n") ?: "", "兼容旧数据：每行一个全局别名").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; gravity = Gravity.TOP
        }
        val identityEdit = multiEdit(
            existing?.identities?.joinToString("\n") {
                "${identityLabel(it.app)} | ${it.title}"
            } ?: "",
            "每行一个，例如：QQ | 昵称\n飞书 | 姓名\nX | @handle"
        )
        val relEdit = edit(existing?.relationship ?: "", "关系类型")
        val stageEdit = edit(existing?.relationshipStage ?: "", "当前关系阶段")
        val affectionEdit = scoreEdit(existing?.affection ?: 50)
        val trustEdit = scoreEdit(existing?.trust ?: 50)
        val closenessEdit = scoreEdit(existing?.closeness ?: 50)
        val affectionReasonEdit = edit("", "仅当好感分数变化时记录，例如：一起完成了一件重要的事")
        val tagsEdit = edit(existing?.profileTags?.joinToString("，") ?: "", "逗号分隔的画像标签")
        val traitsEdit = multiEdit(existing?.traits ?: "", "性格、长期特征或相处特点")
        val communicationEdit = multiEdit(existing?.communicationStyle ?: "", "适合的沟通方式、回复风格")
        val boundariesEdit = multiEdit(existing?.boundaries ?: "", "边界、忌讳、敏感事项")
        val notesEdit = multiEdit(existing?.notes ?: "", "其它需要记住的事实")

        box.addView(label("基本信息"))
        box.addView(label("名字")); box.addView(nameEdit)
        box.addView(label("平台身份"))
        box.addView(text(
            "推荐使用“平台 | 会话标题”。同一个现实联系人可以绑定多个 App；匹配时优先使用这组精确身份。",
            11f, sub))
        box.addView(identityEdit)
        box.addView(label("旧式全局别名")); box.addView(aliasEdit)
        box.addView(label("关系类型")); box.addView(relEdit)
        box.addView(label("关系阶段")); box.addView(stageEdit)

        box.addView(label("关系量表（0–100）"))
        box.addView(text("好感、信任、亲密均由用户维护，不自动推断对方心理。", 11f, sub))
        box.addView(label("好感度")); box.addView(affectionEdit)
        box.addView(label("本次好感变化原因")); box.addView(affectionReasonEdit)
        box.addView(label("信任度")); box.addView(trustEdit)
        box.addView(label("亲密度")); box.addView(closenessEdit)

        box.addView(label("联系人画像"))
        box.addView(label("画像标签")); box.addView(tagsEdit)
        box.addView(label("性格 / 特征")); box.addView(traitsEdit)
        box.addView(label("沟通偏好")); box.addView(communicationEdit)
        box.addView(label("边界 / 忌讳")); box.addView(boundariesEdit)
        box.addView(label("备注")); box.addView(notesEdit)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建联系人画像" else "编辑联系人画像")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isBlank()) { toast("名字不能空"); return@setPositiveButton }
                val identities = parseIdentityLines(identityEdit.text.toString())
                val updated = Contact(
                    id = existing?.id ?: KbStore.newId(),
                    name = name,
                    aliases = aliasEdit.text.toString().split("\n")
                        .map { it.trim() }.filter { it.isNotEmpty() },
                    apps = ((existing?.apps ?: emptyList()) + identities.map { it.app })
                        .filter { it.isNotBlank() }.distinct(),
                    identities = identities,
                    relationship = relEdit.text.toString().trim(),
                    relationshipStage = stageEdit.text.toString().trim(),
                    affection = readScore(affectionEdit),
                    trust = readScore(trustEdit),
                    closeness = readScore(closenessEdit),
                    profileTags = splitTags(tagsEdit.text.toString()),
                    traits = traitsEdit.text.toString().trim(),
                    communicationStyle = communicationEdit.text.toString().trim(),
                    boundaries = boundariesEdit.text.toString().trim(),
                    notes = notesEdit.text.toString().trim(),
                    autoSummary = existing?.autoSummary ?: ""
                )
                if (existing == null) store.saveContact(updated)
                else store.saveContactWithAffectionEvent(
                    updated, affectionReasonEdit.text.toString().trim().ifBlank { "画像编辑" })
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun parseIdentityLines(raw: String): List<PlatformIdentity> =
        raw.split("\n").mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val separators = listOf(" | ", "｜", "|", "：", ":")
            val sep = separators.firstOrNull { t.contains(it) }
            if (sep == null) {
                PlatformIdentity("", t, "")
            } else {
                val left = t.substringBefore(sep).trim()
                val right = t.substringAfter(sep).trim()
                if (right.isEmpty()) null else PlatformIdentity(
                    app = appPackageForLabel(left),
                    title = right,
                    label = left
                )
            }
        }.distinctBy { it.app + "\u0000" + KbStore.normalizeName(it.title) }

    private fun scoreEdit(value: Int): EditText = edit(
        AffectionScale.clamp(value).toString(), "0–100"
    ).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun readScore(v: EditText): Int =
        AffectionScale.clamp(v.text.toString().toIntOrNull() ?: 50)

    private fun multiEdit(value: String, hintText: String): EditText =
        edit(value, hintText).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
        }

    private fun appPackageForLabel(label: String): String = when (label.trim().lowercase()) {
        "qq" -> "com.tencent.mobileqq"
        "飞书", "lark" -> "com.ss.android.lark"
        "x", "twitter" -> "com.twitter.android"
        "微信", "wechat" -> "com.tencent.mm"
        "telegram", "tg" -> "org.telegram.messenger"
        "钉钉", "dingtalk" -> "com.alibaba.android.rimet"
        else -> label.trim()
    }

    private fun identityLabel(pkg: String): String = appLabel(pkg).ifBlank { "其它" }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.tencent.mobileqq" -> "QQ"
        "com.ss.android.lark" -> "飞书"
        "com.twitter.android" -> "X"
        "com.tencent.mm" -> "微信"
        "org.telegram.messenger" -> "Telegram"
        "com.alibaba.android.rimet" -> "钉钉"
        "" -> "未知 App"
        else -> pkg
    }

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    private fun avatarView(name: String): TextView = TextView(this).apply {
        text = name.trim().take(1).ifBlank { "?" }
        textSize = 18f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
        }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun stageBadge(contact: Contact): TextView = TextView(this).apply {
        text = contact.relationshipStage.ifBlank { AffectionScale.label(contact.affection) }
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
        background = round(dp(12), Color.parseColor("#EEF4FF"))
        setPadding(dp(9), dp(5), dp(9), dp(5))
    }

    private fun actionButton(labelText: String, onClick: () -> Unit): View =
        TextView(this).apply {
            text = labelText
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = round(dp(10), accent)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) }
            setOnClickListener { onClick() }
        }

    private fun miniAction(labelText: String, danger: Boolean = false, onClick: () -> Unit): View =
        TextView(this).apply {
            text = labelText
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(if (danger) red else accent)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
            setOnClickListener { onClick() }
        }

    companion object {
        const val EXTRA_LINK_APP = "link_app"
        const val EXTRA_LINK_TITLE = "link_title"
    }

    // --------------------------------------------------------- relationship graph

    private fun renderRelations() {
        val contacts = store.contacts().sortedBy { it.name.lowercase() }
        val edges = store.contactRelations().sortedByDescending { it.updatedAt }

        container.addView(twoButtons(
            "新建关系",
            {
                if (contacts.size < 2) toast("至少需要两个联系人")
                else editRelationDialog(null)
            },
            null,
            null
        ))

        container.addView(card().apply {
            addView(text("联系人关系网", 14f, ink, bold = true))
            addView(text(
                "这里记录“联系人和联系人之间”的关系，例如同学、同事、家人、通过谁认识。它和你本人对某个联系人的好感度是两套不同数据。",
                11.5f, sub).apply { setPadding(0, dp(5), 0, 0) })
        })

        if (contacts.size < 2) {
            container.addView(emptyCard("先建立至少两个联系人，才能创建关系边。"))
            return
        }
        if (edges.isEmpty()) {
            container.addView(emptyCard("还没有关系边。点击“新建关系”开始建立联系人之间的关系网络。"))
            return
        }

        edges.forEach { edge ->
            val from = contacts.firstOrNull { it.id == edge.fromId }?.name ?: "未知联系人"
            val to = contacts.firstOrNull { it.id == edge.toId }?.name ?: "未知联系人"
            container.addView(card().apply {
                addView(text("${from}  ↔  ${to}", 15f, ink, bold = true))
                addView(text(
                    edge.type.ifBlank { "未命名关系" } + " · 强度 ${edge.strength}/100",
                    12.5f, accent, bold = true).apply { setPadding(0, dp(5), 0, 0) })
                addView(ProgressBar(this@KnowledgeActivity, null,
                    android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = edge.strength.coerceIn(0, 100)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(5) }
                })
                if (edge.note.isNotBlank()) {
                    addView(text(edge.note, 12f, sub).apply { setPadding(0, dp(6), 0, 0) })
                }
                val actions = LinearLayout(this@KnowledgeActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                }
                actions.addView(miniAction("编辑") { editRelationDialog(edge) })
                actions.addView(miniAction("删除", danger = true) {
                    confirm("删除关系", "删除「${from} ↔ ${to}」这条关系边？") {
                        store.deleteContactRelation(edge.id)
                        render()
                    }
                })
                addView(actions)
            })
        }
    }

    private fun editRelationDialog(existing: ContactRelation?) {
        val contacts = store.contacts().sortedBy { it.name.lowercase() }
        if (contacts.size < 2) { toast("至少需要两个联系人"); return }

        val names = contacts.map { it.name.ifBlank { "未命名" } }
        val box = dialogBox()
        val fromSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@KnowledgeActivity,
                android.R.layout.simple_spinner_dropdown_item, names)
        }
        val toSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@KnowledgeActivity,
                android.R.layout.simple_spinner_dropdown_item, names)
        }
        existing?.let { relation ->
            fromSpinner.setSelection(contacts.indexOfFirst { it.id == relation.fromId }.coerceAtLeast(0))
            toSpinner.setSelection(contacts.indexOfFirst { it.id == relation.toId }.coerceAtLeast(0))
        }

        val typeEdit = edit(existing?.type ?: "", "例如：大学同学、同事、兄妹、通过某某认识")
        val strengthEdit = scoreEdit(existing?.strength ?: 50)
        val noteEdit = multiEdit(existing?.note ?: "", "可选：关系背景、认识渠道或需要记住的事项")

        box.addView(label("联系人 A")); box.addView(fromSpinner)
        box.addView(label("联系人 B")); box.addView(toSpinner)
        box.addView(label("关系类型")); box.addView(typeEdit)
        box.addView(label("关系强度（0–100）")); box.addView(strengthEdit)
        box.addView(label("备注")); box.addView(noteEdit)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建联系人关系" else "编辑联系人关系")
            .setView(wrapScroll(box))
            .setPositiveButton("保存") { _, _ ->
                val from = contacts.getOrNull(fromSpinner.selectedItemPosition)
                val to = contacts.getOrNull(toSpinner.selectedItemPosition)
                if (from == null || to == null || from.id == to.id) {
                    toast("请选择两个不同联系人")
                    return@setPositiveButton
                }
                val ok = store.saveContactRelation(ContactRelation(
                    id = existing?.id ?: KbStore.newId(),
                    fromId = from.id,
                    toId = to.id,
                    type = typeEdit.text.toString().trim(),
                    strength = readScore(strengthEdit),
                    note = noteEdit.text.toString().trim(),
                    updatedAt = existing?.updatedAt ?: System.currentTimeMillis()
                ))
                toast(if (ok) "关系已保存" else "关系保存失败")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ----------------------------------------------------------------- atoms

    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("确定") { _, _ -> onYes() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dialogBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }

    private fun wrapScroll(v: View) = ScrollView(this).apply { addView(v) }

    private fun twoButtons(
        a: String, onA: () -> Unit,
        b: String?, onB: (() -> Unit)?
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        row.addView(wideBtn(a, true, onA))
        if (b != null && onB != null) row.addView(wideBtn(b, false, onB))
        return row
    }

    private fun wideBtn(labelText: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = labelText; textSize = 14f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else accent)
        background = round(dp(11), if (primary) accent else Color.WHITE, stroke = !primary)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun smallToggle(on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = if (on) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (on) Color.WHITE else sub)
        background = round(dp(10), if (on) accent else Color.parseColor("#E5E7EB"))
        setPadding(dp(16), dp(6), dp(16), dp(6))
        setOnClickListener { onClick() }
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    private fun emptyCard(msg: String): View = card().apply { addView(text(msg, 13f, sub)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun edit(value: String, hintText: String) = EditText(this).apply {
        setText(value); hint = hintText; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
        if (stroke) setStroke(dp(1), accent)
    }
}
