package com.arexample.camera

import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

/**
 * React Native 카메라 뷰 매니저
 * 
 * RNCCameraView를 React Native에 노출시키는 ViewManager입니다.
 * React Native 측에서 카메라 기능을 사용할 수 있도록 합니다.
 * 
 * 사용 예시:
 * ```javascript
 * // React Native 코드에서 사용
 * import { requireNativeComponent } from 'react-native';
 * const CameraView = requireNativeComponent('RNCCameraView');
 * 
 * // JSX에서 사용
 * <CameraView style={{ flex: 1 }} saveImages={true} />
 * ```
 */
@ReactModule(name = RNCCameraViewManager.REACT_CLASS)
class RNCCameraViewManager : SimpleViewManager<RNCCameraView>() {
    
    companion object {
        /**
         * React Native에서 사용할 네이티브 컴포넌트의 이름
         */
        const val REACT_CLASS = "RNCCameraView"
    }

    /**
     * 이 ViewManager의 이름을 반환합니다.
     * 
     * React Native 측에서 이 이름으로 네이티브 컴포넌트를 참조합니다.
     * 
     * @return 네이티브 컴포넌트 이름
     */
    override fun getName(): String = REACT_CLASS

    /**
     * 네이티브 뷰 인스턴스를 생성합니다.
     * 
     * React Native에서 뷰가 생성될 때 호출됩니다.
     * 
     * @param reactContext React Native 테마 컨텍스트
     * @return 생성된 RNCCameraView 인스턴스
     */
    override fun createViewInstance(reactContext: ThemedReactContext): RNCCameraView {
        CameraManagerModule.getInstance().setReactContext(reactContext.reactApplicationContext)
        return RNCCameraView(reactContext)
    }
    
    /**
     * 비트맵 이미지 저장 기능 활성화 여부를 설정합니다.
     * 
     * React Native에서 'saveImages' 프로퍼티를 통해 제어할 수 있습니다.
     * 
     * @param view 대상 RNCCameraView
     * @param enabled 활성화 여부
     */
    @ReactProp(name = "saveImages")
    fun setSaveImages(view: RNCCameraView, enabled: Boolean) {
        view.setSaveImagesEnabled(enabled)
    }
} 