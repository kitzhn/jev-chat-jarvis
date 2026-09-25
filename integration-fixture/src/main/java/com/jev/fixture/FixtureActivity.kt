package com.jev.fixture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat

class FixtureActivity : AppCompatActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (BuildConfig.FLAVOR) {
            "qq" -> showQq()
            "wechat" -> showWechat()
            "feishu" -> showFeishu()
            "twitter" -> showTwitter()
        }
    }

    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(28), dp(18), dp(18))
        setBackgroundColor(Color.parseColor("#F4F6F8"))
    }

    private fun showQq() {
        val root = root()
        root.addView(title("演示旅行群（3）"))
        root.addView(sender("A同学"))
        root.addView(bubble("周末有空一起吃饭吗？", mine = false, id = R.id.mjn))
        root.addView(sender("B同学"))
        root.addView(bubble("我也可以呀", mine = false, id = R.id.mjn))
        root.addView(bubble("那我周六晚上有空", mine = true, id = R.id.mjn))
        root.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        })
        root.addView(EditText(this).apply {
            id = R.id.input
            hint = "输入消息"
            textSize = 16f
            isSingleLine = true
            background = rounded(Color.WHITE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        })
        setContentView(root)
    }

    private fun showWechat() {
        val root = root()
        root.addView(title("演示微信群（3）"))
        root.addView(bubble("周末有空一起吃饭吗？", mine = false, id = R.id.bkl))
        root.addView(bubble("有空呀", mine = true, id = R.id.bkl))
        root.addView(bubble("那周六晚上见？", mine = false, id = R.id.bkl))
        root.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        })
        root.addView(EditText(this).apply {
            hint = "发消息"
            textSize = 16f
            isSingleLine = true
            background = rounded(Color.WHITE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        })
        setContentView(root)
        root.postDelayed({ postWechatNotification() }, 900)
    }

    private fun showFeishu() {
        val root = root()
        root.addView(title("演示飞书群（3）").apply { id = R.id.group_name })
        root.addView(feishuBubble("周末有空一起吃饭吗？", mine = false))
        root.addView(feishuBubble("我可以，周六见。", mine = true))
        root.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        })
        root.addView(EditText(this).apply {
            hint = "发送消息"
            textSize = 16f
            isSingleLine = true
            background = rounded(Color.WHITE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        })
        setContentView(root)
    }

    private fun feishuBubble(text: String, mine: Boolean) = FrameLayout(this).apply {
        id = R.id.bubble_content_container
        background = rounded(if (mine) Color.parseColor("#D5F5CA") else Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = dp(5)
            bottomMargin = dp(8)
            if (mine) leftMargin = dp(100) else rightMargin = dp(100)
        }
        addView(TextView(this@FixtureActivity).apply {
            id = R.id.kb_rich_text_content
            this.text = text
            textSize = 16f
            setTextColor(Color.parseColor("#111827"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        })
        if (mine) addView(TextView(this@FixtureActivity).apply {
            id = R.id.time_read_state_container_align_bubble
            this.text = "已读"
            textSize = 8f
            setTextColor(Color.DKGRAY)
        })
    }

    private fun showTwitter() {
        val root = root().apply { setPadding(0, 0, 0, 0) }
        root.addView(title("演示 X 私信").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
            ).apply { topMargin = dp(24) }
        })
        root.addView(xMessage("阿杰：今天有空一起吃饭吗？。8:11 上午。Read。"))
        root.addView(xMessage("你：好呀，周六见。8:12 上午。Read。"))
        root.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        })
        root.addView(EditText(this).apply {
            hint = "发送私信"
            textSize = 16f
            isSingleLine = true
            background = rounded(Color.WHITE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        })
        setContentView(root)
    }

    private fun xMessage(description: String) = View(this).apply {
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        )
    }

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTextColor(Color.parseColor("#111827"))
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(22))
    }

    private fun sender(t: String) = TextView(this).apply {
        id = R.id.mjq
        text = t
        textSize = 11f
        setTextColor(Color.parseColor("#64748B"))
        setPadding(dp(6), dp(8), 0, dp(3))
    }

    private fun bubble(t: String, mine: Boolean, id: Int) = TextView(this).apply {
        this.id = id
        text = t
        textSize = 16f
        setTextColor(Color.parseColor("#111827"))
        background = rounded(if (mine) Color.parseColor("#95EC69") else Color.WHITE)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = dp(5)
            bottomMargin = dp(8)
            if (mine) leftMargin = dp(120) else rightMargin = dp(120)
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(13).toFloat()
        setColor(color)
    }

    private fun postWechatNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = "chat"
        nm.createNotificationChannel(
            NotificationChannel(channel, "Chat", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("演示微信群（3）")
            .setContentText("A同学：那周六晚上见？")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) nm.notify(1001, n)
    }
}
