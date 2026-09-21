package com.acme.scantotally.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.acme.scantotally.scan.LabelConsensus
import com.acme.scantotally.scan.LabelReading
import com.acme.scantotally.scan.TextWord
import com.acme.scantotally.scan.readLabel
import com.acme.scantotally.ui.theme.LocalSemantics
import com.acme.scantotally.ui.theme.TouchTarget
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/**
 * Reading a label with the camera.
 *
 * An addition, never a replacement: the scanner remains the fast path and
 * nothing about it changes. This is for the cartons whose labels put a
 * quantity beside a week number, where the printed WORDS are the only way to
 * tell them apart -- and for suppliers whose numbering we have never seen,
 * where there is no barcode format to have got right in advance.
 *
 * It reads continuously and commits nothing. Measured on the real cartons, OCR
 * once returned "4098-9783" for "4098-9788": one digit out, still shaped like a
 * valid part number. Every value that several frames agreed on was correct, so
 * a value is offered only once they do, and the operator confirms it against
 * the carton before it becomes stock.
 */
@Composable
fun LabelCameraSheet(
    onClose: () -> Unit,
    /** What happened to the last carton added, shown until the next is read. */
    lastResult: String?,
    added: Int,
    onAdd: (product: String?, box: String?, qty: Int?, description: String?) -> Unit,
    /**
     * Take what has been read to the slots to be corrected by hand.
     *
     * OCR gets a digit wrong occasionally -- "4098-9783" for "4098-9788" in the
     * measurement -- so a value that looks wrong has to be fixable before it
     * becomes stock. Rather than a second editor in here, the reading goes to
     * the slots, which already know how to edit each field.
     */
    onCorrect: (product: String?, box: String?, qty: Int?, description: String?) -> Unit,
    /**
     * Read ONLY the product's printed name.
     *
     * For a product that is in neither Tally nor the price list, where the only
     * place its name exists is on the carton in front of the operator. The
     * barcode never carried it, so without this the name has to be typed from
     * a box held in the other hand.
     *
     * Nothing else on the label is offered in this mode: the part number and
     * box number are already known by the time this is reached, and showing
     * them again invites replacing a scanned value with an OCR guess.
     */
    descriptionOnly: Boolean = false,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera not allowed", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Reading a label needs the camera. Scanning still works without it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose) { Text("Back") }
        }
        return
    }

    var consensus by remember { mutableStateOf(LabelConsensus(required = 4)) }
    var seen by remember { mutableStateOf(LabelReading()) }
    var frames by remember { mutableStateOf(0) }

    // Ready for the next carton, without leaving the camera.
    //
    // A pallet is many boxes, and closing the camera after each one means
    // finding the button again every time. The receipt is posted from the scan
    // screen when the operator decides they are finished -- never from here.
    fun readyForNext() {
        consensus = LabelConsensus(required = 4)
        seen = LabelReading()
        frames = 0
    }

    Box(Modifier.fillMaxSize()) {
        CameraFeed { words ->
            consensus.offer(readLabel(words))
            frames = consensus.frames
            seen = LabelReading(
                product = consensus.product?.value,
                box = consensus.box?.value,
                qty = consensus.qty?.value,
                // Not put to a vote: a name is advisory, and the operator sees
                // it in the prompt before it becomes anything.
                description = readLabel(words).description ?: seen.description,
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Hold the label square on",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (added > 0) {
                            Text(
                                "$added on this entry",
                                style = MaterialTheme.typography.labelLarge,
                                color = LocalSemantics.current.accept.fg,
                            )
                        }
                    }

                    // What became of the carton just added, left up until the
                    // next one is read. A refusal -- a duplicate box, say --
                    // has to be seen before the operator moves on.
                    lastResult?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    val correct = { onCorrect(seen.product, seen.box, seen.qty, seen.description) }
                    if (descriptionOnly) {
                        Found("NAME", seen.description, null, frames, {})
                    } else {
                        Found("PRODUCT", seen.product, consensus.product?.votes, frames, correct)
                        Found("BOX", seen.box, consensus.box?.votes, frames, correct)
                        Found("QUANTITY", seen.qty?.toString(), consensus.qty?.votes, frames, correct)
                    }

                    if (!descriptionOnly &&
                        (seen.product != null || seen.box != null || seen.qty != null)
                    ) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tap any of them to correct it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
                ) { Text("Done") }

                val whole = seen.product != null && seen.box != null && seen.qty != null
                Button(
                    onClick = {
                        if (descriptionOnly) {
                            // Only the name travels back. Nothing already
                            // scanned is touched.
                            onAdd(null, null, null, seen.description)
                        } else {
                            onAdd(seen.product, seen.box, seen.qty, seen.description)
                            // Straight on to the next carton. Only a whole
                            // reading has been added; a partial one has gone to
                            // the slots behind, and the camera is finished with
                            // it either way.
                            readyForNext()
                        }
                    },
                    // Nothing read is nothing to offer. A partial reading fills
                    // what it found and leaves the rest showing as missing.
                    enabled = if (descriptionOnly) seen.description != null
                    else seen.product != null || seen.box != null || seen.qty != null,
                    modifier = Modifier.weight(2f).heightIn(min = TouchTarget),
                ) {
                    Text(
                        when {
                            descriptionOnly -> "Use this name"
                            whole -> "Add this box"
                            else -> "Use what was read"
                        },
                    )
                }
            }
        }
    }
}

/** One field, with how many frames agreed -- which is how sure it is. */
@Composable
private fun Found(
    label: String,
    value: String?,
    votes: Int?,
    frames: Int,
    onCorrect: () -> Unit,
) {
    val sem = LocalSemantics.current
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (value != null) it.clickable(onClick = onCorrect) else it }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (value == null) {
            Text(
                if (frames == 0) "looking…" else "not read yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "$value   ${votes ?: 0}/$frames",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = sem.accept.fg,
            )
        }
    }
}

/**
 * The camera, handing every frame's words to the caller.
 *
 * Frames are dropped rather than queued: a label that has moved on is not
 * worth reading late, and the next frame is a few milliseconds away.
 */
@Composable
private fun CameraFeed(onWords: (List<TextWord>) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            recognizer.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)

            future.addListener({
                val provider = future.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(
                        media, proxy.imageInfo.rotationDegrees,
                    )
                    recognizer.process(image)
                        .addOnSuccessListener { text ->
                            val words = buildList {
                                for (block in text.textBlocks) {
                                    for (line in block.lines) {
                                        for (element in line.elements) {
                                            val b = element.boundingBox ?: continue
                                            add(
                                                TextWord(
                                                    element.text,
                                                    b.left, b.top, b.width(), b.height(),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            onWords(words)
                        }
                        .addOnCompleteListener { proxy.close() }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
            }, ContextCompat.getMainExecutor(ctx))

            view
        },
    )
}
