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
import java.util.UUID

class AppUpdateFileProvider : FileProvider()

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
        val sha256 = if (manifest.has("sha256")) {
            (manifest.get("sha256") as String).also(block = { hash ->
                require(hash.matches(Regex("[0-9a-fA-F]{64}")))
            })
        } else {
            null
        }
        return AppRelease(versionCode, versionName, downloadUrl.toString(), notes, sha256)
    } catch (_: Exception) {
        throw AppUpdateDownloadException(R.string.app_update_manifest_invalid)
    }
}

@Suppress("DEPRECATION")
private fun installerIntent(context: Context, apk: File): Intent {
    // Some installers persist staged APKs by URI even after the source file changes.
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        .buildUpon()
        .appendQueryParameter("installAttempt", UUID.randomUUID().toString())
        .build()
    return Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        .also(block = { intent -> intent.clipData = ClipData.newRawUri("APK", uri) })
}

@Suppress("DEPRECATION")
internal fun installedReleaseMessage(context: Context, release: AppRelease): Int? {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }
    if (release.differsFrom(code, info.versionName)) {
        return null
    }
    val source = info.applicationInfo?.sourceDir
    return if (release.sha256 == null || (source != null && File(source).matchesUpdateDigest(release.sha256))) {
        R.string.app_update_install_complete
    } else {
        R.string.app_update_installed_checksum_mismatch
    }
}

