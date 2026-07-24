package com.oberoi.tvblocker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every launcher installed on this TV apart from us. The list is reported to
 * the dashboard so a parent can pick which one the TV uses when blocking is
 * switched off; the others are kept out of reach.
 */
object Launchers {

    data class Entry(val pkg: String, val label: String, val activity: String)

    fun list(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            for (ri in pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)) {
                val p = ri.activityInfo.packageName ?: continue
                if (p == ctx.packageName || p == "android") continue
                val label = try {
                    ri.loadLabel(pm)?.toString() ?: p
                } catch (e: Exception) {
                    p
                }
                out.add(Entry(p, label, ri.activityInfo.name))
            }
        } catch (e: Exception) { }
        return out
    }

    fun packages(ctx: Context): Set<String> {
        val s = HashSet<String>()
        for (e in list(ctx)) s.add(e.pkg)
        return s
    }

    private fun component(ctx: Context, pkg: String): ComponentName? {
        for (e in list(ctx)) {
            if (e.pkg == pkg) return ComponentName(e.pkg, e.activity)
        }
        return null
    }

    /**
     * The launcher chosen on the dashboard. Falls back to the first one found
     * so the TV is never left without a home screen.
     */
    fun chosen(ctx: Context): ComponentName? {
        val want = State.homePackage
        if (want.isNotEmpty()) {
            val c = component(ctx, want)
            if (c != null) return c
        }
        val all = list(ctx)
        return if (all.isEmpty()) null else ComponentName(all[0].pkg, all[0].activity)
    }

    /** Send the TV back to its own home screen. */
    fun goHome(ctx: Context) {
        val cn = chosen(ctx) ?: return
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_HOME)
            i.component = cn
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            ctx.startActivity(i)
        } catch (e: Exception) { }
    }

    fun json(ctx: Context): String {
        val arr = JSONArray()
        for (e in list(ctx)) {
            arr.put(JSONObject().put("pkg", e.pkg).put("label", e.label))
        }
        return arr.toString()
    }
}
