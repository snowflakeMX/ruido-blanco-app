package ruido.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Only used to lock the screen at night. Locking this way, unlike device admin,
 * keeps fingerprint unlock working in the morning. Reads nothing from the screen.
 */
class ScreenOffService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: ScreenOffService? = null

        val enabled: Boolean get() = instance != null

        /** Turns the screen off; false if the permission is not granted. */
        fun lockScreen(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
    }
}
