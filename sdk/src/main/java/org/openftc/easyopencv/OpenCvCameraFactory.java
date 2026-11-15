package org.openftc.easyopencv;

/**
 * EasyOpenCV's camera factory, reduced to what FIRST's VisionPortal calls: splitting the camera
 * preview into several viewports (VisionPortal.makeMultiPortalView). The ids it returns only
 * label the previews; the simulator shows each portal's preview by camera name.
 */
public abstract class OpenCvCameraFactory {

    public enum ViewportSplitMethod {
        VERTICALLY,
        HORIZONTALLY
    }

    private static final OpenCvCameraFactory INSTANCE = new OpenCvCameraFactory() {
        @Override
        public int[] splitLayoutForMultipleViewports(int containerId, int numViewports, ViewportSplitMethod viewportSplitMethod) {
            if (numViewports < 2) throw new IllegalArgumentException("Layout requested to be split for <2 viewports!");
            int[] ids = new int[numViewports];
            for (int i = 0; i < numViewports; i++) ids[i] = containerId + 1 + i;
            return ids;
        }
    };

    public static OpenCvCameraFactory getInstance() {
        return INSTANCE;
    }

    public abstract int[] splitLayoutForMultipleViewports(int containerId, int numViewports, ViewportSplitMethod viewportSplitMethod);
}
