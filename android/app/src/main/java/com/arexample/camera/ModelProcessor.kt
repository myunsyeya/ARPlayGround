package com.arexample.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

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
            
            // 인터프리터 생성
            interpreter = Interpreter(mappedBuffer)
            isModelLoaded = true
            
            Log.d(TAG, "모델 로드 성공: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "모델 로드 실패: ${e.message}")
            isModelLoaded = false
            false
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
            // 입력 버퍼 생성
            val inputBuffer = convertBitmapToByteBuffer(bitmap)
            
            // 출력 버퍼 생성 (모델 출력에 맞게 조정 필요)
            // 예시: 마스크 출력 (1, 800, 800, 1)
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            val outputBuffer = ByteBuffer.allocateDirect(
                outputShape?.get(0)!! * outputShape[1] * outputShape[2] * outputShape[3] * 4
            ).apply {
                order(ByteOrder.nativeOrder())
            }
            
            // 모델 실행
            interpreter?.run(inputBuffer, outputBuffer)
            
            // 결과 비트맵 생성 (모델 출력에 맞게 시각화 로직 필요)
            val resultBitmap = createResultBitmap(outputBuffer, bitmap.width, bitmap.height)
            
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
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // 입력 크기 및 채널 수에 맞게 조정 필요
        // 예시: 입력 크기 800x800, RGB 3채널 (1, 800, 800, 3)
        val bufferSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3 * 4 // FLOAT32
        val inputBuffer = ByteBuffer.allocateDirect(bufferSize).apply {
            order(ByteOrder.nativeOrder())
        }
        
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        for (pixelValue in pixels) {
            // RGB 채널 분리 및 정규화 (모델에 따라 전처리 방식 변경 필요)
            val r = (pixelValue shr 16 and 0xFF) / 255.0f
            val g = (pixelValue shr 8 and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f
            
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }
        
        return inputBuffer
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
        // 출력 버퍼를 비트맵으로 변환하는 로직
        // 실제 구현은 모델 출력 형식에 따라 달라짐
        
        // 예시: 간단한 오버레이 생성
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
    }
    
    /**
     * 리소스를 해제합니다.
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        isModelLoaded = false
    }
} 