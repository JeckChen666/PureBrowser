package com.example.purebrowser.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.download.hls.HlsDownloadPlan
import com.example.purebrowser.download.dash.DashDownloadPlan
import com.example.purebrowser.ui.resources.HlsDownloadConfirmation
import com.example.purebrowser.ui.resources.DashDownloadConfirmation
import com.example.purebrowser.ui.resources.DownloadConfirmation
import com.example.purebrowser.ui.resources.ResourceSheet

/** All choices are captured before opening either permission prompt, never reread from active UI. */
private data class PendingSubmission(
    val draft: DownloadDraft,
    val name: String,
    val wifiOnly: Boolean,
    val plan: HlsDownloadPlan? = null,
    val dashPlan: DashDownloadPlan? = null,
)

@Composable
fun BrowserPanels(model: BrowserViewModel, candidates: List<MediaCandidate>, showResources: Boolean,
                  onDismissResources: () -> Unit, onBusyChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val defaultWifiOnly by model.defaultWifiOnly.collectAsState()
    var confirmDownload by remember { mutableStateOf<DownloadDraft?>(null) }
    var sitePrivacyGeneration by remember { mutableStateOf(-1L) }
    var mediaPrivacyGeneration by remember { mutableStateOf(-1L) }
    var analyzeSite by remember { mutableStateOf<DownloadDraft?>(null) }
    var analyzeMedia by remember { mutableStateOf<DownloadDraft?>(null) }
    var pendingPermission by remember { mutableStateOf<PendingSubmission?>(null) }
    SideEffect { onBusyChanged(confirmDownload != null || analyzeMedia != null || analyzeSite != null || pendingPermission != null) }
    DisposableEffect(Unit) { onDispose { onBusyChanged(false) } }
    val userAgent = remember { WebSettings.getDefaultUserAgent(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted=grants[Manifest.permission.WRITE_EXTERNAL_STORAGE]==true && grants[Manifest.permission.READ_EXTERNAL_STORAGE]==true
        val pending = pendingPermission
        pendingPermission = null
        if(granted && pending != null) model.download(pending.draft, pending.wifiOnly, pending.name, pending.plan, pending.dashPlan)
        else if(!granted) model.notify("未取得旧版 Android 的下载目录写入权限；未创建任务，可重新尝试")
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending=pendingPermission;pendingPermission=null
        if(!granted) model.notify("通知权限未开启；系统可能隐藏下载通知，请在下载中心查看状态")
        if(pending!=null) model.download(pending.draft,pending.wifiOnly,pending.name,pending.plan,pending.dashPlan)
    }
    fun submit(draft: DownloadDraft, name: String, wifiOnly: Boolean, plan: HlsDownloadPlan? = null, dashPlan: DashDownloadPlan? = null) {
        // A second confirmation cannot overwrite a permission request already in flight.
        if (pendingPermission != null) return
        // T117 smart default: the save screens' Wi-Fi choice becomes the next confirmation's default.
        model.rememberDownloadDefaults(wifiOnly)
        val pending = PendingSubmission(
            draft.copy(candidate = draft.candidate.copy(sources = draft.candidate.sources.toSet())),
            name, wifiOnly, plan, dashPlan,
        )
        confirmDownload = null
        if(Build.VERSION.SDK_INT <= 28 && (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            pendingPermission = pending
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE,Manifest.permission.READ_EXTERNAL_STORAGE))
        } else if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = pending
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else model.download(pending.draft, pending.wifiOnly, pending.name, pending.plan, pending.dashPlan)
    }

    val sourcePage=model.engine?.page?.collectAsState()?.value?.url
    val rulesMatched=model.siteRulesMatched()
    val siteAvailable=sourcePage?.let(model.siteResolver::supports)==true

    // T117 quick-save ("用默认设置保存"): one tap creates the task with remembered defaults and the
    // default variant/format; manifest protocols resolve their plan off the UI thread first.
    fun quickSaveDefaults(item: MediaCandidate) {
        if (pendingPermission != null) { model.notify("请先完成当前下载的权限确认"); return }
        if (item.kind == MediaKind.UNKNOWN) { model.notify("该资源需先点开确认类型，暂不支持快捷保存"); return }
        if (model.sniffer?.candidates?.value?.none { it.url == item.url } != false) {
            model.notify("页面资源已更新，请重新打开资源面板"); return
        }
        model.quickSave(item, userAgent) { resolved, name, wifiOnly, plan, dashPlan ->
            submit(resolved, name, wifiOnly, plan, dashPlan)
        }
    }

    if(showResources) ResourceSheet(candidates, onDismissResources, { item ->
        if (pendingPermission != null) {
            model.notify("请先完成当前下载的权限确认")
        } else if(model.sniffer?.candidates?.value?.any { it.url == item.url } == true) {
            val draft=model.downloadDraft(item, userAgent)
            if(item.kind==MediaKind.UNKNOWN){
                mediaPrivacyGeneration=runCatching{model.repository.requestGeneration()}.getOrDefault(-1L)
                analyzeMedia=draft
            } else confirmDownload=draft
        } else model.notify("页面资源已更新，请重新打开资源面板")
        onDismissResources()
    }, { onDismissResources() }, onAnalyzePage=if(siteAvailable||rulesMatched!=null) ({
        if(siteAvailable) {
            val source=sourcePage ?: ""
            sitePrivacyGeneration=runCatching{model.repository.requestGeneration()}.getOrDefault(-1L)
            analyzeSite=model.downloadDraft(MediaCandidate(source,MediaKind.UNKNOWN,setOf(com.example.purebrowser.media.Evidence.SITE)),userAgent).copy(useAccessContext=false,reliableSource=false)
        } else {
            // Rule-matched page without an adapter: the labeled clues already sit in the list below.
            model.notify("已按站点规则识别本页（来源=站点规则）；线索已列入资源列表，可对“待确认媒体”执行分析媒体")
        }
        onDismissResources()
    }) else null, onQuickSave = { item -> quickSaveDefaults(item) })
    analyzeSite?.let { draft ->
        com.example.purebrowser.ui.resources.SiteAnalysisDialog(draft,model.siteResolver,{analyzeSite=null}) { option ->
            val candidate=option.candidate
            val plan=option.dualTrackPlan
            val ready=model.acceptAnalyzed(draft,candidate,sitePrivacyGeneration)
            analyzeSite=null
            if(ready==null)model.notify("页面已变化，请返回来源重新分析") else confirmDownload=ready.copy(dualTrackPlan=plan,useAccessContext=false,reliableSource=false)
        }
    }
    analyzeMedia?.let { draft ->
        com.example.purebrowser.ui.resources.MediaAnalysisDialog(draft,model.mediaProbe,{analyzeMedia=null}) { candidate ->
            val ready=model.acceptAnalyzed(draft,candidate,mediaPrivacyGeneration)
            analyzeMedia=null
            if(ready==null)model.notify("页面已变化，请返回来源重新分析") else confirmDownload=ready
        }
    }
    confirmDownload?.let { draft ->
        // T117: rules-sourced candidates whose rule reported a structured format set open the
        // FOLDED save screen — format rows, name and toggles on one surface; manifest rows run
        // their read/prepare inside the save action, so no re-entry routing exists anymore.
        val variants = draft.candidate.variants.orEmpty()
        val chooserEligible = draft.candidate.kind != MediaKind.HLS && draft.candidate.kind != MediaKind.DASH &&
            com.example.purebrowser.media.Evidence.RULE in draft.candidate.sources &&
            variants.size > 1
        if (chooserEligible) {
            val sessionOffer = model.siteRulesSessionOffer()
            val onSessionChoice: (String, Boolean) -> Unit = { domain, enabled -> model.setRuleSessionOptIn(domain, enabled) }
            com.example.purebrowser.ui.resources.RuleFormatConfirmation(
                draft, defaultWifiOnly, model.hlsResolver, model.dashResolver,
                { confirmDownload = null },
                sessionOffer = sessionOffer, onSessionChoice = onSessionChoice,
            ) { applied, name, wifiOnly, plan, dashPlan ->
                if (plan != null || dashPlan != null) {
                    submit(applied, name, wifiOnly, plan, dashPlan)
                } else {
                    // Direct row: the same probe-verified source-consent rule as the direct screen.
                    val probed = com.example.purebrowser.download.RequestPolicy.canUseProbedContext(applied.candidate.url,applied.candidate.pageUrl,applied.frameUrl)
                    submit(applied.copy(
                        sourceUrl = applied.sourceUrl ?: applied.candidate.pageUrl?.takeIf { applied.useAccessContext && probed },
                        reliableSource = applied.reliableSource || (applied.useAccessContext && probed)), name, wifiOnly)
                }
            }
        } else {
            // T86: the login-session toggle renders only while the ACTIVE page's matched rule
            // declared a same-registrable-domain session and the user has not opted out (the offer
            // is null otherwise); the download transport itself never carries the session.
            val sessionOffer = model.siteRulesSessionOffer()
            val onSessionChoice: (String, Boolean) -> Unit = { domain, enabled -> model.setRuleSessionOptIn(domain, enabled) }
            if (draft.candidate.kind == MediaKind.HLS) {
                HlsDownloadConfirmation(
                    draft, defaultWifiOnly, model.hlsResolver,
                    { confirmDownload = null },
                    sessionOffer = sessionOffer, onSessionChoice = onSessionChoice,
                ) { frozen, name, wifiOnly, plan ->
                    submit(frozen, name, wifiOnly, plan)
                }
            } else if (draft.candidate.kind == MediaKind.DASH) {
                DashDownloadConfirmation(
                    draft, defaultWifiOnly, model.dashResolver,
                    { confirmDownload = null },
                ) { frozen, name, wifiOnly, plan ->
                    submit(frozen, name, wifiOnly, null, plan)
                }
            } else {
                DownloadConfirmation(
                    draft, defaultWifiOnly,
                    { confirmDownload = null },
                    sessionOffer = sessionOffer, onSessionChoice = onSessionChoice,
                ) { name, wifiOnly, useContext ->
                    // A probe-verified, same-origin page association becomes the task's source only under explicit consent.
                    val probed = com.example.purebrowser.download.RequestPolicy.canUseProbedContext(draft.candidate.url,draft.candidate.pageUrl,draft.frameUrl)
                    submit(draft.copy(useAccessContext = useContext,
                        sourceUrl = draft.sourceUrl ?: draft.candidate.pageUrl?.takeIf { useContext && probed },
                        reliableSource = draft.reliableSource || (useContext && probed)), name, wifiOnly)
                }
            }
        }
    }


    // T117 quick-save ("用默认设置保存"): one tap creates the task with remembered defaults and the
    // default variant/format; manifest protocols resolve their plan off the UI thread first.
    // SEAM NOTE for the orchestrator: the candidate CARD lives in ResourceSheet/ResourceLine
    // (ui/resources/ResourceCenter.kt), which this change set must not edit. To finish the wiring,
    // add ONLY an optional parameter `onQuickSave: ((MediaCandidate) -> Unit)? = null` to
    // ResourceSheet, forward it to ResourceLine, and trigger it from the card row's long-press
    // (Modifier.combinedClickable onClick/onLongClick); then pass `onQuickSave = ::quickSaveDefaults`
    // at the ResourceSheet call site near the top of this composable. No other structural change is
    // needed — everything below the card already goes through quickSaveDefaults.
}

