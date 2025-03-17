package com.arexample.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import com.facebook.react.bridge.ReactApplicationContext
import java.util.Collections

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
class CameraManagerModule private constructor() {
    companion object {
        private const val TAG = "CameraManagerModule"
        private var instance: CameraManagerModule? = null

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
            if (ActivityCompat.checkSelfPermission(
                    context, 
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "카메라 권한이 없습니다")
                return
            }
            
            if (cameraManager == null) {
                Log.e(TAG, "카메라 매니저가 초기화되지 않았습니다")
                return
            }
            
            startBackgroundThread()
            setupCameraId()
            
            if (textureView.isAvailable) {
                openCamera()
            }
        } ?: run {
            Log.e(TAG, "React 컨텍스트가 초기화되지 않았습니다")
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

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)
            captureRequestBuilder = requestBuilder

            device.createCaptureSession(
                listOf(surface),
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
} 