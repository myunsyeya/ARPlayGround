package com.arexample.camera

import android.Manifest
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 실시간 프레임 처리 리스너 인터페이스
 * 
 * 카메라에서 캡처된 프레임을 처리하기 위한 콜백 인터페이스입니다.
 */
interface FrameProcessorListener {
    /**
     * 프레임이 사용 가능할 때 호출됩니다.
     * 
     * @param bitmap 처리할 비트맵 이미지
     * @return 처리 결과 비트맵 (오버레이용)
     */
    fun onFrameAvailable(bitmap: Bitmap): Bitmap?
}

/**
 * 카메라 관리 모듈
 * 
 * 안드로이드 Camera2 API를 사용하여 카메라 기능을 제공하는 싱글톤 클래스입니다.
 * 이 클래스는 카메라 미리보기 설정, 카메라 세션 관리, 백그라운드 스레드 처리 등의 기능을 담당합니다.
 * 
 * 사용 예시:
 * ```kotlin
 * // 싱글톤 인스턴스 가져오기
 * val cameraManager = CameraManagerModule.getInstance()
 * 
 * // React 컨텍스트 설정
 * cameraManager.setReactContext(reactContext)
 * 
 * // TextureView에 카메라 미리보기 설정
 * cameraManager.setupCamera(textureView)
 * 
 * // 카메라 정지
 * cameraManager.stopCamera()
 * ```
 */
