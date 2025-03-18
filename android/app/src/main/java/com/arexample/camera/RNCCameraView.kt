package com.arexample.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import com.facebook.react.uimanager.ThemedReactContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * React Native 카메라 뷰 컴포넌트
 * 
 * React Native에서 사용할 수 있는 카메라 뷰 컴포넌트입니다.
 * Android의 TextureView를 확장하여 카메라 프리뷰를 표시합니다.
 * SurfaceTextureListener를 구현하여 텍스처 표면 상태 변경을 처리합니다.
 *
 * 사용 예시:
 * ```kotlin
 * // React Native ViewManager에서 사용
 * val cameraView = RNCCameraView(themedReactContext)
 * ```
 */
class RNCCameraView : TextureView, TextureView.SurfaceTextureListener {
    
    private val cameraManager: CameraManagerModule = CameraManagerModule.getInstance()
    private val tag = "RNCCameraView"
    
    // 프레임 카운터와 비트맵 저장 관련 변수
    private var frameCounter = 0
    private val saveFrameInterval = 3
    private var saveImagesEnabled = true
    private var context: ThemedReactContext? = null
    
    /**
     * React Native 컨텍스트를 사용하여 뷰를 초기화합니다.
     * 
     * @param context React Native의 ThemedReactContext
     */
    constructor(context: ThemedReactContext) : super(context) {
        this.context = context
        setupView()
    }
    
    /**
     * XML 레이아웃에서 뷰를 생성할 때 사용하는 생성자
     * 
     * @param context 컨텍스트
     * @param attrs 속성 집합
     */
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        if (context is ThemedReactContext) {
            this.context = context
            setupView()
        } else {
            Log.e(tag, "올바른 React 컨텍스트가 전달되지 않았습니다")
        }
    }
    
    /**
     * 뷰를 초기화합니다.
     * 
     * SurfaceTextureListener를 설정합니다.
     */
    private fun setupView() {
        surfaceTextureListener = this
        // 이미지 저장 디렉토리 생성
        createImageDirectory()
    }
    
    /**
     * 텍스처 표면이 사용 가능해지면 호출됩니다.
     * 
     * 이 시점에서 카메라 프리뷰를 설정합니다.
     * 
     * @param surface 사용 가능한 SurfaceTexture
     * @param width 표면의 너비
     * @param height 표면의 높이
     */
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        cameraManager.setupCamera(this)
    }
    
    /**
     * 텍스처 표면의 크기가 변경되면 호출됩니다.
     * 
     * @param surface SurfaceTexture
     * @param width 새 너비
     * @param height 새 높이
     */
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // 필요한 경우 카메라 미리보기 크기 조정 처리
    }
    
    /**
     * 텍스처 표면이 파괴될 때 호출됩니다.
     * 
     * 카메라를 중지하고 리소스를 정리합니다.
     * 
     * @param surface 파괴될 SurfaceTexture
     * @return 호출자가 SurfaceTexture를 파괴해야 하는지 여부
     */
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        cameraManager.stopCamera()
        return true
    }
    
    /**
     * 텍스처 표면이 업데이트될 때 호출됩니다.
     * 
     * 3프레임마다 카메라 프레임을 비트맵으로 저장합니다.
     * 
     * @param surface 업데이트된 SurfaceTexture
     */
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (saveImagesEnabled) {
            frameCounter++
            
            if (frameCounter % saveFrameInterval == 0) {
                // 현재 텍스처 뷰를 비트맵으로 캡처
                val bitmap = bitmap
                if (bitmap != null) {
                    saveImageToStorage(bitmap)
                }
            }
        }
    }
    
    /**
     * 이미지를 저장할 디렉토리를 생성합니다.
     */
    private fun createImageDirectory() {
        val directory = getImageDirectory()
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }
    
    /**
     * 이미지를 저장할 디렉토리를 반환합니다.
     * 앱 전용 외부 저장소의 Pictures 디렉토리를 사용합니다.
     * 
     * @return 이미지 저장 디렉토리
     */
    private fun getImageDirectory(): File {
        // 앱 전용 외부 저장소 사용: /storage/emulated/0/Android/data/com.arexample/files/Pictures/
        val ctx = context ?: throw IllegalStateException("컨텍스트가 null입니다")
        val storageDir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return storageDir ?: throw IllegalStateException("외부 저장소를 사용할 수 없습니다")
    }
    
    /**
     * 비트맵 이미지를 저장소에 저장합니다.
     * 저장된 이미지는 RGB888 형식입니다.
     * 
     * @param bitmap 저장할 비트맵 이미지
     */
    private fun saveImageToStorage(bitmap: Bitmap) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val imageFileName = "AR_IMAGE_$timeStamp.jpg"
            val directory = getImageDirectory()
            val imageFile = File(directory, imageFileName)
            
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            
            Log.d(tag, "이미지가 저장되었습니다: ${imageFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "이미지 저장 오류: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 비트맵 이미지 저장 활성화 여부를 설정합니다.
     * 
     * @param enabled 활성화 여부
     */
    fun setSaveImagesEnabled(enabled: Boolean) {
        saveImagesEnabled = enabled
    }
    
    /**
     * 뷰가 창에서 분리될 때 호출됩니다.
     * 
     * 카메라 리소스를 정리합니다.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cameraManager.stopCamera()
    }
} 