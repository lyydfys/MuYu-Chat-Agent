package com.muyuchat.mca.debug

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.muyuchat.mca.LocalImageBundleManifestInspection
import com.muyuchat.mca.LocalImageGenerationOptions
import com.muyuchat.mca.LocalImageWorkerCancelledException
import com.muyuchat.mca.executeMediaTekAotGeneration
import com.muyuchat.mca.inspectLocalImageBundleManifestFromRoot
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * One-tap production-chain test for a MediaTek device.
 *
 * Pick a v4 bundle ZIP, the activity extracts it, runs the production
 * executeMediaTekAotGeneration path, and writes PNG, metadata, final latent and
 * a log under Android/data/<package>/files/mtk-chain-runs/<runId>/.
 */
class MediaTekChainSmokeActivity : Activity() {
    private val tag = "MCA-MTK-CHAIN"
    private val requestOpenZip = 41
    private val ui = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private var lastRunDir: File? = null
    private lateinit var logView: TextView
    private lateinit var promptView: EditText
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "MCA MediaTek NPUG/AOT chain test"
            textSize = 20f
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Copy the v4 bundle ZIP to the phone, then tap Select bundle ZIP. " +
                "The run writes PNG, metadata, final-latent.f32 and log under Android/data/" +
                "com.muyuchat.mca/files/mtk-chain-runs/."
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        promptView = EditText(this).apply {
            setText("a cat")
            hint = "prompt (dynamic bundle only)"
        }
        root.addView(promptView)
        root.addView(Button(this).apply {
            text = "Select bundle ZIP and run"
            setOnClickListener { selectZip() }
        })
        root.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                cancelRequested.set(true)
                appendLog("cancel requested")
            }
        })
        root.addView(Button(this).apply {
            text = "Share last results"
            setOnClickListener { shareLast() }
        })
        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(logView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun selectZip() {
        if (running.get()) {
            appendLog("a run is already active")
            return
        }
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
            requestOpenZip,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestOpenZip || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runZip(uri)
    }

    private fun runZip(uri: android.net.Uri) {
        if (!running.compareAndSet(false, true)) return
        cancelRequested.set(false)
        lastRunDir = null
        logBuilder.setLength(0)
        logView.text = ""
        Thread {
            val runId = "chain-${System.currentTimeMillis()}"
            val outDir = File(getExternalFilesDir("mtk-chain-runs"), runId).apply { mkdirs() }
            val bundleDir = File(getExternalFilesDir("mtk-chain-bundles"), runId).apply { mkdirs() }
            try {
                appendLog("runId=$runId")
                appendLog("outDir=${outDir.absolutePath}")
                extractZip(uri, bundleDir)
                checkCancelled()
                val inspection = inspectLocalImageBundleManifestFromRoot(bundleDir)
                require(inspection is LocalImageBundleManifestInspection.Ready) {
                    "bundle is not ready: $inspection"
                }
                val envelope = inspection.manifest
                val aot = requireNotNull(envelope.mediatekAotManifest) { "bundle has no MediaTek AOT manifest" }
                val profile = aot.profiles.first()
                appendLog(
                    "bundle id=${envelope.id} conditioning=${aot.conditioning.mode} " +
                        "profile=${profile.id} steps=${profile.defaultSteps} variants=${aot.variants.size}",
                )
                appendLog("blockers=${aot.executionBlockers()}")
                val options = LocalImageGenerationOptions(
                    width = profile.widthPixels,
                    height = profile.heightPixels,
                    steps = profile.defaultSteps,
                    seed = 42,
                    cfgScale = profile.defaultCfgScale.toDouble(),
                )
                val startedAt = System.currentTimeMillis()
                val result = executeMediaTekAotGeneration(
                    bundleRoot = bundleDir,
                    manifest = aot,
                    prompt = promptView.text.toString().ifBlank { "a cat" },
                    options = options,
                    isCancellationRequested = { cancelRequested.get() },
                    onProgress = { progress ->
                        appendLog(
                            "progress ${progress.phase} ${progress.step}/${progress.steps} " +
                                "elapsedMs=${progress.elapsedMs} secPerStep=${progress.secondsPerStep}",
                        )
                    },
                    onFinalLatent = { latent -> writeLatent(outDir, latent) },
                )
                writeResult(outDir, result.bytes, result.executionMetadataJson)
                appendLog("PASS elapsedMs=${System.currentTimeMillis() - startedAt} bytes=${result.bytes.size}")
                lastRunDir = outDir
            } catch (cancelled: LocalImageWorkerCancelledException) {
                appendLog("CANCELLED")
                writeText(outDir, "status.txt", "CANCELLED")
            } catch (error: Throwable) {
                appendLog("FAIL ${error.javaClass.simpleName}: ${error.message}")
                Log.e(tag, "chain run failed", error)
                writeText(outDir, "error.txt", error.stackTraceToString())
            } finally {
                writeText(outDir, "run.log", logBuilder.toString())
                running.set(false)
            }
        }.start()
    }

    private fun extractZip(uri: android.net.Uri, root: File) {
        val canonicalRoot = root.canonicalFile.toPath()
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("cannot open the selected ZIP")
        var bytes = 0L
        var nextLog = 512L * 1024 * 1024
        BufferedInputStream(input).use { buffered ->
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    val target = File(root, name).canonicalFile
                    require(target.toPath().startsWith(canonicalRoot)) { "ZIP entry escapes the bundle: $name" }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> zip.copyTo(output, 1 shl 20) }
                        bytes += target.length()
                        if (bytes >= nextLog) {
                            appendLog("extracted ${bytes / (1024 * 1024)} MB")
                            nextLog += 512L * 1024 * 1024
                        }
                    }
                    zip.closeEntry()
                    checkCancelled()
                }
            }
        }
        appendLog("extracted ${bytes / (1024 * 1024)} MB to ${root.absolutePath}")
    }

    private fun writeLatent(outDir: File, latent: FloatArray) {
        val buffer = ByteBuffer.allocate(latent.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        latent.forEach(buffer::putFloat)
        val bytes = buffer.array()
        File(outDir, "final-latent.f32").writeBytes(bytes)
        appendLog("finalLatent elements=${latent.size} sha256=${sha256(bytes)}")
    }

    private fun writeResult(outDir: File, png: ByteArray, metadata: String) {
        File(outDir, "result.png").writeBytes(png)
        File(outDir, "metadata.json").writeText(metadata, Charsets.UTF_8)
        appendLog("pngBytes=${png.size} pngSHA256=${sha256(png)}")
        runCatching {
            val json = JSONObject(metadata)
            appendLog(
                "executionStatus=${json.optString("executionStatus")} " +
                    "qualityStatus=${json.optString("qualityStatus")} " +
                    "acceptanceStatus=${json.optString("acceptanceStatus")}",
            )
        }
    }

    private fun shareLast() {
        val dir = lastRunDir
        if (dir == null || !dir.isDirectory) {
            appendLog("no completed run to share")
            return
        }
        val shareDir = File(cacheDir, "shared_images").apply { mkdirs() }
        val zipFile = File(shareDir, "${dir.name}.zip")
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
            dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file.isFile) {
                    zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share MCA chain results",
            ),
        )
    }

    private fun checkCancelled() {
        if (cancelRequested.get()) throw LocalImageWorkerCancelledException()
    }

    private fun appendLog(message: String) {
        Log.i(tag, message)
        ui.post {
            logBuilder.append(message).append('\n')
            logView.text = logBuilder.toString()
        }
    }

    private fun writeText(dir: File, name: String, text: String) {
        runCatching { File(dir, name).writeText(text, Charsets.UTF_8) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
