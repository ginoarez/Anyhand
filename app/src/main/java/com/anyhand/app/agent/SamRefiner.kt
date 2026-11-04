/*
 * Refinador de toques con SAM2 (Segment Anything 2) en el teléfono.
 *
 * Adaptado del proyecto Segment-Anything-Android de Shubham Panchal
 * (Apache License 2.0, ver docs/licenses/): misma forma de sessión
 * ONNX para el encoder/decoder de SAM2, reescrita para devolver el
 * centro de la máscara en vez de pintar bitmaps.
 *
 * Idea: Meta propone un punto aproximado; SAM2 recorta el objeto bajo
 * ese punto y el toque cae en el CENTRO REAL del objeto, no en el
 * píxel adivinado. Si no hay modelos, o algo falla, devuelve null y
 * el toque original se usa tal cual: SAM nunca tumba una orden.
 *
 * Modelos (NO van en el repo, ver .gitignore): descargar un par
 * *_encoder.onnx / *_decoder.onnx de SAM2 (p. ej. sam2 hiera tiny de
 * https://huggingface.co/shubham0204/sam2-onnx-models) y dejarlos en
 * app/src/main/assets/ antes de compilar.
 */
package com.anyhand.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer

class SamRefiner private constructor(
    private val encoderSession: OrtSession,
    private val decoderSession: OrtSession,
    private val env: OrtEnvironment,
) {
    private var cachedShot: Bitmap? = null
    private var cachedEmbedding: Array<FloatBuffer>? = null

    /**
     * Punto refinado (x, y en píxeles de [shot]) o null si la máscara
     * no es usable (vacía, gigante o error): quien llama usa el punto
     * original.
     */
    suspend fun refine(shot: Bitmap, x: Float, y: Float): Pair<Float, Float>? =
        withContext(Dispatchers.Default) {
            try {
                val embedding = embeddingFor(shot) ?: return@withContext null
                val mask = decode(shot, embedding, x, y)
                    ?: return@withContext null
                val center = centroid(mask, shot.width, shot.height)
                    ?: return@withContext null
                // Guardarraíl: SAM refina CERCA del punto pedido. Si
                // el centro de la máscara cae lejos (otra fila de la
                // lista), se descarta y va el punto original.
                val dx = center.first - x
                val dy = center.second - y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist > shot.height * 0.08f) {
                    Log.d("AnyhandSAM",
                        "refine descartado: salto de ${dist.toInt()}px")
                    return@withContext null
                }
                center
            } catch (e: Throwable) {
                // Throwable y no solo Exception: un fallo nativo de
                // ONNX (UnsatisfiedLinkError) jamás debe tumbar la
                // orden; el toque original va tal cual.
                Log.d("AnyhandSAM", "refine falló: ${e.message}")
                null
            }
        }

    /** Salidas ONNX: unas vienen como tensor y otras en Optional. */
    private fun tensorOf(value: Any?): OnnxTensor? = when (value) {
        is OnnxTensor -> value
        is java.util.Optional<*> ->
            value.orElse(null) as? OnnxTensor
        else -> null
    }

    private fun centroid(
        mask: FloatArray,
        w: Int,
        h: Int,
    ): Pair<Float, Float>? {
        var count = 0L
        var sumX = 0L
        var sumY = 0L
        var i = 0
        while (i < mask.size) {
            if (mask[i] > 0f) {
                count++
                sumX += (i % w).toLong()
                sumY += (i / w).toLong()
            }
            i++
        }
        if (count == 0L) return null
        val fraction = count.toDouble() / (w.toDouble() * h)
        // Máscara útil: ni un punto perdido ni media pantalla.
        if (fraction < 0.0002 || fraction > 0.6) {
            Log.d("AnyhandSAM", "máscara descartada fracción=$fraction")
            return null
        }
        val cx = sumX.toFloat() / count
        val cy = sumY.toFloat() / count
        return cx to cy
    }

    private suspend fun embeddingFor(shot: Bitmap): Array<FloatBuffer>? {
        if (cachedShot === shot && cachedEmbedding != null) {
            return cachedEmbedding
        }
        val dim = 1024
        val resized = Bitmap.createScaledBitmap(shot, dim, dim, true)
        val pixels = FloatBuffer.allocate(3 * dim * dim)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        // Una sola lectura masiva de píxeles (antes: 3 millones de
        // getPixel sueltos, el verdadero cuello de botella local).
        val argb = IntArray(dim * dim)
        resized.getPixels(argb, 0, dim, 0, 0, dim, dim)
        // Canales en orden R, G, B (CHW).
        for (c in 0..2) {
            val shift = when (c) {
                0 -> 16
                1 -> 8
                else -> 0
            }
            for (i in argb.indices) {
                val v = (argb[i] shr shift) and 0xFF
                pixels.put((v / 255f - mean[c]) / std[c])
            }
        }
        pixels.rewind()
        val input = OnnxTensor.createTensor(
            env, pixels, longArrayOf(1, 3, dim.toLong(), dim.toLong()))
        val outputs = encoderSession.run(
            mapOf(encoderSession.inputNames.first() to input))
        val names = encoderSession.outputNames.toList()
        // Orden del decoder SAM2: highRes0, highRes1, embedding. El
        // encoder los emite como [highRes0, highRes1, embedding].
        val buffers = Array(names.size) { idx ->
            tensorOf(outputs[names[idx]])?.floatBuffer
                ?: throw IllegalStateException("salida ONNX no tensor")
        }
        cachedShot = shot
        cachedEmbedding = buffers
        return buffers
    }

    private fun decode(
        shot: Bitmap,
        embedding: Array<FloatBuffer>,
        x: Float,
        y: Float,
    ): FloatArray? {
        val w = shot.width
        val h = shot.height
        fun tensor(buf: FloatBuffer, shape: LongArray) =
            OnnxTensor.createTensor(env, buf, shape)
        val inputs = decoderSession.inputNames.toList()
        val outputsNames = decoderSession.outputNames.toList()
        val pointCoords = FloatBuffer.wrap(floatArrayOf(x, y))
        val pointLabels = FloatBuffer.wrap(floatArrayOf(1f))
        val emptyMask = FloatBuffer.wrap(FloatArray(256 * 256) { 0f })
        val map = mutableMapOf<String, OnnxTensor>()
        // Por posición, como el modelo exportado: embedding,
        // highRes0, highRes1, puntos, etiquetas, máscara, hasMask.
        map[inputs[0]] = tensor(embedding[2], longArrayOf(1, 256, 64, 64))
        map[inputs[1]] = tensor(embedding[0], longArrayOf(1, 32, 256, 256))
        map[inputs[2]] = tensor(embedding[1], longArrayOf(1, 64, 128, 128))
        map[inputs[3]] = tensor(pointCoords, longArrayOf(1, 1, 2))
        map[inputs[4]] = tensor(pointLabels, longArrayOf(1, 1))
        map[inputs[5]] = tensor(emptyMask, longArrayOf(1, 1, 256, 256))
        map[inputs[6]] = tensor(
            FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1))
        map["orig_im_size"] = OnnxTensor.createTensor(
            env, IntBuffer.wrap(intArrayOf(h, w)), longArrayOf(2))
        val outputs = decoderSession.run(map)
        val maskBuf = tensorOf(outputs[outputsNames[0]])?.floatBuffer
            ?: return null
        val scores = tensorOf(outputs[outputsNames[1]])
            ?.floatBuffer?.array() ?: return null
        if (scores.isEmpty()) return null
        var best = 0
        for (i in scores.indices) {
            if (scores[i] > scores[best]) best = i
        }
        val perMask = w * h
        if (maskBuf.capacity() < perMask * (best + 1)) return null
        val out = FloatArray(perMask)
        maskBuf.position(perMask * best)
        maskBuf.get(out, 0, perMask)
        return out
    }

    companion object {
        @Volatile
        private var instance: SamRefiner? = null
        private var tried = false

        /**
         * Instancia lista o null si no hay modelos .onnx en assets.
         * Copia los modelos a filesDir la primera vez (ONNX Runtime
         * abre por ruta). Nunca lanza: sin SAM se sigue sin refinar.
         */
        fun get(context: Context): SamRefiner? {
            instance?.let { return it }
            if (tried) return null
            tried = true
            return try {
                val assets = context.assets.list("")?.toList().orEmpty()
                val encName = assets.firstOrNull {
                    it.endsWith(".onnx") && "encoder" in it.lowercase()
                } ?: return null
                val decName = assets.firstOrNull {
                    it.endsWith(".onnx") && "decoder" in it.lowercase()
                } ?: return null
                val dir = File(context.filesDir, "sam").apply { mkdirs() }
                fun copyAsset(name: String): File {
                    val out = File(dir, name)
                    if (!out.exists() || out.length() == 0L) {
                        context.assets.open(name).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                    return out
                }
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    addXnnpack(mapOf("intra_op_num_threads" to "4"))
                }
                val refiner = SamRefiner(
                    env.createSession(copyAsset(encName).absolutePath, options),
                    env.createSession(copyAsset(decName).absolutePath, options),
                    env,
                )
                instance = refiner
                Log.d("AnyhandSAM", "SAM listo ($encName, $decName)")
                refiner
            } catch (e: Throwable) {
                Log.d("AnyhandSAM", "SAM no disponible: ${e.message}")
                null
            }
        }
    }
}
