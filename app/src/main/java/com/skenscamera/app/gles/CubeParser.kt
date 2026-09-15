package com.skenscamera.app.gles

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

class CubeParser {
    data class Lut3DData(
        val size: Int,
        val data: FloatArray
    )

    companion object {
        fun parseCubeFile(context: Context, fileName: String): Lut3DData? {
            try {
                val inputStream = context.assets.open(fileName)
                val reader = BufferedReader(InputStreamReader(inputStream))
                var size = 0
                val values = mutableListOf<Float>()

                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        if (trimmed.startsWith("LUT_3D_SIZE")) {
                            size = trimmed.split("\\s+".toRegex())[1].toInt()
                        } else if (trimmed[0].isDigit() || trimmed[0] == '-') {
                            val parts = trimmed.split("\\s+".toRegex())
                            if (parts.size >= 3) {
                                values.add(parts[0].toFloat())
                                values.add(parts[1].toFloat())
                                values.add(parts[2].toFloat())
                            }
                        }
                    }
                }
                return Lut3DData(size, values.toFloatArray())
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
    }
}
