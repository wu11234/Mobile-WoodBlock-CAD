package com.woodblock.ar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.woodblock.ar.data.GeneratedModelStore
import com.woodblock.ar.data.Saved3DModel
import com.woodblock.ar.databinding.ActivityMainBinding
import com.woodblock.ar.geometry.TrueCadSolidBlockBuilder
import com.woodblock.ar.render.PureGlRenderer
import com.woodblock.ar.vision.DetectionBox
import com.woodblock.ar.vision.WoodBlockYoloDetector
import com.woodblock.ar.vision.YoloResult
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2

enum class ScanState {
    IDLE,       // 待机 (严格 0 模型，绝不呈现任何预设虚假几何体)
    SCANNING,   // 实时逐帧视觉解算与连续几何构建
    PAUSED,     // 暂停 (锁定当前几何与位姿，支持手势 360° 旋转观察)
    FINISHED    // 结束 (模型入库归档，支持全屏预览与导出工业标准 OBJ)
}

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var yoloDetector: WoodBlockYoloDetector? = null
    private val cadBuilder = TrueCadSolidBlockBuilder()

    private lateinit var glRenderer: PureGlRenderer

    private var currentState: ScanState = ScanState.IDLE
    private var currentMesh: TrueCadSolidBlockBuilder.GeneratedMesh? = null

    // 顶部状态栏最小化标志
    private var isTopMinimized = false

    // 空间姿态平滑滤波器
    private var smoothedYawDeg = 45.0f
    private var consecutiveMisses = 0
    private var scanFrames = 0

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "需要相机权限进行 3D 实时扫描", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        glRenderer = PureGlRenderer(this)

        // 严格零预置保证：开机待机时彻底清空 3D 视口，绝不渲染任何虚假模型！
        glRenderer.clearMesh()

        cameraExecutor.execute {
            try {
                yoloDetector = WoodBlockYoloDetector(this)
                Log.d(TAG, "YOLOv8 92.2% ONNX loaded!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load vision models", e)
            }
        }

        setup3DSurfaceView()
        setupButtons()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setup3DSurfaceView() {
        binding.cameraPreviewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.surfaceView3D.setZOrderMediaOverlay(true)
        binding.surfaceView3D.setEGLContextClientVersion(2)
        binding.surfaceView3D.setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        binding.surfaceView3D.setRenderer(glRenderer)
        binding.surfaceView3D.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY

        var lastX = 0f
        var lastY = 0f

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                glRenderer.zoomScale = (glRenderer.zoomScale * detector.scaleFactor).coerceIn(0.5f, 3.0f)
                return true
            }
        })

        binding.surfaceView3D.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    glRenderer.autoRotate = false
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    glRenderer.rotY += dx * 0.4f
                    glRenderer.rotX += dy * 0.4f
                    lastX = event.x
                    lastY = event.y
                }
            }
            true
        }
    }

    private fun toggleTopPanelMinimize() {
        isTopMinimized = !isTopMinimized
        if (isTopMinimized) {
            binding.layoutTopCollapsible.visibility = View.GONE
            binding.btnToggleMinimize.text = "展开 ▼"
        } else {
            binding.layoutTopCollapsible.visibility = View.VISIBLE
            binding.btnToggleMinimize.text = "收起 ▲"
        }
    }

    private fun setupButtons() {
        // 顶部面板最小化 / 展开切换
        binding.btnToggleMinimize.setOnClickListener { toggleTopPanelMinimize() }
        binding.layoutTopHeader.setOnClickListener { toggleTopPanelMinimize() }

        // 1. 【开始】按钮
        binding.btnStart.setOnClickListener {
            currentState = ScanState.SCANNING
            currentMesh = null
            glRenderer.clearMesh()
            consecutiveMisses = 0
            scanFrames = 0
            binding.scanProgressBar.progress = 0
            binding.scanProgressBar.visibility = View.VISIBLE
            binding.tvBadge3D.text = "正在生成..."
            updateUIState()
            Toast.makeText(this, "开始扫描：请对准木块", Toast.LENGTH_SHORT).show()
        }

        // 2. 【暂停】按钮
        binding.btnPause.setOnClickListener {
            if (currentState == ScanState.SCANNING) {
                currentState = ScanState.PAUSED
                binding.btnPause.text = "继续"
                binding.tvVisionStatus.text = "已暂停，可滑动查看 3D 模型"
            } else if (currentState == ScanState.PAUSED) {
                currentState = ScanState.SCANNING
                binding.btnPause.text = "暂停"
                binding.tvVisionStatus.text = "继续扫描中..."
            }
            updateUIState()
        }

        // 3. 【结束】按钮
        binding.btnFinish.setOnClickListener {
            currentState = ScanState.FINISHED
            binding.scanProgressBar.visibility = View.GONE

            val mesh = currentMesh
            if (mesh != null) {
                val saved = GeneratedModelStore.saveModel(mesh)
                Toast.makeText(this, "模型生成完成！共 ${saved.triangleCount} 面", Toast.LENGTH_SHORT).show()
                openModelViewerDialog(saved)
            } else {
                Toast.makeText(this, "尚未生成模型，请先扫描木块", Toast.LENGTH_SHORT).show()
            }
            updateUIState()
        }

        // 4. 【模型】按钮 (严格零预置：无数据则提示，绝不捏造假模型)
        binding.btnModels.setOnClickListener {
            val model = GeneratedModelStore.latestModel
            if (model != null) {
                openModelViewerDialog(model)
            } else {
                Toast.makeText(this, "暂无生成的模型，请先扫描木块", Toast.LENGTH_SHORT).show()
            }
        }

        updateUIState()
    }

    private fun updateUIState() {
        when (currentState) {
            ScanState.IDLE -> {
                binding.btnStart.isEnabled = true
                binding.btnPause.isEnabled = false
                binding.btnPause.text = "暂停"
                binding.btnFinish.isEnabled = false
                binding.scanProgressBar.visibility = View.GONE
                binding.tvStateTitle.text = "等待扫描"
                binding.tvVisionStatus.text = "将摄像头对准木块，点击【开始】"
                binding.viewLiveDot.setBackgroundColor(0xFF888888.toInt())
                binding.tvBadge3D.text = "3D 预览"
                glRenderer.clearMesh()
            }
            ScanState.SCANNING -> {
                binding.btnStart.isEnabled = false
                binding.btnPause.isEnabled = true
                binding.btnPause.text = "暂停"
                binding.btnFinish.isEnabled = true
                binding.scanProgressBar.visibility = View.VISIBLE
                binding.tvStateTitle.text = "正在扫描"
                binding.viewLiveDot.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
            }
            ScanState.PAUSED -> {
                binding.btnStart.isEnabled = false
                binding.btnPause.isEnabled = true
                binding.btnPause.text = "继续"
                binding.btnFinish.isEnabled = true
                binding.tvStateTitle.text = "已暂停"
                binding.viewLiveDot.setBackgroundColor(0xFFFFB300.toInt())
            }
            ScanState.FINISHED -> {
                binding.btnStart.isEnabled = true
                binding.btnPause.isEnabled = false
                binding.btnPause.text = "暂停"
                binding.btnFinish.isEnabled = false
                binding.scanProgressBar.visibility = View.GONE
                binding.tvStateTitle.text = "扫描完成"
                binding.tvVisionStatus.text = "点击【模型】可全屏查看或导出"
                binding.viewLiveDot.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processCameraFrame(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processCameraFrame(imageProxy: ImageProxy) {
        val detector = yoloDetector
        if (detector == null) {
            imageProxy.close()
            return
        }

        val bitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        val rotatedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val vw = binding.cameraPreviewView.width
        val vh = binding.cameraPreviewView.height

        if (vw > 0 && vh > 0) {
            val yoloResult = detector.detect(rotatedBitmap)

            val scaleX = vw.toFloat() / rotatedBitmap.width
            val scaleY = vh.toFloat() / rotatedBitmap.height

            val scaledBoxes = yoloResult.allBoxes.map { b ->
                DetectionBox(
                    classId = b.classId,
                    className = b.className,
                    score = b.score,
                    rect = RectF(
                        b.rect.left * scaleX,
                        b.rect.top * scaleY,
                        b.rect.right * scaleX,
                        b.rect.bottom * scaleY
                    )
                )
            }
            val viewResult = yoloResult.copy(allBoxes = scaledBoxes)

            runOnUiThread {
                binding.visionOverlay.updateYoloResult(viewResult)

                // 待机状态：严格确保清空 3D 窗口，绝不渲染假模
                if (currentState == ScanState.IDLE) {
                    glRenderer.clearMesh()
                    binding.tvBadge3D.text = "3D 预览"
                    return@runOnUiThread
                }

                // 仅在 SCANNING 状态下进行特征提取与实体构建
                if (currentState == ScanState.SCANNING) {
                    if (yoloResult.isDetected && yoloResult.woodBox != null && yoloResult.woodScore >= 0.45f) {
                        consecutiveMisses = 0

                        val woodRect = yoloResult.woodBox

                        // 空间朝向平滑跟踪
                        var targetYaw = smoothedYawDeg
                        if (yoloResult.holeBox != null && yoloResult.grooveBox != null) {
                            val hx = yoloResult.holeBox.centerX()
                            val hy = yoloResult.holeBox.centerY()
                            val gx = yoloResult.grooveBox.centerX()
                            val gy = yoloResult.grooveBox.centerY()
                            val angleDeg = Math.toDegrees(atan2((gy - hy).toDouble(), (gx - hx).toDouble())).toFloat()
                            targetYaw = -angleDeg + 45f
                        } else if (yoloResult.holeBox != null) {
                            val hx = yoloResult.holeBox.centerX()
                            val hy = yoloResult.holeBox.centerY()
                            val wx = woodRect.centerX()
                            val wy = woodRect.centerY()
                            val angleDeg = Math.toDegrees(atan2((hy - wy).toDouble(), (hx - wx).toDouble())).toFloat()
                            targetYaw = -angleDeg - 135f
                        }

                        var diffYaw = (targetYaw - smoothedYawDeg) % 360f
                        if (diffYaw > 180f) diffYaw -= 360f
                        if (diffYaw < -180f) diffYaw += 360f
                        smoothedYawDeg = (smoothedYawDeg + diffYaw * 0.25f) % 360f

                        glRenderer.targetRotY = smoothedYawDeg
                        val tiltOffset = ((woodRect.centerY() / vh.toFloat()) - 0.5f) * 20f
                        glRenderer.targetRotX = 25f + tiltOffset

                        scanFrames++
                        val progress = (scanFrames * 100 / 40).coerceIn(0, 100)
                        binding.scanProgressBar.progress = progress

                        // 实时连续 CAD 实体重构 (60 FPS 极速流畅，完全水密实体)
                        if (scanFrames >= 10) {
                            val mesh = cadBuilder.build(yoloResult.metrics)
                            currentMesh = mesh
                            glRenderer.updateGeneratedMesh(mesh)
                            binding.tvBadge3D.text = "3D 模型 (${mesh.triangleCount} 面)"
                            binding.tvVisionStatus.text = "模型已就绪 ($progress%)"
                        } else {
                            binding.tvVisionStatus.text = "正在提取特征 ($progress%)..."
                        }
                    } else {
                        consecutiveMisses++
                        if (consecutiveMisses > 20) {
                            binding.tvVisionStatus.text = "请将摄像头对准木块..."
                        }
                    }
                }
            }
        }

        imageProxy.close()
    }

    /**
     * 打开【模型】查看器，支持 360° 旋转观察实体白模与导出标准 Wavefront OBJ 文件
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun openModelViewerDialog(savedModel: Saved3DModel) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_model_viewer)

        val tvTitle = dialog.findViewById<TextView>(R.id.tvViewerTitle)
        val tvDetails = dialog.findViewById<TextView>(R.id.tvModelDetails)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.btnCloseViewer)
        val btnToggleColor = dialog.findViewById<MaterialButton>(R.id.btnToggleColorMode)
        val btnExport = dialog.findViewById<MaterialButton>(R.id.btnExportPly)
        val surfaceView = dialog.findViewById<android.opengl.GLSurfaceView>(R.id.surfaceViewerDialog)

        val dimStr = String.format(java.util.Locale.US, "%.1f×%.1f×%.1f mm", savedModel.widthMm, savedModel.heightMm, savedModel.depthMm)
        tvTitle.text = savedModel.name
        tvDetails.text = "三角面数: ${savedModel.triangleCount} | 尺寸: $dimStr"

        btnToggleColor.text = "自转"
        btnExport.text = "导出 OBJ"

        val viewerRenderer = PureGlRenderer(this).apply {
            if (savedModel.mesh != null) {
                updateGeneratedMesh(savedModel.mesh)
            }
            autoRotate = true
        }

        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        surfaceView.setRenderer(viewerRenderer)
        surfaceView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY

        var lastX = 0f
        var lastY = 0f
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                viewerRenderer.zoomScale = (viewerRenderer.zoomScale * detector.scaleFactor).coerceIn(0.5f, 3.0f)
                return true
            }
        })

        surfaceView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    viewerRenderer.autoRotate = false
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    viewerRenderer.rotY += dx * 0.5f
                    viewerRenderer.rotX += dy * 0.5f
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    dialog.window?.decorView?.postDelayed({ viewerRenderer.autoRotate = true }, 4000)
                }
            }
            true
        }

        // 切换自转与静止
        btnToggleColor.setOnClickListener {
            viewerRenderer.autoRotate = !viewerRenderer.autoRotate
            val modeDesc = if (viewerRenderer.autoRotate) "开启自动旋转" else "关闭自动旋转"
            Toast.makeText(this, modeDesc, Toast.LENGTH_SHORT).show()
        }

        // 导出工业标准 Wavefront OBJ CAD 文件
        btnExport.setOnClickListener {
            try {
                val exportDir = getExternalFilesDir(null) ?: filesDir
                val prefix = "woodblock_"
                val file = File(exportDir, "${prefix}${savedModel.id}.obj")
                file.writeText(savedModel.objContent)
                val mtlFile = File(exportDir, "${prefix}${savedModel.id}.mtl")
                mtlFile.writeText("# WoodBlock Material\nnewmtl wood_mat\nKa 0.2 0.2 0.2\nKd 0.85 0.70 0.50\nKs 0.1 0.1 0.1\nNs 10\n")

                Toast.makeText(this, "OBJ 模型已导出至:\n${file.name}", Toast.LENGTH_LONG).show()
                Log.d("MainActivity", "Exported 3D CAD OBJ to: ${file.absolutePath}")
            } catch (e: Exception) {
                Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        yoloDetector?.close()
    }
}
