package com.woodblock.ar.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class DetectionBox(
    val classId: Int,
    val className: String,
    val score: Float,
    val rect: RectF
)

data class BlockMetrics(
    val holeRadius: Float = 0.20f,
    val holeOffsetX: Float = 0.0f,
    val holeOffsetZ: Float = 0.0f,
    val grooveRadius: Float = 0.18f,
    val hasHole: Boolean = true,
    val hasGroove: Boolean = true,
    val holeDiameterMm: Float = 20.0f,
    val grooveWidthMm: Float = 18.0f
)

data class YoloResult(
    val isDetected: Boolean,
    val woodBox: RectF?,
    val holeBox: RectF?,
    val grooveBox: RectF?,
    val woodScore: Float,
    val holeScore: Float,
    val grooveScore: Float,
    val allBoxes: List<DetectionBox>,
    val metrics: BlockMetrics,
    val pureWoodTexture: Bitmap
)

class WoodBlockYoloDetector(context: Context) {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val classNames = listOf("wood_block", "hole", "groove")
    // 基于 45 张全实测扩增重训模型（mAP 92.2%）的优化置信度阈值
    private val classThresholds = floatArrayOf(0.45f, 0.35f, 0.25f)

    private val inputWidth = 640
    private val inputHeight = 640
    private val inputFloatBuffer: FloatBuffer = ByteBuffer.allocateDirect(1 * 3 * inputWidth * inputHeight * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val letterboxBitmap: Bitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas: Canvas = Canvas(letterboxBitmap)
    private val letterboxMatrix: Matrix = Matrix()
    private val pixels = IntArray(inputWidth * inputHeight)

    // 纯净天然山毛榉原木材质贴图（彻底根除照片重影）
    val defaultPureWoodTexture: Bitmap = generateProceduralBeechTexture()

    init {
        val modelBytes = context.assets.open("woodblock.onnx").readBytes()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        session = ortEnv.createSession(modelBytes, opts)
    }

    fun detect(bitmap: Bitmap): YoloResult {
        val origW = bitmap.width
        val origH = bitmap.height

        // 1. Letterbox 等比保真缩放（保持长宽比，消除圆孔变椭圆的失真）
        val scale = min(inputWidth.toFloat() / origW, inputHeight.toFloat() / origH)
        val scaledW = origW * scale
        val scaledH = origH * scale
        val padX = (inputWidth - scaledW) / 2f
        val padY = (inputHeight - scaledH) / 2f

        letterboxCanvas.drawColor(Color.BLACK)
        letterboxMatrix.reset()
        letterboxMatrix.setScale(scale, scale)
        letterboxMatrix.postTranslate(padX, padY)
        letterboxCanvas.drawBitmap(bitmap, letterboxMatrix, null)

        // 2. 图像预处理转 float 数组 (NCHW, 0..1)
        letterboxBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val planeSize = inputWidth * inputHeight
        val floatArray = FloatArray(3 * planeSize)
        val rOffset = 0
        val gOffset = planeSize
        val bOffset = planeSize * 2

        for (i in 0 until planeSize) {
            val c = pixels[i]
            floatArray[rOffset + i] = ((c shr 16) and 0xFF) / 255.0f
            floatArray[gOffset + i] = ((c shr 8) and 0xFF) / 255.0f
            floatArray[bOffset + i] = (c and 0xFF) / 255.0f
        }

        inputFloatBuffer.rewind()
        inputFloatBuffer.put(floatArray)
        inputFloatBuffer.rewind()

        val shape = longArrayOf(1, 3, inputWidth.toLong(), inputHeight.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv, inputFloatBuffer, shape)

        // 3. 模型推理
        val output = session.run(Collections.singletonMap("images", inputTensor))
        val outputTensor = output[0] as OnnxTensor
        val outputBuffer = outputTensor.floatBuffer

        // 4. 解析输出张量 [1, 7, 8400]
        val numBoxes = 8400
        val numClasses = 3
        val rawCandidates = ArrayList<DetectionBox>()

        for (i in 0 until numBoxes) {
            var maxScore = 0f
            var bestClass = -1
            for (c in 0 until numClasses) {
                val score = outputBuffer.get((4 + c) * numBoxes + i)
                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            if (bestClass in 0..2 && maxScore >= classThresholds[bestClass]) {
                val cx = outputBuffer.get(0 * numBoxes + i)
                val cy = outputBuffer.get(1 * numBoxes + i)
                val w = outputBuffer.get(2 * numBoxes + i)
                val h = outputBuffer.get(3 * numBoxes + i)

                // 还原 Letterbox 偏移与缩放
                val left = (cx - w / 2f - padX) / scale
                val top = (cy - h / 2f - padY) / scale
                val right = (cx + w / 2f - padX) / scale
                val bottom = (cy + h / 2f - padY) / scale

                rawCandidates.add(
                    DetectionBox(
                        classId = bestClass,
                        className = classNames[bestClass],
                        score = maxScore,
                        rect = RectF(
                            max(0f, left),
                            max(0f, top),
                            min(origW.toFloat(), right),
                            min(origH.toFloat(), bottom)
                        )
                    )
                )
            }
        }

        inputTensor.close()
        output.close()

        // 5. NMS 非极大值抑制
        val nmsBoxes = applyNMS(rawCandidates, 0.45f)

        // 6. 提取最佳检测框
        var bestWood: DetectionBox? = null
        var bestHole: DetectionBox? = null
        var bestGroove: DetectionBox? = null

        for (box in nmsBoxes) {
            when (box.classId) {
                0 -> if (bestWood == null || box.score > bestWood.score) bestWood = box
                1 -> if (bestHole == null || box.score > bestHole.score) bestHole = box
                2 -> if (bestGroove == null || box.score > bestGroove.score) bestGroove = box
            }
        }

        // 7. 计算实测物理尺寸与相对几何参数 (50mm 积木标准尺寸基准)
        var holeRad = 0.15f
        var holeOffX = -0.10f
        var holeOffZ = -0.10f
        var grooveRad = 0.15f
        var holeDiameterMm = 15.0f
        var grooveWidthMm = 15.0f

        if (bestWood != null) {
            val woodW = bestWood.rect.width().coerceAtLeast(10f)
            val woodH = bestWood.rect.height().coerceAtLeast(10f)
            val woodSize = (woodW + woodH) / 2f

            if (bestHole != null) {
                val holeSize = (bestHole.rect.width() + bestHole.rect.height()) / 2f
                val ratio = (holeSize / woodSize).coerceIn(0.20f, 0.45f)
                holeRad = ratio / 2f
                holeDiameterMm = ratio * 50.0f

                val dx = (bestHole.rect.centerX() - bestWood.rect.centerX()) / woodSize
                val dz = (bestHole.rect.centerY() - bestWood.rect.centerY()) / woodSize
                holeOffX = dx.coerceIn(-0.20f, 0.05f)
                holeOffZ = dz.coerceIn(-0.20f, 0.05f)
            }

            if (bestGroove != null) {
                val gSize = min(bestGroove.rect.width(), bestGroove.rect.height())
                val gRatio = (gSize / woodSize).coerceIn(0.20f, 0.42f)
                grooveRad = gRatio / 2f
                grooveWidthMm = gRatio * 50.0f
            }
        }

        val metrics = BlockMetrics(
            holeRadius = holeRad,
            holeOffsetX = holeOffX,
            holeOffsetZ = holeOffZ,
            grooveRadius = grooveRad,
            hasHole = bestHole != null,
            hasGroove = bestGroove != null,
            holeDiameterMm = holeDiameterMm,
            grooveWidthMm = grooveWidthMm
        )

        return YoloResult(
            isDetected = bestWood != null,
            woodBox = bestWood?.rect,
            holeBox = bestHole?.rect,
            grooveBox = bestGroove?.rect,
            woodScore = bestWood?.score ?: 0f,
            holeScore = bestHole?.score ?: 0f,
            grooveScore = bestGroove?.score ?: 0f,
            allBoxes = nmsBoxes,
            metrics = metrics,
            pureWoodTexture = defaultPureWoodTexture
        )
    }

    private fun applyNMS(boxes: List<DetectionBox>, iouThreshold: Float): List<DetectionBox> {
        val sorted = boxes.sortedByDescending { it.score }
        val result = ArrayList<DetectionBox>()

        for (candidate in sorted) {
            var suppress = false
            for (accepted in result) {
                if (candidate.classId == accepted.classId && calculateIoU(candidate.rect, accepted.rect) > iouThreshold) {
                    suppress = true
                    break
                }
            }
            if (!suppress) {
                result.add(candidate)
            }
        }
        return result
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        return interArea / (areaA + areaB - interArea)
    }

    /**
     * 生成高精天然山毛榉原木材质贴图：
     * 具有柔和天然木纹与微小木质纤维颗粒感，彻底杜绝实拍照片孔洞贴在 3D 面上的重影。
     */
    private fun generateProceduralBeechTexture(): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val baseR = 222; val baseG = 188; val baseB = 145
        val ringR = 195; val ringG = 158; val ringB = 118
        val rand = Random(42)

        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val wave = sin(x * 0.08 + sin(y * 0.04) * 4.0)
                val t = ((wave + 1.0) * 0.5 * 0.22).toFloat()
                val noise = (rand.nextFloat() - 0.5f) * 12f

                val r = (baseR * (1f - t) + ringR * t + noise).toInt().coerceIn(0, 255)
                val g = (baseG * (1f - t) + ringG * t + noise).toInt().coerceIn(0, 255)
                val b = (baseB * (1f - t) + ringB * t + noise).toInt().coerceIn(0, 255)

                pixels[y * size + x] = Color.rgb(r, g, b)
            }
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }

    fun close() {
        session.close()
    }
}
