package com.arexample.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import com.facebook.react.uimanager.ThemedReactContext

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
class RNCCameraView : FrameLayout, TextureView.SurfaceTextureListener, FrameProcessorListener {
    
    private val cameraManager: CameraManagerModule = CameraManagerModule.getInstance()
    private val modelProcessor: ModelProcessor = ModelProcessor.getInstance()
    private val tag = "RNCCameraView"
    
    // UI 컴포넌트
    private val textureView: TextureView
    private val overlayImageView: ImageView
    
    // AI 처리 관련 변수
    private var isProcessingEnabled = false
    
    /**
     * React Native 컨텍스트를 사용하여 뷰를 초기화합니다.
     * 
     * @param context React Native의 ThemedReactContext
     */
    constructor(context: ThemedReactContext) : super(context) {
        // TextureView 생성 및 설정
        textureView = TextureView(context)
        textureView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        
        // 오버레이 ImageView 생성 및 설정
        overlayImageView = ImageView(context)
        overlayImageView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        
        // FrameLayout에 뷰 추가
        addView(textureView)
        addView(overlayImageView)
        
        setupView()
        tryLoadModel(context)
    }
    
    /**
     * XML 레이아웃에서 뷰를 생성할 때 사용하는 생성자
     * 
     * @param context 컨텍스트
     * @param attrs 속성 집합
     */
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        textureView = TextureView(context)
        textureView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        
        overlayImageView = ImageView(context)
        overlayImageView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        
        addView(textureView)
        addView(overlayImageView)
        
        if (context is ThemedReactContext) {
            setupView()
            tryLoadModel(context)
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
        textureView.surfaceTextureListener = this
    }
    
    /**
     * 모델을 로드합니다.
     * 
     * 모델 로드가 실패해도 카메라는 계속 작동합니다.
     */
    private fun tryLoadModel(context: Context) {
        try {
            // 모델 파일 경로 (assets 폴더 내)
            val modelPath = "model.tflite"
            
            // 백그라운드에서 모델 로드 시도
            Thread {
                try {
                    val success = modelProcessor.loadModel(context, modelPath)
                    if (success) {
                        // 모델 로드 성공 시 실시간 처리 활성화
                        isProcessingEnabled = true
                        post {
                            // UI 스레드에서 실행
                            cameraManager.setRealTimeProcessing(true, this)
                        }
                        Log.d(tag, "모델 로드 성공 및 처리 활성화")
                    } else {
                        Log.e(tag, "모델 로드 실패")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "모델 로드 중 오류: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(tag, "모델 로드 시도 중 오류: ${e.message}")
        }
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
        cameraManager.setupCamera(textureView)
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
     * @param surface 업데이트된 SurfaceTexture
     */
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // 필요한 경우 카메라 프레임 업데이트 처리
    }
    
    /**
     * 뷰가 창에서 분리될 때 호출됩니다.
     * 
     * 카메라 리소스를 정리합니다.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cameraManager.stopCamera()
        modelProcessor.close()
    }
    
    /**
     * 프레임 처리 리스너 구현 (FrameProcessorListener 인터페이스)
     * 
     * 이 메서드는 카메라에서 새 프레임이 사용 가능할 때 호출됩니다.
     * 
     * @param bitmap 처리할 비트맵 이미지
     * @return 처리 결과 비트맵 (오버레이용)
     */
    override fun onFrameAvailable(bitmap: Bitmap): Bitmap? {
        if (!isProcessingEnabled) {
            return null
        }
        
        try {
            // 모델 프로세서를 통해 이미지 처리
            val resultBitmap = modelProcessor.processImage(bitmap)
            
            // 결과가 있으면 UI 스레드에서 오버레이 표시
            resultBitmap?.let { result ->
                post {
                    try {
                        overlayImageView.setImageBitmap(result)
                    } catch (e: Exception) {
                        Log.e(tag, "오버레이 표시 오류: ${e.message}")
                    }
                }
            }
            
            return null // 반환 값은 사용하지 않음
        } catch (e: Exception) {
            Log.e(tag, "프레임 처리 오류: ${e.message}")
            return null
        }
    }
    
    /**
     * 이미지 처리 활성화/비활성화 설정
     * 
     * @param enabled 활성화 여부
     */
    fun setProcessingEnabled(enabled: Boolean) {
        isProcessingEnabled = enabled
        cameraManager.setRealTimeProcessing(enabled, if (enabled) this else null)
        
        // 처리 비활성화 시 오버레이 이미지 제거
        if (!enabled) {
            post { overlayImageView.setImageBitmap(null) }
        }
    }
} 