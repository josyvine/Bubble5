package com.app.bubble;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import java.util.List;

public class ImageStitcher {

    /**
     * Stitches a list of bitmaps vertically, attempting to remove overlaps caused by scrolling.
     */
    public static Bitmap stitchImages(List<Bitmap> bitmaps) {
        if (bitmaps == null || bitmaps.isEmpty()) {
            return null;
        }
        if (bitmaps.size() == 1) {
            return bitmaps.get(0);
        }

        Bitmap result = bitmaps.get(0);

        for (int i = 1; i < bitmaps.size(); i++) {
            Bitmap nextBitmap = bitmaps.get(i);
            if (nextBitmap != null) {
                result = mergeTwoImages(result, nextBitmap);
            }
        }

        return result;
    }

    private static Bitmap mergeTwoImages(Bitmap top, Bitmap bottom) {
        // Find how many pixels at the bottom of 'top' match the top of 'bottom'
        int overlap = findVerticalOverlap(top, bottom);

        int width = Math.min(top.getWidth(), bottom.getWidth());
        // The new height is the sum of both minus the duplicate overlap part
        int height = top.getHeight() + bottom.getHeight() - overlap;

        // Limit height to avoid crashes (texture size limit usually 4096 or 8192)
        if (height > 8000) height = 8000;

        try {
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);

            // Draw the top image
            canvas.drawBitmap(top, 0, 0, null);

            // Draw the bottom image, shifted up by the overlap amount so visuals align
            canvas.drawBitmap(bottom, 0, top.getHeight() - overlap, null);

            // Recycle old bitmaps to free memory
            // Note: In a real app, verify these aren't used elsewhere before recycling
            // top.recycle(); 
            // bottom.recycle();

            return result;
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return top; // Fallback
        }
    }

    /**
     * Scans for visual overlap between two bitmaps.
     * Returns the height (in pixels) of the overlapping region.
     */
    private static int findVerticalOverlap(Bitmap top, Bitmap bottom) {
        int width = Math.min(top.getWidth(), bottom.getWidth());
        int topHeight = top.getHeight();
        int bottomHeight = bottom.getHeight();

        // We assume the scroll isn't huge (e.g. not more than 50% of screen at a time)
        // We look at the bottom 20% of the top image
        int searchHeight = topHeight / 5; 
        
        // Safety check
        if (searchHeight > bottomHeight) searchHeight = bottomHeight;

        // Define a "signature" row from the bottom of the top image
        // We pick a row 10 pixels from the bottom
        int referenceRowY = topHeight - 10;
        int[] referencePixels = new int[width];
        top.getPixels(referencePixels, 0, width, 0, referenceRowY, width, 1);

        // Scan the top part of the bottom image to find this row
        int[] comparePixels = new int[width];
        
        for (int y = 0; y < searchHeight; y++) {
            bottom.getPixels(comparePixels, 0, width, 0, y, width, 1);

            if (arraysSimilar(referencePixels, comparePixels)) {
                // Match found!
                // The overlap is the distance from the match in bottom image
                // back to the logical start relative to the top image.
                // Overlap = (Pixels remaining in top image after ref row) + (Pixels in bottom image up to match)
                // Roughly: overlap = 10 + y
                return 10 + y;
            }
        }

        // No overlap found (moved too fast or completely different content)
        return 0;
    }

    /**
     * Compares two rows of pixels. Returns true if they are mostly similar.
     * We use a threshold because compression/rendering artifacts might make pixels slightly different.
     */
    private static boolean arraysSimilar(int[] row1, int[] row2) {
        int matches = 0;
        int totalChecked = 0;
        
        // Check every 5th pixel to save CPU
        for (int i = 0; i < row1.length; i += 5) {
            totalChecked++;
            if (row1[i] == row2[i]) {
                matches++;
            }
        }

        // If 80% of pixels match, we assume it's the same row
        return matches > (totalChecked * 0.8);
    }
}