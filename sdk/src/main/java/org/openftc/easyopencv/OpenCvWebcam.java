package org.openftc.easyopencv;

/**
 * EasyOpenCV's webcam interface. Only the stream format enum is here: FIRST's
 * VisionPortal.StreamFormat refers to it. Driving a camera through EasyOpenCV directly is not
 * simulated; use VisionPortal (TeamCode files that need more are skipped at build time).
 */
public interface OpenCvWebcam {

    enum StreamFormat {
        YUY2,
        MJPEG
    }
}
