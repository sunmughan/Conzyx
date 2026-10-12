package com.codeair.mhe

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort, synchronous crash/startup logger.
 *
 * The canonical log is kept in the app's private internal files directory as
 * crash.log. A mirror is also maintained in getExternalFilesDir(null) so the
 * user can retrieve the same log without granting broad storage access.
 *
 * The logger deliberately does not depend on coroutines, Compose, preferences,
 * databases, networking, or the runtime installer: it must remain usable when
 * any of those components are the thing that failed.
 */
object AppCrashLogger {
    private const val TAG = "MobileHarnessCrash"
    private const val FILE_NAME = "crash.log"
    private const val MAX_BYTES = 8L * 1024L * 1024L
    private const val KEEP_BYTES = 4L * 1024L * 1024L

    @Volatile
    private var initialized = false

    @Volatile
    private var internalFile: File? = null

    @Volatile
    private var externalFile: File? = null

    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return

            val app = context.applicationContext
            internalFile = File(app.filesDir, FILE_NAME)
            externalFile = app.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                logThrowable("UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
                runCatching {
                    previousHandler?.uncaughtException(thread, throwable)
                }.onFailure { handlerFailure ->
                    Log.e(TAG, "Original Android uncaught-exception handler failed", handlerFailure)
                }
            }

            initialized = true
            write(
                buildString {
                    appendLine("============================================================")
                    appendLine("Mobile Harness crash/startup logger initialized")
                    appendLine("timestamp=${timestamp()}")
                    appendLine("package=${app.packageName}")
                    appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("variant=${BuildConfig.BUILD_TYPE}/${BuildConfig.APP_VARIANT}")
                    appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine("internalLog=${internalFile?.absolutePath}")
                    appendLine("externalMirror=${externalFile?.absolutePath ?: "unavailable"}")
                    appendLine("============================================================")
                },
            )
        }
    }

    fun log(message: String) {
        if (!initialized) return
        write("[${timestamp()}] ${Thread.currentThread().name}: $message\n")
    }

    fun logThrowable(message: String, throwable: Throwable) {
        if (!initialized) return
        // Forward to Crashlytics as well so it appears in the Firebase console.
        runCatching {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(throwable)
        }
        val stack = StringWriter()
        PrintWriter(stack).use { throwable.printStackTrace(it) }
        write(
            buildString {
                append("[${timestamp()}] ${Thread.currentThread().name}: ")
                appendLine(message)
                appendLine("exception=${throwable::class.java.name}")
                appendLine("message=${throwable.message ?: "<null>"}")
                appendLine(stack.toString())
                appendLine("---- end exception ----")
            },
        )
    }

    fun logOperationFailure(operation: String, throwable: Throwable) {
        logThrowable("OPERATION FAILED: $operation", throwable)
    }

    fun checkpoint(name: String, details: String? = null) {
        log("CHECKPOINT $name" + (details?.let { " | " + it.replace('\n', ' ').take(1000) } ?: ""))
    }

    fun logSection(message: String, block: () -> Unit) {
        log("START $message")
        try {
            block()
            log("SUCCESS $message")
        } catch (throwable: Throwable) {
            logThrowable("FAILED $message", throwable)
            throw throwable
        }
    }

    fun internalLogFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun externalLogFile(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    private fun timestamp(): String = synchronized(timestampFormat) {
        timestampFormat.format(Date())
    }

    private fun write(text: String) {
        synchronized(lock) {
            val targets = listOfNotNull(internalFile, externalFile).distinctBy { it.absolutePath }
            for (target in targets) {
                runCatching {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target, true).use { output ->
                        output.write(text.toByteArray(StandardCharsets.UTF_8))
                        output.fd.sync()
                    }
                    trimIfNeeded(target)
                }.onFailure { error ->
                    Log.e(TAG, "Unable to persist crash log at ${target.absolutePath}", error)
                }
            }
        }
    }

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_BYTES) return
        val bytes = file.readBytes()
        val start = (bytes.size - KEEP_BYTES.toInt()).coerceAtLeast(0)
        val retained = bytes.copyOfRange(start, bytes.size)
        FileOutputStream(file, false).use { output ->
            output.write(
                "===== crash.log trimmed; retained newest entries =====\n"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            output.write(retained)
            output.fd.sync()
        }
    }
}
