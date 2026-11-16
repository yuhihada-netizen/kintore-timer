package jp.kintore.timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Item(var name: String, var sec: Int, var sets: Int, var restSet: Int)
class Menu(var id: String, var name: String, var prep: Int, var rest: Int, val items: MutableList<Item>)
class Seg(val work: Boolean, val label: String, val sub: String, val sec: Int)

fun fmt(s: Int): String = if (s >= 60) "${s / 60}分${(s % 60).toString().padStart(2, '0')}秒" else "${s}秒"

fun buildSegs(m: Menu): List<Seg> {
    val out = ArrayList<Seg>()
    if (m.prep > 0) out.add(Seg(false, "準備", "", m.prep))
    m.items.forEachIndexed { i, it ->
        val sets = maxOf(1, it.sets)
        val sec = maxOf(1, it.sec)
        for (s in 1..sets) {
            out.add(Seg(true, it.name.ifBlank { "種目" }, if (sets > 1) "$s/$sets" else "", sec))
            if (s < sets && it.restSet > 0) out.add(Seg(false, "休憩", "", it.restSet))
        }
        if (i < m.items.size - 1 && m.rest > 0) out.add(Seg(false, "休憩", "", m.rest))
    }
    return out
}

fun segsToJson(l: List<Seg>): String {
    val a = JSONArray()
    l.forEach { a.put(JSONObject().put("w", it.work).put("l", it.label).put("s", it.sub).put("t", it.sec)) }
    return a.toString()
}

fun segsFromJson(s: String): List<Seg> {
    val a = JSONArray(s)
    return (0 until a.length()).map { val o = a.getJSONObject(it); Seg(o.getBoolean("w"), o.getString("l"), o.getString("s"), o.getInt("t")) }
}

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kintore", Context.MODE_PRIVATE)
    var menus = ArrayList<Menu>()
    var cur = 0
    var sound: Boolean
        get() = sp.getBoolean("sound", false)
        set(v) { sp.edit().putBoolean("sound", v).apply() }

    init { load(); if (menus.isEmpty()) { menus.add(sample()); save() } }

    fun sample() = Menu(
        newId(), "体幹サンプル", 5, 15,
        mutableListOf(Item("ノーマルプランク", 30, 2, 10), Item("サイドプランク右", 20, 1, 0), Item("サイドプランク左", 20, 1, 0))
    )

    fun newId() = System.nanoTime().toString(36)

    private fun load() {
        try {
            val o = JSONObject(sp.getString("data", null) ?: return)
            val a = o.getJSONArray("menus")
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val its = m.getJSONArray("items")
                val items = (0 until its.length()).map {
                    val x = its.getJSONObject(it)
                    Item(x.getString("name"), x.getInt("sec"), x.getInt("sets"), x.getInt("restSet"))
                }.toMutableList()
                menus.add(Menu(m.getString("id"), m.getString("name"), m.getInt("prep"), m.getInt("rest"), items))
            }
            cur = o.optInt("cur", 0).coerceIn(0, maxOf(0, menus.size - 1))
        } catch (e: Exception) { menus.clear() }
    }

    fun save() {
        val a = JSONArray()
        menus.forEach { m ->
            val its = JSONArray()
            m.items.forEach { its.put(JSONObject().put("name", it.name).put("sec", it.sec).put("sets", it.sets).put("restSet", it.restSet)) }
            a.put(JSONObject().put("id", m.id).put("name", m.name).put("prep", m.prep).put("rest", m.rest).put("items", its))
        }
        sp.edit().putString("data", JSONObject().put("menus", a).put("cur", cur).toString()).apply()
    }

    fun menu(): Menu = menus[cur]
}
