package cn.hdfk7.android.iptv.feature.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import cn.hdfk7.android.iptv.BuildConfig
import cn.hdfk7.android.iptv.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException

class AppUpdateFileProvider : FileProvider()

internal data class AppRelease(val versionCode: Int, val versionName: String, val downloadUrl: String, val notes: String = "")

private suspend fun readAppRelease(address: String): AppRelease {
    val manifestUrl = address.toHttpUrlOrNull()
    if (manifestUrl == null || !manifestUrl.isHttps || manifestUrl.username.isNotEmpty() || manifestUrl.password.isNotEmpty()) {
        throw AppUpdateDownloadException(R.string.app_update_manifest_invalid)
    }
    val downloaded = withTimeout(timeMillis = 20_000L, block = { downloadUpdateManifest(manifestUrl.toString()) })
    try {
        val manifest = JSONObject(downloaded.body)
        val versionCode = manifest.get("versionCode") as? Int ?: error("Expected an integer versionCode")
        val versionName = manifest.getString("versionName")
        val downloadUrl = requireNotNull(downloaded.url.resolve(manifest.getString("apkUrl")))
        require(versionCode > 0 && versionName.isNotBlank() && downloadUrl.isHttps && downloadUrl != downloaded.url && downloadUrl != manifestUrl &&
            downloadUrl.username.isEmpty() && downloadUrl.password.isEmpty())
        val notes = (manifest.opt("releaseNotes") as? String).orEmpty().trim().take(600)
        return AppRelease(versionCode, versionName, downloadUrl.toString(), notes)
    } catch (_: Exception) {
        throw AppUpdateDownloadException(R.string.app_update_manifest_invalid)
    }
}

@Suppress("DEPRECATION")
private fun installerIntent(context: Context, apk: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    return Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        .also(block = { intent -> intent.clipData = ClipData.newRawUri("APK", uri) })
}

@Suppress("DEPRECATION")
private fun installedVersion(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }
}

