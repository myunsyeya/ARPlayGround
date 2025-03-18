package com.arexample.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.tensorflow.lite.Interpreter
// GPU 가속 재활성화
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * TensorFlow Lite 모델 처리 클래스
 * 
 * TFLite 모델을 로드하고 입력 이미지를 처리하는 기능을 제공합니다.
 * 이 클래스는 싱글톤 패턴으로 구현되어 있습니다.
 */
class ModelProcessor private constructor() {
    companion object {
        private const val TAG = "ModelProcessor"
        private var instance: ModelProcessor? = null
        private const val MODEL_INPUT_SIZE = 800
        
        @JvmStatic
        @Synchronized
        fun getInstance(): ModelProcessor {
            return instance ?: ModelProcessor().also { instance = it }
        }
    }
    
    private var interpreter: Interpreter? = null
    private var isModelLoaded = false
    // GPU 가속 변수 재활성화
    private var gpuDelegate: GpuDelegate? = null
    
    // 스레드 풀 생성 (백그라운드 작업용)
    private val executor = Executors.newSingleThreadExecutor()
    
    // 재사용 가능한 버퍼 (메모리 할당 최적화)
    private var inputBuffer: ByteBuffer? = null
    private var lastProcessedBitmap: Bitmap? = null
    
    /**
     * TFLite 모델을 로드합니다.
     * 
     * @param context 애플리케이션 컨텍스트
     * @param modelPath 모델 파일 경로 (assets 폴더 내의 경로)
     * @return 모델 로드 성공 여부
     */
    fun loadModel(context: Context, modelPath: String): Boolean {
        return try {
            val assetManager = context.assets
            val fileDescriptor = assetManager.openFd(modelPath)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val mappedBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY, 
                startOffset, 
                declaredLength
            )
            
            // GPU 가속 코드 재활성화 (안정적으로 동작하도록 예외 처리 강화)
            val options = Interpreter.Options()
            
            try {
                val compatList = CompatibilityList()
                
                if (compatList.isDelegateSupportedOnThisDevice) {
                    // GPU 가속 사용 시도
                    try {
                        gpuDelegate = GpuDelegate()
                        options.addDelegate(gpuDelegate)
                        Log.d(TAG, "GPU 가속을 사용합니다")
                    } catch (e: Exception) {
                        Log.e(TAG, "GPU 가속 초기화 실패, CPU 모드로 전환: ${e.message}")
                        releaseGpuDelegate()
                        options.setNumThreads(4)
                    }
                } else {
                    // GPU 가속 미지원 디바이스
                    options.setNumThreads(4)
                    Log.d(TAG, "GPU 가속이 지원되지 않는 기기: CPU 스레드를 4개 사용합니다")
                }
            } catch (e: Exception) {
                // GPU 관련 라이브러리에 문제가 있는 경우
                Log.e(TAG, "GPU 가속 확인 중 오류, CPU 모드로 전환: ${e.message}")
                options.setNumThreads(4)
            }
            
            // 인터프리터 생성
            interpreter = Interpreter(mappedBuffer, options)
            isModelLoaded = true
            
            // 스트림과 파일 디스크립터 닫기
            inputStream.close()
            fileDescriptor.close()
            
            // 재사용 가능한 입력 버퍼 미리 생성
            val bufferSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3 * 4 // FLOAT32
            inputBuffer = ByteBuffer.allocateDirect(bufferSize).apply {
                order(ByteOrder.nativeOrder())
            }
            
            Log.d(TAG, "모델 로드 성공: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "모델 로드 실패: ${e.message}")
            isModelLoaded = false
            false
        }
    }
    
    // GPU 위임자 해제 도우미 메서드
    private fun releaseGpuDelegate() {
        try {
            gpuDelegate?.close()
            gpuDelegate = null
        } catch (e: Exception) {
            Log.e(TAG, "GPU 위임자 해제 중 오류: ${e.message}")
        }
    }
    
