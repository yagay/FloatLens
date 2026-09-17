package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Compatibility shim for the old PP geometry-refinement entry point.
 *
 * <p>The previous implementation invoked ML Kit again for PP results. Circle now uses one common
 * ML-compatible document contract without calling ML at runtime. Keep this method so existing
 * callers do not need engine-specific branches.</p>
 */
final class OcrGeometryRefiner {
    static OcrDocument refinePpWithUpscaledMlKit(Context context, Bitmap source, OcrDocument pp) {
        return OcrCanonicalGeometry.normalize(context, pp);
    }

    private OcrGeometryRefiner() {}
}
