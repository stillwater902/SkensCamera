package com.skenscamera.app.gles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix as AndroidMatrix
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class TextureRender(private val context: Context) {

    private val triangleVerticesData = floatArrayOf(
        // X, Y, Z, U, V
        -1.0f, -1.0f, 0f, 0f, 0f,
         1.0f, -1.0f, 0f, 1f, 0f,
        -1.0f,  1.0f, 0f, 0f, 1f,
         1.0f,  1.0f, 0f, 1f, 1f
    )

    private val triangleVertices: FloatBuffer = ByteBuffer
        .allocateDirect(triangleVerticesData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(triangleVerticesData)

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    private var program = 0
    var textureId = -1
        private set

    private var lutTextureId = -1

    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var muLutTextureHandle = 0
    private var muLutSizeHandle = 0

    @Volatile
    var captureNextFrame = false
    var onPhotoCapturedListener: ((Bitmap) -> Unit)? = null

    private var viewportWidth = 0
    private var viewportHeight = 0

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        uniform sampler3D uLutTexture;
        uniform float uLutSize;
        
        void main() {
            vec4 textureColor = texture2D(sTexture, vTextureCoord);
            
            vec3 scale = vec3((uLutSize - 1.0) / uLutSize);
            vec3 offset = vec3(1.0 / (2.0 * uLutSize));
            vec3 lutCoord = textureColor.rgb * scale + offset;
            
            vec4 newColor = texture3D(uLutTexture, lutCoord);
            gl_FragColor = vec4(newColor.rgb, textureColor.a);
        }
    """.trimIndent()

    init {
        triangleVertices.position(0)
        Matrix.setIdentityM(stMatrix, 0)
    }

    fun surfaceCreated() {
        program = createProgram(vertexShaderCode, fragmentShaderCode)
        if (program == 0) return

        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        muLutTextureHandle = GLES20.glGetUniformLocation(program, "uLutTexture")
        muLutSizeHandle = GLES20.glGetUniformLocation(program, "uLutSize")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())

        loadLutTexture()
    }

    fun updateViewport(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    private fun loadLutTexture() {
        val lutData = CubeParser.parseCubeFile(context, "luts/sample.cube") ?: return
        val lutTextures = IntArray(1)
        GLES30.glGenTextures(1, lutTextures, 0)
        lutTextureId = lutTextures[0]

        val buffer = FloatBuffer.wrap(lutData.data)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            lutData.size, lutData.size, lutData.size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buffer
        )
    }

    fun drawFrame(st: SurfaceTexture) {
        st.getTransformMatrix(stMatrix)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        if (lutTextureId != -1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES20.glUniform1i(muLutTextureHandle, 1)
            GLES20.glUniform1f(muLutSizeHandle, 2.0f)
        }

        triangleVertices.position(0)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 20, triangleVertices)
        GLES20.glEnableVertexAttribArray(maPositionHandle)

        triangleVertices.position(3)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 20, triangleVertices)
        GLES20.glEnableVertexAttribArray(maTextureHandle)

        Matrix.setIdentityM(mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, stMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (captureNextFrame) {
            captureNextFrame = false
            readAndSaveFrame()
        }
    }

    private fun readAndSaveFrame() {
        if (viewportWidth == 0 || viewportHeight == 0) return

        val buf = ByteBuffer.allocateDirect(viewportWidth * viewportHeight * 4)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        GLES20.glReadPixels(0, 0, viewportWidth, viewportHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()

        val bitmap = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buf)

        // Balik bitmap secara vertikal karena koordinat Y OpenGL terbalik
        val matrix = AndroidMatrix().apply {
            postScale(1f, -1f, viewportWidth / 2f, viewportHeight / 2f)
        }
        val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, viewportWidth, viewportHeight, matrix, true)

        onPhotoCapturedListener?.invoke(flippedBitmap)
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) return 0

        var program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, pixelShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }
        return program
    }
}
