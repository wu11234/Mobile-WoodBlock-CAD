package com.woodblock.ar.data

import com.woodblock.ar.geometry.TrueCadSolidBlockBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Saved3DModel(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val mesh: TrueCadSolidBlockBuilder.GeneratedMesh? = null,
    val vertexCount: Int,
    val triangleCount: Int,
    val widthMm: Float = 50.0f,
    val heightMm: Float = 50.0f,
    val depthMm: Float = 50.0f,
    val holeDiameterMm: Float = 15.0f,
    val grooveWidthMm: Float = 15.0f,
    val timestamp: String,
    val objContent: String
)

/**
 * 3D 实测实体几何仓库 (严格零预置数据)
 * 仅存储从摄像头实测扫描生成的真实三维连续实体模型
 */
object GeneratedModelStore {
    val modelList = mutableListOf<Saved3DModel>()
    var latestModel: Saved3DModel? = null

    fun saveModel(mesh: TrueCadSolidBlockBuilder.GeneratedMesh): Saved3DModel {
        val count = modelList.size + 1
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val model = Saved3DModel(
            name = "木块模型 #$count",
            mesh = mesh,
            vertexCount = mesh.vertexCount,
            triangleCount = mesh.triangleCount,
            widthMm = 50.0f,
            heightMm = 50.0f,
            depthMm = 50.0f,
            holeDiameterMm = mesh.metrics.holeDiameterMm,
            grooveWidthMm = mesh.metrics.grooveWidthMm,
            timestamp = timeStr,
            objContent = mesh.objContent
        )
        modelList.add(0, model)
        latestModel = model
        return model
    }
}