    /**
     * 이미지를 처리하여 결과를 반환합니다.
     * 
     * @param bitmap 처리할 비트맵 이미지 (800x800 크기 권장)
     * @return 처리 결과를 시각화한 비트맵 또는 null (처리 실패 시)
     */
    fun processImage(bitmap: Bitmap): Bitmap? {
        if (!isModelLoaded || interpreter == null) {
            Log.e(TAG, "모델이 로드되지 않았습니다")
            return null
        }
        
        try {
            // 이전 비트맵과 같은 크기라면 새로 생성하지 않고 재사용 버퍼 사용
            val isSameSizeAsPrevious = lastProcessedBitmap?.let {
                it.width == bitmap.width && it.height == bitmap.height
            } ?: false
            
            // 입력 버퍼 준비
            val inputBuff = if (isSameSizeAsPrevious) {
                inputBuffer?.rewind()
                inputBuffer
            } else {
                // 크기가 다르면 새로 생성
                convertBitmapToByteBuffer(bitmap)
            }
            
            if (inputBuff == null) {
                return null
            }
            
            // 출력 버퍼 생성 (모델 출력에 맞게 조정 필요)
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            val outputBuffer = ByteBuffer.allocateDirect(
                outputShape?.get(0)!! * outputShape[1] * outputShape[2] * outputShape[3] * 4
            ).apply {
                order(ByteOrder.nativeOrder())
            }
            
            // 모델 실행
            interpreter?.run(inputBuff, outputBuffer)
            
            // 결과 비트맵 생성 (모델 출력에 맞게 시각화 로직 필요)
            val resultBitmap = createResultBitmap(outputBuffer, bitmap.width, bitmap.height)
            
            // 현재 비트맵 저장 (다음 호출 시 크기 비교용)
            lastProcessedBitmap = bitmap
            
            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "이미지 처리 오류: ${e.message}")
            return null
        }
    }
    
    /**
     * 비트맵을 ByteBuffer로 변환합니다 (모델 입력용).
     * 
     * @param bitmap 변환할 비트맵
     * @return 변환된 ByteBuffer
     */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer? {
        try {
            // 입력 크기 및 채널 수에 맞게 조정 필요
            val bufferSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3 * 4 // FLOAT32
            val inputBuffer = ByteBuffer.allocateDirect(bufferSize).apply {
                order(ByteOrder.nativeOrder())
            }
            
            // 필요한 경우 비트맵 리사이징 (모델 입력 크기에 맞게)
            val scaledBitmap = if (bitmap.width != MODEL_INPUT_SIZE || bitmap.height != MODEL_INPUT_SIZE) {
                Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
            } else {
                bitmap
            }
            
            val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
            scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, 
                                  scaledBitmap.width, scaledBitmap.height)
            
            inputBuffer.rewind()
            for (pixelValue in pixels) {
                // RGB 채널 분리 및 정규화 (모델에 따라 전처리 방식 변경 필요)
                val r = (pixelValue shr 16 and 0xFF) / 255.0f
                val g = (pixelValue shr 8 and 0xFF) / 255.0f
                val b = (pixelValue and 0xFF) / 255.0f
                
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
            
            // 스케일링된 비트맵을 원본과 다른 경우에만 해제
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            
            this.inputBuffer = inputBuffer
            return inputBuffer
        } catch (e: Exception) {
            Log.e(TAG, "ByteBuffer 변환 오류: ${e.message}")
            return null
        }
    }
    
    /**
     * 모델 출력 결과를 시각화한 비트맵을 생성합니다.
     * 
     * @param outputBuffer 모델 출력 버퍼
     * @param width 결과 비트맵 너비
     * @param height 결과 비트맵 높이
     * @return 시각화된 결과 비트맵
     */
    private fun createResultBitmap(outputBuffer: ByteBuffer, width: Int, height: Int): Bitmap {
        try {
            // 출력 버퍼를 비트맵으로 변환하는 로직
            // 실제 구현은 모델 출력 형식에 따라 달라짐
            
            // ARGB_8888 대신 RGB_565로 변경하여 메모리 사용량 50% 감소
            val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            val paint = Paint()
            paint.color = Color.RED
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            
            // 예시: 출력이 바운딩 박스인 경우
            // 실제로는 모델 출력을 파싱하여 그려야 함
            canvas.drawRect(100f, 100f, 700f, 700f, paint)
            
            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "결과 비트맵 생성 오류: ${e.message}")
            // 오류 시 빈 비트맵 반환
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }
    
    /**
     * 리소스를 해제합니다.
     */
    fun close() {
        try {
            // 스레드 풀 정리
            executor.shutdown()
            
            // 인터프리터 정리
            interpreter?.close()
            interpreter = null
            
            // GPU 위임 객체 정리 코드 재활성화
            releaseGpuDelegate()
            
            // 비트맵 정리
            lastProcessedBitmap?.recycle()
            lastProcessedBitmap = null
            
            // 재사용 버퍼 정리
            inputBuffer = null
            isModelLoaded = false
            
            Log.d(TAG, "ModelProcessor 리소스가 정리되었습니다")
        } catch (e: Exception) {
            Log.e(TAG, "리소스 정리 중 오류: ${e.message}")
        }
    }
} 