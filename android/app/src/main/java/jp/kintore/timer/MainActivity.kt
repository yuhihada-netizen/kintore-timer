package jp.kintore.timer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        window.statusBarColor = Color.parseColor("#111418")
        val sv = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#111418")); isFillViewport = true }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(16), px(16), px(48)) }
        sv.addView(root)
        setContentView(sv)
        render()
    }

    private fun text(s: String, size: Float = 14f, color: Int = Color.parseColor("#9aa5b1")) =
        TextView(this).apply { text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); setPadding(0, px(10), 0, px(4)) }

    private fun btn(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun edit(value: String, number: Boolean, onChange: (String) -> Unit) = EditText(this).apply {
        setText(value); setTextColor(Color.WHITE); setSingleLine()
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
        setBackgroundColor(Color.parseColor("#0e1115")); setPadding(px(10), px(8), px(10), px(8))
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onChange(s.toString()); store.save() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun labeled(label: String, v: View, weight: Float = 1f) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(px(3), 0, px(3), 0) }
        addView(TextView(context).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(Color.parseColor("#9aa5b1")) })
        addView(v)
    }

    private fun rowOf(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it) } }

    private fun render() {
        root.removeAllViews()
        root.addView(text("筋トレタイマー", 22f, Color.WHITE))

        val start = btn(if (TimerService.running) "やり直す（最初から）" else "開始") { startTimer() }
        root.addView(start)
        if (TimerService.running) root.addView(btn("停止") { startService(Intent(this, TimerService::class.java).setAction(TimerService.ACT_STOP)); render() })
        root.addView(text("開始後はホームやYouTubeを開いてください。画面の一番上にバーが出ます。バーは触れても反応せず、下のアプリをそのまま操作できます。一時停止・再開・前後への移動は、通知の操作ボタンで行います。", 12f))

        root.addView(text("メニュー"))
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        store.menus.forEachIndexed { i, m ->
            tabs.addView(btn((if (i == store.cur) "● " else "") + m.name.ifBlank { "無題" }) { store.cur = i; store.save(); render() })
        }
        root.addView(HorizontalScrollView(this).apply { addView(tabs) })
        root.addView(rowOf(
            btn("＋新規") { store.menus.add(Menu(store.newId(), "新しいメニュー", 5, 15, mutableListOf(Item("種目1", 30, 1, 0)))); store.cur = store.menus.size - 1; store.save(); render() },
            btn("複製") {
                val m = store.menu()
                store.menus.add(Menu(store.newId(), m.name + " のコピー", m.prep, m.rest, m.items.map { Item(it.name, it.sec, it.sets, it.restSet) }.toMutableList()))
                store.cur = store.menus.size - 1; store.save(); render()
            },
            btn("削除") {
                if (store.menus.size < 2) { Toast.makeText(this, "最後のメニューは削除できません", Toast.LENGTH_SHORT).show(); return@btn }
                AlertDialog.Builder(this).setMessage("「${store.menu().name}」を削除しますか？")
                    .setPositiveButton("削除") { _, _ -> store.menus.removeAt(store.cur); store.cur = 0; store.save(); render() }
                    .setNegativeButton("キャンセル", null).show()
            }
        ))

        val m = store.menu()
        root.addView(text("メニューの設定"))
        root.addView(rowOf(labeled("メニュー名", edit(m.name, false) { m.name = it }, 2f)))
        root.addView(rowOf(
            labeled("最初の準備（秒）", edit(m.prep.toString(), true) { m.prep = it.toIntOrNull() ?: 0 }),
            labeled("種目間の休憩（秒）", edit(m.rest.toString(), true) { m.rest = it.toIntOrNull() ?: 0 })
        ))

        root.addView(text("種目"))
        m.items.forEachIndexed { i, it ->
            root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#2d343d")); layoutParams = LinearLayout.LayoutParams(-1, px(1)).apply { topMargin = px(10); bottomMargin = px(6) } })
            root.addView(rowOf(
                labeled("表示名", edit(it.name, false) { v -> it.name = v }, 2f),
                labeled("秒数", edit(it.sec.toString(), true) { v -> it.sec = v.toIntOrNull() ?: 1 }),
                labeled("セット", edit(it.sets.toString(), true) { v -> it.sets = v.toIntOrNull() ?: 1 }),
                labeled("セット間休憩", edit(it.restSet.toString(), true) { v -> it.restSet = v.toIntOrNull() ?: 0 })
            ))
            root.addView(rowOf(
                btn("↑") { if (i > 0) { m.items.add(i - 1, m.items.removeAt(i)); store.save(); render() } },
                btn("↓") { if (i < m.items.size - 1) { m.items.add(i + 1, m.items.removeAt(i)); store.save(); render() } },
                btn("複製") { m.items.add(i + 1, Item(it.name, it.sec, it.sets, it.restSet)); store.save(); render() },
                btn("削除") { m.items.removeAt(i); store.save(); render() }
            ))
        }
        root.addView(btn("＋ 種目を追加") { m.items.add(Item("種目${m.items.size + 1}", 30, 1, 0)); store.save(); render() })
        val total = buildSegs(m).sumOf { it.sec }
        root.addView(text("合計時間：" + fmt(total), 13f))
    }

    private fun startTimer() {
        val m = store.menu()
        if (m.items.isEmpty()) { Toast.makeText(this, "種目を追加してください", Toast.LENGTH_SHORT).show(); return }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setMessage("画面の一番上にタイマーを表示するため、「他のアプリの上に重ねて表示」を許可してください。許可したら、戻ってもう一度「開始」を押してください。")
                .setPositiveButton("設定を開く") { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                .setNegativeButton("キャンセル", null).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        launch()
    }

    private fun launch() {
        val m = store.menu()
        startForegroundService(Intent(this, TimerService::class.java).putExtra(TimerService.EXTRA_SEGS, segsToJson(buildSegs(m))))
        render()
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        launch()  // 通知を拒否されてもタイマー自体は動かす
    }

    override fun onResume() { super.onResume(); render() }
}
