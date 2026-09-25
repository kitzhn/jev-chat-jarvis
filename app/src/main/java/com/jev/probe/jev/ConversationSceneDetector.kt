package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.ConversationScene

/**
 * Cheap local scene detector. It deliberately uses transparent keyword rules
 * instead of another network call, so scene adaptation adds no latency or cost.
 */
object ConversationSceneDetector {

    fun detect(snapshot: ChatSnapshot): ConversationScene {
        val text = snapshot.messages.takeLast(8)
            .joinToString("\n") { it.text.lowercase() }

        if (text.isBlank()) return ConversationScene.UNKNOWN

        val scores = linkedMapOf(
            ConversationScene.CASUAL to score(text, listOf(
                "哈哈", "笑死", "哈哈哈", "在干嘛", "吃什么", "好玩", "闲聊", "最近怎么样", "hhh"
            )),
            ConversationScene.COMFORT to score(text, listOf(
                "难受", "不开心", "委屈", "累死", "好累", "崩溃", "哭", "压力", "安慰", "陪陪"
            )),
            ConversationScene.CONFLICT to score(text, listOf(
                "生气", "吵", "别说了", "失望", "为什么你", "你总是", "烦", "冷静", "矛盾", "误会"
            )),
            ConversationScene.WORK to score(text, listOf(
                "工作", "项目", "会议", "文件", "报告", "截止", "老师", "导师", "同事", "任务", "进度"
            )),
            ConversationScene.MEETUP to score(text, listOf(
                "见面", "吃饭", "周末", "几点", "哪里见", "约", "有空", "出门", "一起去", "地址"
            )),
            ConversationScene.APOLOGY to score(text, listOf(
                "对不起", "抱歉", "不好意思", "我的问题", "我错了", "道歉", "原谅"
            ))
        )

        val best = scores.maxByOrNull { it.value } ?: return ConversationScene.UNKNOWN
        return if (best.value <= 0) ConversationScene.CASUAL else best.key
    }

    private fun score(text: String, terms: List<String>): Int =
        terms.sumOf { term -> if (text.contains(term)) 1 else 0 }
}
