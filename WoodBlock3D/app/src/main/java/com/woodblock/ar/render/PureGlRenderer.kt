package com.woodblock.ar.render

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.woodblock.ar.geometry.TrueCadSolidBlockBuilder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 纯净三维实体渲染器：
 * 双通道渲染，绘制哑光白模 + 高对比工程线框；
 * 严格零预置，开机待机严格 0 模型；支持手势平滑缩放/旋转与视觉空间位姿跟随。
 */
class PureGlRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private var program = -1
    private var positionAttrib = -1
    private var normalAttrib = -1

    private var mvpUniform = -1
    private var mvUniform = -1
    private var normalMatrixUniform = -1
    private var lightDirUniform = -1
    private var isWireframeUniform = -1
    private var useVertexColorUniform = -1

    private var currentCadMesh: TrueCadSolidBlockBuilder.GeneratedMesh? = null
    private var pendingCadMesh: TrueCadSolidBlockBuilder.GeneratedMesh? = null

    private var shouldClear = false

    // 旋转位姿参数
    var rotX = 30f
    var rotY = 40f
    var zoomScale = 1.0f
    var autoRotate = false

    // 目标姿态角度（来自视觉位姿解算）
    var targetRotY = 40f
    var targetRotX = 30f

    private val modelView = FloatArray(16)
    private val modelViewProj = FloatArray(16)
    private val normalMatrix = FloatArray(9)
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)

    private var viewportWidth = 500
    private var viewportHeight = 500

    fun updateGeneratedMesh(mesh: TrueCadSolidBlockBuilder.GeneratedMesh) {
        pendingCadMesh = mesh
        shouldClear = false
    }

    fun clearMesh() {
        shouldClear = true
        pendingCadMesh = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.06f, 0.07f, 0.10f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        val vertCode = context.assets.open("shaders/wood_block.vert").bufferedReader().use { it.readText() }
        val fragCode = context.assets.open("shaders/wood_block.frag").bufferedReader().use { it.readText() }
        program = ShaderUtil.createProgram(vertCode, fragCode)

        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        normalAttrib = GLES20.glGetAttribLocation(program, "a_Normal")

        mvpUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        mvUniform = GLES20.glGetUniformLocation(program, "u_ModelView")
        normalMatrixUniform = GLES20.glGetUniformLocation(program, "u_NormalMatrix")
        lightDirUniform = GLES20.glGetUniformLocation(program, "u_LightDir")
        isWireframeUniform = GLES20.glGetUniformLocation(program, "u_IsWireframe")
        useVertexColorUniform = GLES20.glGetUniformLocation(program, "u_UseVertexColor")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (shouldClear) {
            currentCadMesh = null
            shouldClear = false
            return
        }

        pendingCadMesh?.let {
            currentCadMesh = it
            pendingCadMesh = null
        }

        if (program == -1) return

        // 姿态平滑跟踪
        if (!autoRotate) {
            rotY += (targetRotY - rotY) * 0.25f
            rotX += (targetRotX - rotX) * 0.25f
        } else {
            rotY = (rotY + 0.6f) % 360f
        }

        GLES20.glUseProgram(program)

        // 观察与投影矩阵
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 0.30f / zoomScale, 0f, 0f, 0f, 0f, 1f, 0f)
        val aspect = if (viewportHeight > 0) viewportWidth.toFloat() / viewportHeight.toFloat() else 1f
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, 0.04f, 3.0f)

        // 模型空间变换 (5cm 尺度)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotY, 0f, 1f, 0f)
        Matrix.scaleM(modelMatrix, 0, 0.05f, 0.05f, 0.05f)

        Matrix.multiplyMM(modelView, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProj, 0, projMatrix, 0, modelView, 0)

        normalMatrix[0] = modelView[0]; normalMatrix[1] = modelView[1]; normalMatrix[2] = modelView[2]
        normalMatrix[3] = modelView[4]; normalMatrix[4] = modelView[5]; normalMatrix[5] = modelView[6]
        normalMatrix[6] = modelView[8]; normalMatrix[7] = modelView[9]; normalMatrix[8] = modelView[10]

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, modelViewProj, 0)
        GLES20.glUniformMatrix4fv(mvUniform, 1, false, modelView, 0)
        GLES20.glUniformMatrix3fv(normalMatrixUniform, 1, false, normalMatrix, 0)
        GLES20.glUniform3f(lightDirUniform, 0.5f, 0.8f, 0.5f)

        renderCadMesh()
    }

    private fun renderCadMesh() {
        val mesh = currentCadMesh ?: return
        val stride = 6 * 4 // pos:3, normal:3
        GLES20.glUniform1i(useVertexColorUniform, 0)

        mesh.vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, stride, mesh.vertexBuffer)

        mesh.vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(normalAttrib)
        GLES20.glVertexAttribPointer(normalAttrib, 3, GLES20.GL_FLOAT, false, stride, mesh.vertexBuffer)

        // 实体通道
        GLES20.glUniform1i(isWireframeUniform, 0)
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(2.0f, 4.0f)
        mesh.triangleIndexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.triangleCount * 3, GLES20.GL_UNSIGNED_SHORT, mesh.triangleIndexBuffer)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)

        // 线框通道
        GLES20.glUniform1i(isWireframeUniform, 1)
        GLES20.glLineWidth(2.5f)
        GLES20.glDepthMask(false)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        mesh.lineIndexBuffer.position(0)
        GLES20.glDrawElements(GLES20.GL_LINES, mesh.lineSegmentCount * 2, GLES20.GL_UNSIGNED_SHORT, mesh.lineIndexBuffer)
        GLES20.glDepthMask(true)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(normalAttrib)
    }
}
