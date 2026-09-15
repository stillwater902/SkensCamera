package com.skenscamera.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaScannerConnection
import android.opengl.EGL14
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.skenscamera.app.gles.TextureRender
import com.skenscamera.app.gles.VideoEncoder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity() {

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
            if (map.values.all { it }) {
                setupCameraUI()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            setupCameraUI()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun setupCameraUI() {
        setContent {
            OpenGLCameraScreen()
        }
    }
}

@Composable
fun OpenGLCameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var isRecording by remember { mutableStateOf(false) }
    var videoEncoder by remember { mutableStateOf<VideoEncoder?>(null) }
    var textureRenderInstance by remember { mutableStateOf<TextureRender?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(object : GLSurfaceView.Renderer {
                        private val textureRender = TextureRender(ctx).also {
                            textureRenderInstance = it
                            it.onPhotoCapturedListener = { bitmap ->
                                saveBitmapToGallery(ctx, bitmap)
                            }
                        }
                        private var surfaceTexture: SurfaceTexture? = null

                        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                            textureRender.surfaceCreated()
                            surfaceTexture = SurfaceTexture(textureRender.textureId)

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider { request ->
                                        val surface = Surface(surfaceTexture)
                                        request.provideSurface(surface, ContextCompat.getMainExecutor(ctx)) {
                                            surface.release()
                                        }
                                    }
                                }

                                val cameraSelector = CameraSelector.Builder()
                                    .requireLensFacing(lensFacing)
                                    .build()

                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            surfaceTexture?.setOnFrameAvailableListener {
                                requestRender()
                            }
                        }

                        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                            android.opengl.GLES20.glViewport(0, 0, width, height)
                            textureRender.updateViewport(width, height)
                        }

                        override fun onDrawFrame(gl: GL10?) {
                            surfaceTexture?.updateTexImage()
                            surfaceTexture?.let { st ->
                                textureRender.drawFrame(st)

                                videoEncoder?.let { encoder ->
                                    encoder.makeCurrent()
                                    textureRender.drawFrame(st)
                                    encoder.setPresentationTime(System.nanoTime())
                                    encoder.swapBuffers()
                                    encoder.drainEncoder(false)
                                }
                            }
                        }
                    })
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Indikator REC
        if (isRecording) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .background(Color.Red.copy(alpha = 0.8f), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color.White, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "REC (LUT ENABLED)", color = Color.White, fontSize = 14.sp)
            }
        }

        // Floating Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Photo Capture Button (Hanya jika sedang tidak merekam video)
            IconButton(
                onClick = {
                    textureRenderInstance?.captureNextFrame = true
                },
                enabled = !isRecording,
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.White.copy(alpha = 0.8f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Take Photo",
                    tint = Color.Black
                )
            }

            // Record Video Button
            IconButton(
                onClick = {
                    if (!isRecording) {
                        val videoFile = createVideoFile(context)
                        val encoder = VideoEncoder(videoFile, 1080, 1920).apply {
                            start(EGL14.eglGetCurrentContext())
                        }
                        videoEncoder = encoder
                        isRecording = true
                        Toast.makeText(context, "Mulai merekam video ber-LUT...", Toast.LENGTH_SHORT).show()
                    } else {
                        videoEncoder?.stop()
                        videoEncoder = null
                        isRecording = false
                        Toast.makeText(context, "Video tersimpan di Galeri!", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .size(80.dp)
                    .background(if (isRecording) Color.Red else Color.White, CircleShape)
                    .border(4.dp, Color.Gray, CircleShape)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = "Record Video",
                    tint = if (isRecording) Color.White else Color.Red
                )
            }

            // Switch Camera
            IconButton(
                onClick = {
                    if (!isRecording) {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Camera",
                    tint = Color.White
                )
            }
        }
    }
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap) {
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SKENS_LUT_$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SkensCamera")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
        }
    } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val skensDir = File(dir, "SkensCamera").apply { mkdirs() }
        val file = File(skensDir, "SKENS_LUT_$name.jpg")
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    }

    ContextCompat.getMainExecutor(context).execute {
        Toast.makeText(context, "Foto ber-LUT berhasil disimpan ke Galeri!", Toast.LENGTH_SHORT).show()
    }
}

private fun createVideoFile(context: Context): File {
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
    val skensDir = File(dir, "SkensCamera").apply { mkdirs() }
    val file = File(skensDir, "SKENS_CLIP_$name.mp4")

    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    return file
}
