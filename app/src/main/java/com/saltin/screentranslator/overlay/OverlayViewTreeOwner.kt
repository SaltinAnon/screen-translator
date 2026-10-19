package com.saltin.screentranslator.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 给「不在 Activity 里的窗口」用的宿主。
 *
 * ComposeView 被 addView 到 WindowManager 的悬浮窗上时，没有 Activity 给它提供
 * Lifecycle / ViewModelStore / SavedStateRegistry。而 Compose 的 ViewCompositionStrategy
 * 会沿 View 树查找这几个 owner，找不到就完全不渲染。
 * 所以必须自己实现一套，并通过 [androidx.lifecycle.setViewTreeLifecycleOwner] /
 * [androidx.lifecycle.setViewTreeViewModelStoreOwner] /
 * [androidx.savedstate.setViewTreeSavedStateRegistryOwner] 挂到 ComposeView 上。
 *
 * 注意：ViewTreeLifecycleOwner / ViewTreeViewModelStoreOwner / ViewTreeSavedStateRegistryOwner
 * 本身是「带有伴生扩展函数的类」，不是接口，因此这里只实现三个 *Owner 接口。
 */
class OverlayViewTreeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun attach() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun detach() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
