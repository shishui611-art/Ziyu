package io.github.xgl34222220.ziyu

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal class ZiyuApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val processForeground = MutableStateFlow(false)
    private var startedActivityCount = 0

    val nativeImportViewModel: NativeImportViewModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(this),
        )[NativeImportViewModel::class.java]
    }

    val ziyuViewModel: ZiyuViewModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(this),
        )[ZiyuViewModel::class.java]
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount += 1
                processForeground.value = startedActivityCount > 0
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                processForeground.value = startedActivityCount > 0
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        NativeImportNotificationController.ensureChannel(this)
        FontTaskNotificationController.ensureChannel(this)
        superviseNativeImport()
    }

    private fun superviseNativeImport() {
        val model = nativeImportViewModel
        applicationScope.launch {
            var lastPassiveSignature = ""
            var previousPhase = NativeImportPhase.IDLE
            snapshotFlow { model.state }
                .distinctUntilChanged()
                .collect { state ->
                when {
                    state.busy -> {
                        if (previousPhase !in setOf(NativeImportPhase.QUEUED, NativeImportPhase.RUNNING)) {
                            NativeImportNotificationController.clearServiceDetached()
                        }
                        if (!NativeImportNotificationController.isServiceExpected() &&
                            !NativeImportNotificationController.isServiceDetached()
                        ) {
                            runCatching { NativeImportNotificationController.start(this@ZiyuApplication) }
                                .onFailure { android.util.Log.w("ZiyuImport", "Unable to start foreground service", it) }
                        }
                        lastPassiveSignature = ""
                    }
                    !shouldPublishNativeImportNotification(state) -> Unit
                    state.phase != NativeImportPhase.IDLE -> {
                        // A running service publishes the transition before it exits.
                        // A restored paused queue still offers resume controls;
                        // terminal history must not become a new completion event.
                        val detachReason = NativeImportNotificationController.consumeServiceDetached()
                        if (!NativeImportNotificationController.isServiceExpected() || detachReason != null) {
                            val passiveState = nativeImportDetachedNotice(state, detachReason)
                            val signature = "${passiveState.phase}:${passiveState.processed}:${passiveState.message}"
                            if (signature != lastPassiveSignature) {
                                NativeImportNotificationController.notify(this@ZiyuApplication, passiveState)
                                lastPassiveSignature = signature
                            }
                        }
                    }
                    previousPhase != NativeImportPhase.IDLE -> {
                        NativeImportNotificationController.cancel(this@ZiyuApplication)
                        lastPassiveSignature = ""
                    }
                }
                previousPhase = state.phase
            }
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        viewModelStore.clear()
        super.onTerminate()
    }
}

@Composable
internal fun rememberNativeImportViewModel(): NativeImportViewModel {
    val application = LocalContext.current.applicationContext as ZiyuApplication
    return remember(application) { application.nativeImportViewModel }
}
