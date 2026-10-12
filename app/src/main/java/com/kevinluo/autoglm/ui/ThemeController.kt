package com.kevinluo.autoglm.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * ============================================================================
 * ThemeController —— 深色 / 浅色模式控制器（纯 UI）
 * ============================================================================
 * 零基础说明：
 *   Android 官方提供了 AppCompatDelegate.setDefaultNightMode() 来切换深色模式：
 *     · MODE_NIGHT_FOLLOW_SYSTEM（-1）：跟随系统
 *     · MODE_NIGHT_NO（1）           ：强制浅色
 *     · MODE_NIGHT_YES（2）          ：强制深色
 *   调用后，系统会重新加载资源：values-night/ 下的 colors.xml、themes.xml 生效，
 *   并自动重建 Activity。所以气泡、输入框、面板的颜色都会自动适配，
 *   我们不需要为深色模式写任何额外代码。
 *
 * 这个类只做两件事：
 *   1. 把用户的选择存进 SharedPreferences（下次打开 App 还记得）；
 *   2. 启动时把保存的选择应用回去。
 */
object ThemeController {
    /** SharedPreferences 文件名。 */
    private const val PREFS_NAME = "autoglm_ui_prefs"

    /** 保存“夜间模式”偏好用的 key。 */
    private const val KEY_NIGHT_MODE = "night_mode"

    /**
     * 读取用户保存的模式；没保存过就返回“跟随系统”。
     */
    fun getSavedMode(context: Context): Int =
        prefs(context).getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    /**
     * 启动时调用：把保存的深浅色偏好应用回去。
     *
     * ⚠️ 调用时机很重要，必须在 **Application.onCreate()** 里调用（见 AutoGLMApplication）：
     *   Activity 的真实主题是在 `attachBaseContext()` 阶段根据 `sDefaultNightMode`
     *   确定配置的，而 `attachBaseContext()` 发生在 `Activity.onCreate()` 之前。
     *   如果在 `Activity.onCreate()` 里才设置，当前这个 Activity 已经带着
     *   “跟随系统”的配置创建完了，`setDefaultNightMode()` 只能再触发一次
     *   `recreate()` 来纠正 —— 用户会看到一次明显的闪屏（先白后黑）。
     *   放在 Application 里则任何 Activity 都还没创建，一次到位。
     *
     * 另外：没保存过偏好时（用户从未点过切换按钮）直接返回，
     * 不去干预 AppCompat 的默认行为，避免引发多余的 Activity 重建。
     */
    fun applySavedMode(context: Context) {
        val preferences = prefs(context)
        if (!preferences.contains(KEY_NIGHT_MODE)) return
        AppCompatDelegate.setDefaultNightMode(
            preferences.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        )
    }

    /**
     * 当前界面“看起来”是不是深色。
     *
     * ⚠️ 不要用 `activity.resources.configuration.uiMode` 来判断！实测（小米 HyperOS /
     *    Android 16）在 AppCompat 用 applyOverrideConfiguration() 注入夜间模式后，
     *    这个值的 `night` 标志并不可靠 —— 会出现“界面明明是深色，读出来却没有 night 位”
     *    的情况，于是 toggle() 误判成“当前是浅色”，又写了一遍深色，
     *    用户看到的就是“点了没反应 / 越点越乱”。
     *
     * 因此以**已保存的偏好**为唯一真相：
     *   · 存的是 MODE_NIGHT_YES / MODE_NIGHT_NO → 直接就是答案；
     *   · 存的是“跟随系统”（或从未保存）→ 才去读系统值，而且读
     *     **applicationContext** 的配置（Application 的资源不会被 AppCompat 覆盖，
     *     所以它反映的是真实的系统深色模式）。
     */
    fun isCurrentlyDark(context: Context): Boolean =
        when (getSavedMode(context)) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> {
                val nightModeFlags = context.applicationContext.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }

    /**
     * 一键切换：当前是深色 → 变浅色；当前是浅色 → 变深色。
     *
     * @return 切换后是否为深色（调用方可以据此弹提示、换图标）
     */
    fun toggle(context: Context): Boolean {
        val toDark = !isCurrentlyDark(context)
        setMode(context, if (toDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        return toDark
    }

    /**
     * 保存并立即应用指定模式。
     */
    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_NIGHT_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
