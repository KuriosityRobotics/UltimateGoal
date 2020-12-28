package org.firstinspires.ftc.teamcode.ultimategoal.vision;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.opencv.imgproc.Imgproc.CHAIN_APPROX_SIMPLE;
import static org.opencv.imgproc.Imgproc.COLOR_RGB2HSV;
import static org.opencv.imgproc.Imgproc.FONT_HERSHEY_SCRIPT_COMPLEX;
import static org.opencv.imgproc.Imgproc.LINE_8;
import static org.opencv.imgproc.Imgproc.MORPH_CLOSE;
import static org.opencv.imgproc.Imgproc.MORPH_ELLIPSE;
import static org.opencv.imgproc.Imgproc.RETR_EXTERNAL;
import static org.opencv.imgproc.Imgproc.cvtColor;
import static org.opencv.imgproc.Imgproc.findContours;

public class RingStackLocator {

    static final Scalar RED = new Scalar(0, 0, 255);
    static final Scalar GREEN = new Scalar(0, 255, 0);
    static final Scalar BLUE = new Scalar(255, 0, 0);

    public enum TargetZone {
        TARGET_ZONE_UNKNOWN,
        TARGET_ZONE_A,
        TARGET_ZONE_B,
        TARGET_ZONE_C
    }

    /**
     * Finds the largest contour by enclosed area in a list
     *
     * @param contours
     * @return the index of the specified List which contains the largest contour
     */
    static int largestContour(final List<MatOfPoint> contours, double area) {
        if (contours.size() == 0)
            return -1;

        double maxVal = 0;
        int maxValIdx = -1;
        for (int contourIdx = 0; contourIdx < contours.size(); contourIdx++) {
            double contourArea = Imgproc.contourArea(contours.get(contourIdx));
            if (maxVal < contourArea && contourArea > 0.005 * area) {
                maxVal = contourArea;
                maxValIdx = contourIdx;
            }
        }

        return maxValIdx;
    }

    /**
     * Find the index of the countour of a list which is closest to the specified point. Contours which are smaller than 0.0008*area will be ignored, so we don't get any super tiny values caused by small imperfections
     *
     * @param contours
     * @param point    - The point we want to be close to
     * @param area     - the total area of the plane where the contours are located.
     * @return an index of the specified List which contains the contour closest to the specified point
     */
    static int closestContour(final List<MatOfPoint> contours, Point point, double area) {
        double closestDistToPoint = Double.MAX_VALUE;
        int closestIdx = -1;
        int currIdx = 0;
        for (MatOfPoint contour : contours) {
            Rect contourBoundingBox = Imgproc.boundingRect(contour);
            Moments moments = Imgproc.moments(contour); // Calculate the "point of mass" of the enclosed area
            double avgX = moments.m10 / moments.m00;
            double avgY = moments.m01 / moments.m00;
            double dist = Math.hypot(avgX - point.x, avgY - point.y);
            if (dist < closestDistToPoint && contourBoundingBox.area() > 0.0008 * area) {
                closestIdx = currIdx;
                closestDistToPoint = dist;
            }
            currIdx++;
        }
        ;

        return closestIdx;
    }

    private static double getClosestFromRange(final double input) {
        double[] numbers = new double[]{0, 1, 4};
        List<Double> list = Arrays.stream(numbers).boxed().collect(Collectors.toList());
        return list.stream()
                .min(Comparator.comparingDouble(i -> Math.abs(i - input))).orElse(0d);
    }

    /**
     * This function takes an Image in the form of a Mat, and then will calculate how many rings are stacked in it
     *
     * @param input
     * @return an int which represents the calculated number of rings
     */
    public static TargetZone processFrame(final Mat input) {
        return processFrame(input, false);
    }

    /**
     * This function takes an Image in the form of a Mat, and then will calculate how many rings are stacked in it, and use that to find the target zone
     * It also mutates the input image if shouldWriteToImage is true, and draws some text, a bounding box, and the border of the rings
     *
     * @param input
     * @return an enum representing the target zone
     */
    public static TargetZone processFrame(final Mat input, boolean shouldWriteToImage) {
        Imgproc.resize(input, input, new Size(480, 270));

        final int IMAGE_WIDTH = input.width();
        final int IMAGE_HEIGHT = input.height();

        float numRings = 0; // This is going to be our guess at how many rings there are

        Mat inputHSV = new Mat();
        Mat mask = new Mat(); // This mask will reflect whether or not each pixel in it is yellow enough
        Mat hierarchy = new Mat(); // we don't need to use this, but opencv requires it

        cvtColor(input, inputHSV, COLOR_RGB2HSV); // This converts the colourspace from input to HSV, and then puts that into submatHSV

        Imgproc.medianBlur(inputHSV, inputHSV, 9);
        Core.inRange(inputHSV, new Scalar(10, 100, 15), new Scalar(25, 255, 255), mask); // We now take submatHSV, and work out what is within our colour range that's acceptable. For each pixel, if it is within the range, it will be white; otherwise, it will be black
        Imgproc.morphologyEx(mask, mask, MORPH_CLOSE, Imgproc.getStructuringElement(MORPH_ELLIPSE, new Size(5, 5)));
        //      Imgproc.Canny(mask, mask, 10, 10);

        List<MatOfPoint> contours = new ArrayList<MatOfPoint>(); // List for storing contours
        findContours(mask, contours, hierarchy, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE); // Find all the contours (edges) on the mask
        //    int closestContour = closestContour(contours, new Point(mask.width() / 2f, (mask.height() / 3f)*2), mask.size().area()); // Find the index of the largest contour
        int closestContour = largestContour(contours, mask.size().area());

        if (closestContour >= 0 && contours.get(closestContour) != null) { // Do we even have contours?
            Rect contourBoundingBox = Imgproc.boundingRect(contours.get(closestContour)); // Draw a bounding box around the largest contour
            numRings = (20f * contourBoundingBox.height) / (3f * contourBoundingBox.width); // We're using the ratio between the height and width of the bounding box to determine how many there are. The 20 and 3 can be calculated from the actual dimensions of the rings. Also am keeping it as an int so it rounds automatically toward zero

            Imgproc.rectangle(input, contourBoundingBox, BLUE, 1);

        }
        if (shouldWriteToImage) {
            for (int i = 0; i < contours.size(); i++) {
                Imgproc.drawContours(input, contours, i, GREEN, 2, LINE_8, hierarchy);
            }
            Imgproc.putText(input, String.valueOf(numRings), new Point(0.025 * IMAGE_WIDTH, 0.1 * IMAGE_HEIGHT), FONT_HERSHEY_SCRIPT_COMPLEX, 0.0025 * IMAGE_WIDTH, BLUE, 1);
        }

        inputHSV.release();
        hierarchy.release();
        mask.release();
        //   cvtColor(input, inputHSV, COLOR_HSV2RGB); // This converts the colourspace from input to HSV, and then puts that into submatHSV


        switch ((int) getClosestFromRange(numRings)) {
            case 0:
                return TargetZone.TARGET_ZONE_A;
            case 1:
                return TargetZone.TARGET_ZONE_B;
            case 4:
                return TargetZone.TARGET_ZONE_C;
            default:
                return TargetZone.TARGET_ZONE_UNKNOWN;
        }
    }
}