@Composable
fun AppUpdateDialog(onClose: () -> Unit): Unit {
    val context = LocalContext.current
    val store = remember(context, calculation = { AppUpdateStore(context.applicationContext) })
    val address = BuildConfig.APP_UPDATE_MANIFEST_URL
    val scope = rememberCoroutineScope()
    var checkAttempt by remember(calculation = { mutableIntStateOf(0) })
    var state by remember(calculation = { mutableStateOf<UpdateDialogState>(UpdateDialogState.Checking) })
    val downloadProgress = remember(calculation = { MutableStateFlow<AppDownloadProgress?>(null) })
    val validation = remember(calculation = { MutableStateFlow(false) })
    val progress by downloadProgress.collectAsState()
    val validating by validation.collectAsState()
    val currentState = state
    val latest = currentState.release
    val update = latest?.takeIf(predicate = { release -> release.differsFrom(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME) })
    val ready = currentState as? UpdateDialogState.Ready
    val downloading = currentState is UpdateDialogState.Downloading
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult(), onResult = { outcome ->
        val request = state as? UpdateDialogState.Installing
        if (request != null) {
            scope.launch(block = {
                val installed = withContext(context = Dispatchers.IO, block = { installedReleaseMessage(context, request.release) })
                val message = installed ?: when (outcome.resultCode) {
                    Activity.RESULT_OK -> R.string.app_update_install_unconfirmed
                    Activity.RESULT_CANCELED -> R.string.app_update_install_cancelled
                    else -> installationFailureMessage(outcome.data?.getIntExtra("android.intent.extra.INSTALL_RESULT", 0) ?: 0)
                }
                if (state === request) {
                    store.clearInstalling()
                    state = request.ready.copy(message = message)
                }
            })
        }
    })
    val install: (UpdateDialogState.Ready) -> Unit = { candidate ->
        val apk = candidate.apk
        when {
            !apk.isFile || apk.length() == 0L -> {
                state = UpdateDialogState.Available(Result.success(candidate.release), R.string.app_update_file_missing)
            }
            !context.packageManager.canRequestPackageInstalls() -> {
                state = candidate.copy(permission = InstallPermission.Required, message = R.string.app_update_allow_install)
            }
            else -> {
                val allowed = candidate.copy(permission = InstallPermission.Granted)
                state = UpdateDialogState.Installing(allowed)
                scope.launch(block = {
                    val message = try {
                        if (candidate.release.sha256 != null &&
                            !withContext(context = Dispatchers.IO, block = { store.markInstalling(candidate.release) })) {
                            R.string.app_update_save_failed
                        } else {
                            installer.launch(installerIntent(context, apk))
                            null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: ActivityNotFoundException) {
                        R.string.app_update_installer_missing
                    } catch (_: SecurityException) {
                        R.string.app_update_install_access_denied
                    } catch (_: RuntimeException) {
                        R.string.app_update_install_failed
                    }
                    if (message != null) {
                        store.clearInstalling()
                        state = allowed.copy(message = message)
                    }
                })
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult(), onResult = { _ ->
        store.clearAuthorizing()
        val candidate = state as? UpdateDialogState.Ready
        if (candidate != null) {
            val granted = context.packageManager.canRequestPackageInstalls()
            state = candidate.copy(
                permission = if (granted) InstallPermission.Granted else InstallPermission.Required,
                message = if (granted) R.string.app_update_permission_granted else R.string.app_update_permission_not_granted
            )
        }
    })
    val authorize: (UpdateDialogState.Ready) -> Unit = authorize@{ candidate ->
        if ((state as? UpdateDialogState.Ready)?.permission == InstallPermission.Saving) {
            return@authorize
        }
        if (context.packageManager.canRequestPackageInstalls()) {
            state = candidate.copy(permission = InstallPermission.Granted, message = R.string.app_update_permission_granted)
            return@authorize
        }
        state = candidate.copy(permission = InstallPermission.Saving)
        scope.launch(block = {
            var opened = false
            var message = candidate.message
            try {
                if (!withContext(context = Dispatchers.IO, block = { store.markAuthorizing() })) {
                    message = R.string.app_update_save_failed
                    return@launch
                }
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                opened = true
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: RuntimeException) {
                message = R.string.app_update_permission_settings_unavailable
            } finally {
                if (!opened) {
                    store.clearAuthorizing()
                }
                if ((state as? UpdateDialogState.Ready)?.permission == InstallPermission.Saving) {
                    state = candidate.copy(message = message)
                }
            }
        })
    }
    LaunchedEffect(key1 = address, key2 = checkAttempt, block = {
        state = UpdateDialogState.Checking
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
                state = readyUpdate(context, saved, store.apk)
                return@LaunchedEffect
            }
        }
        if (address.isNotBlank()) {
            val result = withContext(context = Dispatchers.IO, block = {
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
            state = UpdateDialogState.Available(result)
        }
    })
    val startDownload: () -> Unit = {
        if (update != null && state !is UpdateDialogState.Downloading &&
            state !is UpdateDialogState.Installing && (state as? UpdateDialogState.Ready)?.permission != InstallPermission.Saving) {
            state = UpdateDialogState.Downloading(update)
            downloadProgress.value = null
            validation.value = false
            scope.launch(block = {
                try {
                    val apk = downloadApk(context.applicationContext, update,
                        onProgress = { status -> downloadProgress.value = status },
                        onVerifying = { validation.value = true })
                    state = readyUpdate(context, update, apk)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AppUpdateDownloadException) {
                    state = UpdateDialogState.Available(Result.success(update), failure.messageRes, updateRecovery(failure.messageRes))
                } catch (_: IOException) {
                    state = UpdateDialogState.Available(Result.success(update), R.string.app_update_download_failed)
                } catch (_: Exception) {
                    state = UpdateDialogState.Available(Result.success(update), R.string.app_update_download_unexpected)
                } finally {
                    validation.value = false
                }
            })
        }
    }
    val primaryAction = if (address.isBlank()) null else currentState.primaryAction(update != null)
    val actionMessage = currentState.message
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
        currentState is UpdateDialogState.Checking -> stringResource(R.string.app_update_checking)
        latest == null -> stringResource(((currentState as? UpdateDialogState.Available)?.result?.exceptionOrNull() as? AppUpdateDownloadException)?.messageRes ?: R.string.app_update_failed)
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
        canRedownload = ready != null,
        onPrimary = {
            when (primaryAction) {
                UpdateAction.Check -> { checkAttempt += 1 }
                UpdateAction.Download -> { startDownload() }
                UpdateAction.Authorize -> { ready?.let(block = authorize) }
                UpdateAction.Install -> { ready?.let(block = install) }
                null -> Unit
            }
        },
        onRedownload = startDownload,
        onClose = {
            if ((state as? UpdateDialogState.Ready)?.permission != InstallPermission.Saving) {
                onClose()
            }
        }
    )
}

private fun readyUpdate(context: Context, release: AppRelease, apk: File): UpdateDialogState.Ready {
    val granted = context.packageManager.canRequestPackageInstalls()
    return UpdateDialogState.Ready(
        release,
        apk,
        if (granted) InstallPermission.Granted else InstallPermission.Required,
        if (granted) R.string.app_update_download_ready else R.string.app_update_allow_install
    )
}