@Composable
fun AppUpdateDialog(onClose: () -> Unit): Unit {
    val context = LocalContext.current
    val store = remember(context, calculation = { AppUpdateStore(context.applicationContext) })
    val address = BuildConfig.APP_UPDATE_MANIFEST_URL
    val scope = rememberCoroutineScope()
    var checkAttempt by remember(calculation = { mutableIntStateOf(0) })
    var result by remember(calculation = { mutableStateOf<Result<AppRelease>?>(null) })
    var downloading by remember(calculation = { mutableStateOf(false) })
    val downloadProgress = remember(calculation = { MutableStateFlow<AppDownloadProgress?>(null) })
    val validation = remember(calculation = { MutableStateFlow(false) })
    val progress by downloadProgress.collectAsState()
    val validating by validation.collectAsState()
    var downloadedApk by remember(calculation = { mutableStateOf<File?>(null) })
    var actionMessage by remember(calculation = { mutableStateOf<Int?>(null) })
    var recovery by remember(calculation = { mutableStateOf(UpdateAction.Download) })
    var needsPermission by remember(calculation = { mutableStateOf(false) })
    var installing by remember(calculation = { mutableStateOf(false) })
    val latest = result?.getOrNull()
    val update = latest?.takeIf(predicate = { release -> release.versionCode > BuildConfig.VERSION_CODE })
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult(), onResult = { outcome ->
        installing = false
        val expectedVersion = update?.versionCode
        actionMessage = when {
            expectedVersion != null && installedVersion(context) >= expectedVersion -> R.string.app_update_install_complete
            outcome.resultCode == Activity.RESULT_OK -> R.string.app_update_install_unconfirmed
            outcome.resultCode == Activity.RESULT_CANCELED -> R.string.app_update_install_cancelled
            else -> installationFailureMessage(outcome.data?.getIntExtra("android.intent.extra.INSTALL_RESULT", 0) ?: 0)
        }
    })
    val install: (File) -> Unit = { apk ->
        when {
            !apk.isFile || apk.length() == 0L -> {
                downloadedApk = null
                recovery = UpdateAction.Download
                actionMessage = R.string.app_update_file_missing
            }
            !context.packageManager.canRequestPackageInstalls() -> {
                needsPermission = true
                actionMessage = R.string.app_update_allow_install
            }
            else -> {
                needsPermission = false
                try {
                    installing = true
                    installer.launch(installerIntent(context, apk))
                    actionMessage = R.string.app_update_install_ready
                } catch (_: ActivityNotFoundException) {
                    installing = false
                    actionMessage = R.string.app_update_installer_missing
                } catch (_: SecurityException) {
                    installing = false
                    actionMessage = R.string.app_update_install_access_denied
                } catch (_: RuntimeException) {
                    installing = false
                    actionMessage = R.string.app_update_install_failed
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult(), onResult = { _ ->
        store.clearAuthorizing()
        needsPermission = !context.packageManager.canRequestPackageInstalls()
        actionMessage = if (needsPermission) R.string.app_update_permission_not_granted else R.string.app_update_permission_granted
    })
    val authorize: () -> Unit = {
        if (context.packageManager.canRequestPackageInstalls()) {
            needsPermission = false
            actionMessage = R.string.app_update_permission_granted
        } else if (store.markAuthorizing()) {
            try {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            } catch (_: RuntimeException) {
                store.clearAuthorizing()
                actionMessage = R.string.app_update_permission_settings_unavailable
            }
        } else {
            actionMessage = R.string.app_update_save_failed
        }
    }
    LaunchedEffect(key1 = address, key2 = checkAttempt, block = {
        result = null
        actionMessage = null
        if (checkAttempt == 0) {
            val saved = withContext(context = Dispatchers.IO, block = {
                val release = store.pendingRelease()
                if (release == null) {
                    if (store.apk.exists()) {
                        store.discard()
                    }
                    null
                } else {
                    try {
                        validateUpdateApk(context, store.apk, release)
                        release
                    } catch (_: AppUpdateDownloadException) {
                        store.discard()
                        null
                    }
                }
            })
            store.clearAuthorizing()
            if (saved != null) {
                result = Result.success(saved)
                downloadedApk = store.apk
                needsPermission = !context.packageManager.canRequestPackageInstalls()
                actionMessage = if (needsPermission) R.string.app_update_allow_install else R.string.app_update_download_ready
                return@LaunchedEffect
            }
        }
        if (address.isNotBlank()) {
            result = withContext(context = Dispatchers.IO, block = {
                try {
                    Result.success(readAppRelease(address))
                } catch (_: TimeoutCancellationException) {
                    Result.failure(AppUpdateDownloadException(R.string.app_update_check_timeout))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            })
        }
    })
    val startDownload: () -> Unit = {
        if (update != null) {
            downloadedApk = null
            downloading = true
            actionMessage = null
            downloadProgress.value = null
            validation.value = false
            scope.launch(block = {
                try {
                    downloadedApk = downloadApk(context.applicationContext, update,
                        onProgress = { status -> downloadProgress.value = status },
                        onVerifying = { validation.value = true })
                    needsPermission = !context.packageManager.canRequestPackageInstalls()
                    actionMessage = if (needsPermission) R.string.app_update_allow_install else R.string.app_update_download_ready
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AppUpdateDownloadException) {
                    actionMessage = failure.messageRes
                    recovery = updateRecovery(failure.messageRes)
                } catch (_: IOException) {
                    actionMessage = R.string.app_update_download_failed
                    recovery = UpdateAction.Download
                } catch (_: Exception) {
                    actionMessage = R.string.app_update_download_unexpected
                    recovery = UpdateAction.Download
                } finally {
                    downloading = false
                    validation.value = false
                }
            })
        }
    }
    val primaryAction = when {
        installing || downloading || address.isBlank() || result == null -> null
        downloadedApk != null && needsPermission -> UpdateAction.Authorize
        downloadedApk != null -> UpdateAction.Install
        latest == null || update == null -> UpdateAction.Check
        actionMessage != null -> recovery
        else -> UpdateAction.Download
    }
    val primaryLabel = when (primaryAction) {
        UpdateAction.Check -> R.string.app_update_retry_check
        UpdateAction.Authorize -> R.string.app_update_authorize_install
        UpdateAction.Install -> R.string.app_update_install
        UpdateAction.Download -> if (actionMessage == null) R.string.app_update_confirm_download else R.string.app_update_retry_download
        null -> null
    }
    val current = progress
    val fraction = current?.fraction
    val notes = if (update != null) {
        update.notes
    } else {
        BuildConfig.APP_RELEASE_NOTES
    }
    val notesVersion = update?.versionName ?: BuildConfig.VERSION_NAME
    val message = when {
        validating -> stringResource(R.string.app_update_verifying)
        downloading && current != null && fraction != null -> stringResource(R.string.app_update_progress, Formatter.formatFileSize(context, current.downloaded), Formatter.formatFileSize(context, current.total), (fraction * 100).toInt())
        downloading && current != null -> stringResource(R.string.app_update_progress_size, Formatter.formatFileSize(context, current.downloaded))
        downloading -> stringResource(R.string.app_update_downloading)
        actionMessage != null -> stringResource(checkNotNull(actionMessage))
        address.isBlank() -> stringResource(R.string.app_update_unconfigured)
        result == null -> stringResource(R.string.app_update_checking)
        latest == null -> stringResource((result?.exceptionOrNull() as? AppUpdateDownloadException)?.messageRes ?: R.string.app_update_failed)
        update == null -> stringResource(R.string.app_update_current, BuildConfig.VERSION_NAME)
        else -> stringResource(R.string.app_update_available, update.versionName)
    }
    AppUpdateContent(
        message = message,
        notes = notes,
        notesVersion = notesVersion,
        downloading = downloading,
        validating = validating,
        fraction = fraction,
        primaryLabel = primaryLabel,
        canRedownload = downloadedApk != null && !installing,
        onPrimary = {
            when (primaryAction) {
                UpdateAction.Check -> { checkAttempt += 1 }
                UpdateAction.Download -> { startDownload() }
                UpdateAction.Authorize -> { authorize() }
                UpdateAction.Install -> { downloadedApk?.let(block = install) }
                null -> Unit
            }
        },
        onRedownload = startDownload,
        onClose = onClose
    )
}
