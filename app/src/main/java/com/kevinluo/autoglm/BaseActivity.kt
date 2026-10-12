package com.kevinluo.autoglm

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Base Activity that provides consistent edge-to-edge immersive status bar
 * across all activities in the app.
 *
 * All activities should extend this class to get unified immersive UI behavior.
 *
 * 说明：深浅色偏好的应用**不在这里**做，而是在 AutoGLMApplication.onCreate() 里做。
 * 原因：Activity 的主题在 attachBaseContext() 阶段就已确定，那早于 onCreate()，
 * 在这里调用 AppCompatDelegate.setDefaultNightMode() 会多触发一次 Activity 重建（闪屏）。
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    /**
     * Sets up edge-to-edge insets for the root view.
     * Call this after setContentView() with the root view ID.
     *
     * @param rootViewId The resource ID of the root view to apply insets to
     * @param applyTop Whether to apply top inset (status bar padding)
     * @param applyBottom Whether to apply bottom inset (navigation bar padding)
     */
    protected fun setupEdgeToEdgeInsets(rootViewId: Int, applyTop: Boolean = true, applyBottom: Boolean = true) {
        val rootView = findViewById<android.view.View>(rootViewId)
        setupEdgeToEdgeInsets(rootView, applyTop = applyTop, applyBottom = applyBottom)
    }

    /**
     * Sets up edge-to-edge insets for a specific view.
     *
     * @param view The view to apply insets to
     * @param applyTop Whether to apply top inset
     * @param applyBottom Whether to apply bottom inset
     */
    protected fun setupEdgeToEdgeInsets(
        view: android.view.View,
        applyTop: Boolean = true,
        applyBottom: Boolean = true,
    ) {
        val initialPaddingLeft = view.paddingLeft
        val initialPaddingTop = view.paddingTop
        val initialPaddingRight = view.paddingRight
        val initialPaddingBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = initialPaddingLeft,
                top = initialPaddingTop + (if (applyTop) insets.top else 0),
                right = initialPaddingRight,
                bottom = initialPaddingBottom + (if (applyBottom) insets.bottom else 0),
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
