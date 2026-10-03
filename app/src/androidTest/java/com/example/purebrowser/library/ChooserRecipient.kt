package com.example.purebrowser.library

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock

/** Uses the real resolver UI; never launches an explicit recipient or skips its UID read. */
internal fun chooseFixtureRecipient(instrument: Instrumentation, timeoutMs: Long): Boolean {
    // Debug and signed test APKs may coexist. Select this exact independent UID,
    // not another fixture with an identical generic label.
    val recipientLabel = instrument.context.packageManager.getActivityInfo(
        android.content.ComponentName(instrument.context.packageName, FixtureFileReceiver::class.java.name), 0
    ).loadLabel(instrument.context.packageManager).toString()
    val automation = instrument.uiAutomation
    val original = automation.serviceInfo
    val flags = original.flags
    original.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    automation.serviceInfo = original
    try {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var nextScroll = 0L
        var scrolls = 0
        var lastRoots = emptyList<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            val roots = (listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root })
            lastRoots = roots.map { it.packageName.toString() }
            for (root in roots) {
                for (match in root.findAccessibilityNodeInfosByText(recipientLabel)) {
                    if (match.text?.toString() != recipientLabel) continue
                    var node: AccessibilityNodeInfo? = match
                    while (node != null && !node.isClickable) node = node.parent
                    if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
                }
            }
            // The independent recipient can be below the collapsed sharesheet's fold.
            if (scrolls < 8 && SystemClock.uptimeMillis() >= nextScroll) {
                fun scroll(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
                    if (depth > 12) return false
                    if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    for (i in 0 until minOf(node.childCount, 100)) {
                        val child = node.getChild(i) ?: continue
                        if (scroll(child, depth + 1)) return true
                    }
                    return false
                }
                roots.filter { it.packageName.toString() in setOf("android", "com.android.intentresolver") }
                    .firstOrNull { scroll(it) }?.let { scrolls++ }
                nextScroll = SystemClock.uptimeMillis() + 750
            }
            Thread.sleep(200)
        }
        android.util.Log.e("ChooserFixture", "Recipient not found; window packages=$lastRoots, scrolls=$scrolls")
        return false
    } finally {
        val restore = automation.serviceInfo
        restore.flags = flags
        automation.serviceInfo = restore
    }
}
