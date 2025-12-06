package com.app.bubble;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import java.util.List;

public class ImageStitcher {

    /**
     * Stitches a list of bitmaps vertically into a single bitmap.
     * 
     * @param bitmaps The list of bitmaps captured during the scroll.
     * @return A single long bitmap containing all the segments.
     */
    public static Bitmap stitchImages(List<Bitmap> bitmaps) {
        if (bitmaps == null || bitmaps.isEmpty()) {
            return null;
        }

        // 1. Calculate total height and max width
        int totalHeight = 0;
        int maxWidth = 0;

        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null) {
                totalHeight += bitmap.getHeight();
                if (bitmap.getWidth() > maxWidth) {
                    maxWidth = bitmap.getWidth();
                }
            }
        }

        // Avoid creating a bitmap that is too large (Android has memory limits)
        // A safe vertical limit is often around 4096 or 8192 pixels depending on the device.
        // We will clamp it just in case.
        int maxHeightLimit = 8192; 
        if (totalHeight > maxHeightLimit) {
            totalHeight = maxHeightLimit;
        }

        if (maxWidth == 0 || totalHeight == 0) {
            return null;
        }

        try {
            // 2. Create the long result bitmap
            Bitmap result = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            Paint paint = new Paint();

            // 3. Draw each bitmap onto the canvas
            int currentHeight = 0;
            
            // In a sophisticated implementation, we would compare pixel rows here to find 
            // the exact overlap and avoid duplicate lines of text. 
            // For this implementation, we rely on the scrolling service to provide 
            // distinct enough chunks, or we perform a simple concatenation.
            // Since GlobalScrollService scrolls a specific amount, there might be slight overlap.
            // The OCR engine (Google Vision) is usually smart enough to handle slight duplicate lines,
            // but we will apply a small vertical offset if needed to simulate the overlap removal.
            
            // Assuming a scroll overlap of roughly 0% for simplicity in this Java-only version,
            // or we could crop the top X pixels of subsequent images to remove headers.
            
            for (int i = 0; i < bitmaps.size(); i++) {
                Bitmap bitmap = bitmaps.get(i);
                if (bitmap == null) continue;

                // Stop if we run out of space
                if (currentHeight + bitmap.getHeight() > totalHeight) {
                    break;
                }

                canvas.drawBitmap(bitmap, 0, currentHeight, paint);
                currentHeight += bitmap.getHeight();
            }

            return result;

        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            // If we run out of memory, just return the first bitmap as a fallback
            return bitmaps.get(0);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}