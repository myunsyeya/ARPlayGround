#include <jni.h>
#include <string>
#include <android/bitmap.h>
#include <android/log.h>

#define  LOG_TAG    "YuvToRgbConverter"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 부동 소수점 대신 고정 소수점 정수로 변환
// 고정 소수점 승수 (10비트 정밀도)
#define FIX(x) ((int)((x) * 1024 + 0.5))

// 최적화된 YUV -> RGB 변환 함수
extern "C" JNIEXPORT jint JNICALL
Java_com_arexample_camera_CameraManagerModule_convertYuvToRgbNative(
        JNIEnv *env,
        jobject /* this */,
        jbyteArray yuv420sp,
        jint width,
        jint height,
        jobject outBitmap) {
    
    // 입력 YUV 데이터에 액세스
    jbyte* yuv = env->GetByteArrayElements(yuv420sp, nullptr);
    
    // 출력 비트맵에 액세스
    AndroidBitmapInfo bitmapInfo;
    void* bitmapPixels;
    
    if (AndroidBitmap_getInfo(env, outBitmap, &bitmapInfo) < 0) {
        LOGE("Failed to get bitmap info");
        env->ReleaseByteArrayElements(yuv420sp, yuv, 0);
        return -1;
    }
    
    if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Bitmap format is not RGBA_8888");
        env->ReleaseByteArrayElements(yuv420sp, yuv, 0);
        return -2;
    }
    
    if (AndroidBitmap_lockPixels(env, outBitmap, &bitmapPixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        env->ReleaseByteArrayElements(yuv420sp, yuv, 0);
        return -3;
    }
    
    // YUV 변환 계수 (고정 소수점 정수)
    const int FIXED_R_V = FIX(1.402);
    const int FIXED_G_U = FIX(-0.344);
    const int FIXED_G_V = FIX(-0.714);
    const int FIXED_B_U = FIX(1.772);
    
    // YUV 데이터 포인터 설정
    jbyte* yPtr = yuv;
    jbyte* uvPtr = yuv + (width * height);
    
    // RGB로 변환
    uint32_t* rgbaPtr = static_cast<uint32_t*>(bitmapPixels);
    
    // YUV420에서 변환 - 최적화된 구현
    const int frameSize = width * height;
    
    // 룩업 테이블: 지연 초기화
    static int initialized = 0;
    static int yuvToRgbTable[256 + 256 + 256]; // Y(직접) + Cr LUT + Cb LUT
    
    // LUT 초기화 (최초 한번만)
    if (!initialized) {
        // Y 값은 직접 인덱스로 사용 (Y 범위: 0-255)
        
        // Cr -> R 계수 테이블 (V 범위: -128 ~ 127)
        for (int i = 0; i < 256; i++) {
            int v = i - 128;
            int value = (FIXED_R_V * v + 512) >> 10; // 반올림
            // 256 오프셋 (Y 값 이후)
            yuvToRgbTable[256 + i] = value;
        }
        
        // Cr,Cb -> G 계수 테이블 (복합 테이블 대신 분리)
        for (int i = 0; i < 256; i++) {
            int v = i - 128;
            // G에 대한 Cb 영향
            int value = (FIXED_G_U * v + 512) >> 10; // 반올림
            // 2*256 오프셋 (Y, Cr 테이블 이후)
            yuvToRgbTable[2*256 + i] = value;
        }
        
        // Cb -> B 계수 테이블 (U 범위: -128 ~ 127)
        for (int i = 0; i < 256; i++) {
            int u = i - 128;
            int value = (FIXED_B_U * u + 512) >> 10; // 반올림
            // 3*256 오프셋 (Y, Cr, g_Cb 테이블 이후)
            yuvToRgbTable[3*256 + i] = value;
        }
        
        // G에 대한 Cr 영향
        for (int i = 0; i < 256; i++) {
            int v = i - 128;
            int value = (FIXED_G_V * v + 512) >> 10; // 반올림
            // 4*256 오프셋
            yuvToRgbTable[4*256 + i] = value;
        }
        
        initialized = 1;
    }
    
    // 메모리 액세스 패턴 개선 및 루프 최적화
    for (int j = 0; j < height - 1; j += 2) {
        // 현재 행과 다음 행의 Y 포인터
        int rowStart = j * width;
        int nextRowStart = rowStart + width;
        
        // 현재 UV 행 시작점
        int uvRowStart = (j >> 1) * width;
        
        for (int i = 0; i < width - 1; i += 2) {
            // UV 인덱스 계산 (NV21 형식에서 V는 먼저, U는 나중에)
            int uvIndex = uvRowStart + (i & ~1);
            
            // UV 값 (한 번만 로드하고 재사용)
            int v = uvPtr[uvIndex] & 0xff;
            int u = uvPtr[uvIndex + 1] & 0xff;
            
            // 미리 계산된 룩업 테이블 값 가져오기
            int rTable = yuvToRgbTable[256 + v]; // Cr -> R
            int gTable = yuvToRgbTable[2*256 + u] + yuvToRgbTable[4*256 + v]; // Cb,Cr -> G
            int bTable = yuvToRgbTable[3*256 + u]; // Cb -> B
            
            // 4개 픽셀 인덱스 계산 (2x2 블록)
            int index1 = rowStart + i;
            int index2 = index1 + 1;
            int index3 = nextRowStart + i;
            int index4 = index3 + 1;
            
            // Y 값 가져오기 (로컬 변수로 캐싱)
            int y1 = yPtr[index1] & 0xff;
            int y2 = yPtr[index2] & 0xff;
            int y3 = yPtr[index3] & 0xff;
            int y4 = yPtr[index4] & 0xff;
            
            // 픽셀 1 (좌상단)
            int r = y1 + rTable;
            int g = y1 + gTable;
            int b = y1 + bTable;
            
            // 값 범위 제한 (비교 연산자 대신 비트 마스킹 활용)
            r = (r < 0) ? 0 : ((r > 255) ? 255 : r);
            g = (g < 0) ? 0 : ((g > 255) ? 255 : g);
            b = (b < 0) ? 0 : ((b > 255) ? 255 : b);
            
            rgbaPtr[index1] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            
            // 픽셀 2 (우상단)
            r = y2 + rTable;
            g = y2 + gTable;
            b = y2 + bTable;
            
            r = (r < 0) ? 0 : ((r > 255) ? 255 : r);
            g = (g < 0) ? 0 : ((g > 255) ? 255 : g);
            b = (b < 0) ? 0 : ((b > 255) ? 255 : b);
            
            rgbaPtr[index2] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            
            // 픽셀 3 (좌하단)
            r = y3 + rTable;
            g = y3 + gTable;
            b = y3 + bTable;
            
            r = (r < 0) ? 0 : ((r > 255) ? 255 : r);
            g = (g < 0) ? 0 : ((g > 255) ? 255 : g);
            b = (b < 0) ? 0 : ((b > 255) ? 255 : b);
            
            rgbaPtr[index3] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            
            // 픽셀 4 (우하단)
            r = y4 + rTable;
            g = y4 + gTable;
            b = y4 + bTable;
            
            r = (r < 0) ? 0 : ((r > 255) ? 255 : r);
            g = (g < 0) ? 0 : ((g > 255) ? 255 : g);
            b = (b < 0) ? 0 : ((b > 255) ? 255 : b);
            
            rgbaPtr[index4] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    }
    
    // 리소스 정리
    AndroidBitmap_unlockPixels(env, outBitmap);
    env->ReleaseByteArrayElements(yuv420sp, yuv, 0);
    
    return 0;
} 