class CameraManagerModule private constructor() : PermissionListener {
    companion object {
        private const val TAG = "CameraManagerModule"
        private var instance: CameraManagerModule? = null
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private val CAMERA_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val MODEL_INPUT_SIZE = 800 // 모델 입력 크기

        /**
         * 싱글톤 인스턴스를 반환합니다.
         * 
         * @return CameraManagerModule 인스턴스
         */
        @JvmStatic
        @Synchronized
        fun getInstance(): CameraManagerModule {
            return instance ?: CameraManagerModule().also { instance = it }
        }
        
        // 네이티브 라이브러리 로딩
        init {
            try {
                System.loadLibrary("yuv-to-rgb")
                Log.i(TAG, "YUV to RGB 네이티브 라이브러리 로드 성공")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "네이티브 라이브러리 로드 실패: ${e.message}")
            }
        }
    }

    private var cameraDevice: CameraDevice? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var previewSize: Size? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var reactContext: ReactApplicationContext? = null
    private var textureView: TextureView? = null
    private var pendingTextureView: TextureView? = null
    
    // 프레임 처리 관련 변수
    private var imageReader: ImageReader? = null
    private var processingEnabled = false
    private var frameProcessorListener: FrameProcessorListener? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private var processingInterval = 3 // 몇 프레임마다 처리할지 설정
    private var frameCount = 0

    // YUV -> RGB 변환을 위한 계수들
    private val yuvToRgbCoefficients = arrayOf(
        floatArrayOf(1.0f, 0.0f, 1.370705f),      // R 계수 (y, u, v)
        floatArrayOf(1.0f, -0.337633f, -0.698001f), // G 계수 (y, u, v)
        floatArrayOf(1.0f, 1.732446f, 0.0f)        // B 계수 (y, u, v)
    )
    
    // 미리 계산된 색상 오프셋 (성능 최적화)
    private val uCoefficients = FloatArray(256)
    private val vCoefficients = FloatArray(256)
    private val uvCoefficients = FloatArray(256 * 256)
    private var coefficientsInitialized = false

    /**
     * React Native 컨텍스트를 설정합니다.
     * 
     * @param context React Native 애플리케이션 컨텍스트
     */
    fun setReactContext(context: ReactApplicationContext) {
        reactContext = context
        cameraManager = context.getSystemService(ReactApplicationContext.CAMERA_SERVICE) as CameraManager
    }

    /**
     * 카메라 미리보기를 설정합니다.
     * 
     * TextureView에 카메라 미리보기를 설정하고 카메라를 초기화합니다.
     * 카메라 권한이 있어야 하며, TextureView가 사용 가능한 상태일 때 카메라를 엽니다.
     * 
     * @param textureView 카메라 미리보기를 표시할 TextureView
     */
    fun setupCamera(textureView: TextureView) {
        this.textureView = textureView
        
        reactContext?.let { context ->
            if (hasCameraPermission(context)) {
                setupCameraInternal()
            } else {
                // 권한이 없는 경우 요청
                Log.d(TAG, "카메라 권한 요청 필요")
                pendingTextureView = textureView
                requestCameraPermission()
            }
        } ?: run {
            Log.e(TAG, "React 컨텍스트가 초기화되지 않았습니다")
        }
    }
    
    /**
     * 카메라 권한이 있는지 확인합니다.
     */
    private fun hasCameraPermission(context: ReactApplicationContext): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 카메라 권한을 요청합니다.
     */
    private fun requestCameraPermission() {
        val context = reactContext ?: return
        
        try {
            val activity = context.currentActivity as? PermissionAwareActivity
            if (activity != null) {
                Log.d(TAG, "카메라 권한 요청 시작")
                activity.requestPermissions(CAMERA_PERMISSIONS, REQUEST_CAMERA_PERMISSION, this)
            } else {
                Log.e(TAG, "권한 요청을 위한 Activity를 찾을 수 없습니다")
            }
        } catch (e: Exception) {
            Log.e(TAG, "권한 요청 중 오류: ${e.message}")
        }
    }
    
    /**
     * 권한 요청 결과를 처리합니다.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "카메라 권한이 허용되었습니다")
                // 권한이 허용된 후에 카메라 설정 진행
                pendingTextureView?.let {
                    textureView = it
                    setupCameraInternal()
                    pendingTextureView = null
                }
            } else {
                Log.e(TAG, "카메라 권한이 거부되었습니다")
            }
            return true
        }
        return false
    }
    
    /**
     * 내부적으로 카메라 설정을 처리합니다. 권한이 확인된 후 호출됩니다.
     */
    private fun setupCameraInternal() {
        if (cameraManager == null) {
            Log.e(TAG, "카메라 매니저가 초기화되지 않았습니다")
            return
        }
        
        startBackgroundThread()
        setupCameraId()
        
        if (textureView?.isAvailable == true) {
            openCamera()
        }
    }

    /**
     * 카메라 ID를 설정합니다.
     * 
     * 후면 카메라를 기본으로 사용하며, 적절한 미리보기 크기를 선택합니다.
     */
    private fun setupCameraId() {
        try {
            cameraManager?.cameraIdList?.forEach { id ->
                val characteristics = cameraManager?.getCameraCharacteristics(id) ?: return@forEach
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: return@forEach
                
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@forEach
                    
                    previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java))
                    
                    // ImageReader 설정 (프레임 캡처용)
                    val largest = chooseOptimalSize(map.getOutputSizes(ImageFormat.YUV_420_888))
                    imageReader = ImageReader.newInstance(
                        largest.width, 
                        largest.height, 
                        ImageFormat.YUV_420_888, 
                        2
                    ).apply {
                        setOnImageAvailableListener(ImageReader.OnImageAvailableListener { reader ->
                            val image = reader.acquireLatestImage()
                            if (image != null) {
                                if (processingEnabled && frameCount % processingInterval == 0) {
                                    processImage(image)
                                } else {
                                    image.close()
                                }
                                frameCount++
                                if (frameCount > 1000) frameCount = 0 // 카운터 리셋
                            }
                        }, backgroundHandler)
                    }
                    
                    cameraId = id
                    return
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "카메라 ID 설정 오류: ${e.message}")
        }
    }

    /**
     * 최적의 미리보기 크기를 선택합니다.
     * 
     * 이 구현에서는 가장 큰 해상도를 선택합니다.
     * 
     * @param choices 사용 가능한 미리보기 크기 배열
     * @return 선택된 최적의 미리보기 크기
     */
    private fun chooseOptimalSize(choices: Array<Size>): Size {
        // 코틀린 스타일로 변경: 함수형 프로그래밍 활용
        return choices.maxByOrNull { it.width * it.height } ?: choices[0]
    }

    /**
     * 카메라를 엽니다.
     * 
     * 카메라 권한이 있는지 확인하고, 카메라 ID가 설정되어 있다면
     * 카메라를 열고 미리보기 세션을 생성합니다.
     */
    private fun openCamera() {
        val context = reactContext ?: return
        val camId = cameraId ?: return
        val handler = backgroundHandler ?: return
        
        try {
            if (ActivityCompat.checkSelfPermission(
                    context, 
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            
            cameraManager?.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "카메라 열기 오류: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "예기치 않은 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 카메라 미리보기 세션을 생성합니다.
     * 
     * TextureView의 SurfaceTexture를 사용하여 카메라 미리보기를 설정합니다.
     * 자동 초점 모드를 설정하고 반복적인 요청을 시작합니다.
     */
    private fun createCameraPreviewSession() {
        val device = cameraDevice ?: return
        val view = textureView ?: return
        val size = previewSize ?: return
        
        try {
            val texture = view.surfaceTexture ?: return
            
            texture.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(texture)
            
            // 출력 대상 리스트 생성
            val targets = ArrayList<Surface>().apply {
                add(surface) // 미리보기 대상
                imageReader?.surface?.let { add(it) } // 이미지 처리 대상
            }

            // 미리보기 요청 빌더 생성
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)
            
            // 이미지 리더 대상 추가
            imageReader?.surface?.let { requestBuilder.addTarget(it) }
            
            captureRequestBuilder = requestBuilder

            device.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            return
                        }

                        cameraCaptureSession = session
                        try {
                            captureRequestBuilder?.apply {
                                set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                
                                backgroundHandler?.let { handler ->
                                    session.setRepeatingRequest(
                                        build(),
                                        null,
                                        handler
                                    )
                                }
                            }
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "카메라 세션 설정 오류: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "카메라 세션 구성 실패")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "카메라 미리보기 세션 생성 오류: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "예기치 않은 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 백그라운드 스레드를 시작합니다.
     * 
     * 카메라 작업을 위한 별도의 스레드를 생성하고 시작합니다.
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { thread ->
            thread.start()
            backgroundHandler = Handler(thread.looper)
        }
    }

    /**
     * 백그라운드 스레드를 중지합니다.
     * 
     * 백그라운드 스레드를 안전하게 종료합니다.
     */
    private fun stopBackgroundThread() {
        backgroundThread?.let { thread ->
            thread.quitSafely()
            try {
                thread.join()
                backgroundThread = null
                backgroundHandler = null
            } catch (e: InterruptedException) {
                Log.e(TAG, "백그라운드 스레드 종료 오류: ${e.message}")
            }
        }
    }

    /**
     * 카메라를 중지합니다.
     * 
     * 카메라 세션과 장치를 닫고, 백그라운드 스레드를 종료합니다.
     * 이 메서드는 카메라 사용이 끝날 때 반드시 호출해야 합니다.
     */
    fun stopCamera() {
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        
        cameraDevice?.close()
        cameraDevice = null
        
        stopBackgroundThread()
    }

    /**
     * 실시간 프레임 처리를 활성화하거나 비활성화합니다.
     * 
     * @param enabled 활성화 여부
     * @param listener 프레임 처리 리스너
     */
    fun setRealTimeProcessing(enabled: Boolean, listener: FrameProcessorListener? = null) {
        processingEnabled = enabled
        frameProcessorListener = listener
    }
    
    /**
     * 프레임 처리 간격을 설정합니다.
     * 
     * @param interval 몇 프레임마다 처리할지 설정 (1은 모든 프레임 처리)
     */
    fun setProcessingInterval(interval: Int) {
        processingInterval = if (interval < 1) 1 else interval
    }

    /**
     * 카메라에서 캡처된 이미지를 처리합니다.
     * 
     * 이 메서드는 카메라에서 캡처한 YUV 이미지를 비트맵으로 변환하고,
     * 등록된 프레임 처리 리스너를 통해 AI 모델 처리를 수행합니다.
     * 
     * 처리 흐름:
     * 1. YUV 이미지를 RGB 비트맵으로 변환 (yuv420ToBitmap)
     * 2. 모델 입력 크기에 맞게 비트맵 리사이징 (resizeBitmap)
     * 3. 리스너가 등록된 경우 비동기적으로 프레임 처리 요청
     * 4. 결과 처리 및 메모리 리소스 해제
     * 
     * 메모리 관리:
     * - 모든 비트맵은 사용 후 명시적으로 recycle() 호출하여 메모리 누수 방지
     * - 이미지 객체도 close() 호출하여 적절히 해제
     * 
     * 시간복잡도: O(W*H) [변환 + 리사이징 + 모델 처리]
     * 공간복잡도: O(W*H) [원본 비트맵 + 리사이징된 비트맵]
     * 
     * @param image 카메라에서 캡처된 이미지 (YUV_420_888 포맷)
     */
    private fun processImage(image: Image) {
        try {
            val bitmap = yuv420ToBitmap(image)
            // 처리를 위해 이미지를 리사이징합니다 (모델 입력 크기에 맞춤)
            val resizedBitmap = resizeBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
            
            // 리스너가 등록되어 있으면 이미지 처리 수행
            frameProcessorListener?.let { listener ->
                try {
                    // 비동기적으로 프레임 처리
                    backgroundHandler?.post {
                        try {
                            // 프레임 처리 결과를 얻습니다
                            val resultBitmap = listener.onFrameAvailable(resizedBitmap)
                            
                            // 결과가 있으면 UI 스레드에서 오버레이 표시 등의 작업 수행 가능
                            // 여기에서는 결과 처리를 위한 콜백을 추가할 수 있음
                        } catch (e: Exception) {
                            Log.e(TAG, "프레임 처리 오류: ${e.message}")
                        } finally {
                            resizedBitmap.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "프레임 처리 요청 오류: ${e.message}")
                    resizedBitmap.recycle()
                }
            } ?: run {
                // 리스너가 없으면 비트맵 리소스 해제
                resizedBitmap.recycle()
            }
            
            // 원본 비트맵 리소스 해제
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "이미지 처리 오류: ${e.message}")
        } finally {
            // 이미지 리소스 해제
            image.close()
        }
    }
    
    /**
     * YUV에서 RGB로 이미지를 변환하는 네이티브 메소드
     *
     * @param yuv420sp YUV 형식의 이미지 데이터 바이트 배열
     * @param width 이미지 너비
     * @param height 이미지 높이
     * @param outBitmap 결과를 저장할 출력 비트맵
     * @return 성공 시 0, 실패 시 오류 코드
     */
    private external fun convertYuvToRgbNative(yuv420sp: ByteArray, width: Int, height: Int, outBitmap: Bitmap): Int

    /**
     * YUV_420_888 이미지를 RGBA 비트맵으로 변환
     *
     * @param image YUV_420_888 형식의 이미지
     * @return RGBA 비트맵
     */
    private fun yuv420ToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        
        // 출력 비트맵 생성
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // YUV 평면 가져오기
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        
        // YUV 데이터 복사
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        // Y 데이터 복사
        yBuffer.get(nv21, 0, ySize)
        
        // UV 데이터 복사 (NV21 형식으로)
        val uvPos = ySize
        if (vBuffer.remaining() > 0) {
            vBuffer.get(nv21, uvPos, vBuffer.remaining())
        }
        if (uBuffer.remaining() > 0) {
            uBuffer.get(nv21, uvPos + vBuffer.remaining(), uBuffer.remaining())
        }
        
        // 네이티브 메소드 호출하여 YUV에서 RGB로 변환
        val result = convertYuvToRgbNative(nv21, width, height, bitmap)
        if (result != 0) {
            Log.e(TAG, "YUV에서 RGB로 변환 실패: 오류 코드 $result")
        }
        
        return bitmap
    }

    /**
     * 비트맵을 지정된 크기로 리사이징합니다.
     * 
     * 이 메서드는 입력 이미지의 비율을 유지하면서 목표 크기로 변환합니다.
     * 안드로이드의 Matrix 클래스를 사용하여 이미지 변환을 수행합니다.
     * 
     * 알고리즘:
     * 1. 입력 비트맵과 목표 크기 기반으로 스케일 계수 계산
     * 2. Matrix 변환을 사용하여 이미지 리사이징
     * 
     * 시간복잡도: O(W₁*H₁) [W₁,H₁은 원본 이미지 크기]
     * 공간복잡도: O(W₂*H₂) [W₂,H₂는 목표 이미지 크기]
     * 
     * 참고: Matrix 변환은 안드로이드 시스템 내부적으로 최적화되어 있습니다.
     * 
     * @param bitmap 원본 비트맵
     * @param width 목표 너비
     * @param height 목표 높이
     * @return 리사이징된 비트맵
     */
    private fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val matrix = Matrix()
        val scaleWidth = width.toFloat() / bitmap.width
        val scaleHeight = height.toFloat() / bitmap.height
        matrix.postScale(scaleWidth, scaleHeight)
        
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
    }

    /**
     * YUV -> RGB 변환을 위한 계수들을 초기화합니다.
     * 이 방식은 전체 LUT보다 메모리 사용량이 훨씬 적습니다.
     * 
     * 알고리즘 설명:
     * 1. 각 U값(0-255)에 대한 블루 채널 계수 사전 계산
     * 2. 각 V값(0-255)에 대한 레드 채널 계수 사전 계산
     * 3. 각 U,V 조합에 대한 그린 채널 계수 사전 계산
     * 
     * 이 방식은 전체 YUV 조합(256³)을 사전 계산하는 것보다 메모리를 크게 절약합니다.
     * - 전체 LUT: 256³ x 4bytes = 67MB
     * - 현재 방식: 256 + 256 + 256² = 66,048 항목 = 258KB
     * 
     * 시간복잡도: O(256²)
     * 공간복잡도: O(256²)
     * 
     * 참고: 이 초기화는 앱 실행 중 한 번만 수행됩니다.
     */
    private fun initYuvToRgbCoefficients() {
        if (coefficientsInitialized) return
        
        // U와 V에 대한 계수 미리 계산 (메모리 사용량 절약)
        for (u in 0 until 256) {
            val uValue = u - 128
            uCoefficients[u] = yuvToRgbCoefficients[2][1] * uValue // B에 대한 U 계수
        }
        
        for (v in 0 until 256) {
            val vValue = v - 128
            vCoefficients[v] = yuvToRgbCoefficients[0][2] * vValue // R에 대한 V 계수
        }
        
        // 복잡한 계산(G에 대한 U+V 계수) 미리 계산
        for (u in 0 until 256) {
            val uValue = u - 128
            for (v in 0 until 256) {
                val vValue = v - 128
                uvCoefficients[u * 256 + v] = 
                    yuvToRgbCoefficients[1][1] * uValue + 
                    yuvToRgbCoefficients[1][2] * vValue
            }
        }
        
        coefficientsInitialized = true
    }
} 