/* Background color matrix and optional AGSL sharpen adapted from SukiSU-Ultra
 * 075ac81f1dfe58b3f59c665a1b07fda8b975015f (GPL-3.0-or-later).
 * Ziyu uses pixel coordinates, Android 13 capability guards and a cached fallback.
 */
package io.github.xgl34222220.ziyu.ui.appearance

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas

@Composable
internal fun Modifier.backgroundEffects(effects: BackgroundEffects): Modifier {
    val matrix = remember(effects) {
        if (effects.saturation == 100f && effects.contrast == 100f && effects.brightness == 0f) null
        else ColorMatrix().apply {
            setSaturation(effects.saturation / 100f)
            val c = effects.contrast / 100f
            val offset = 128f * (1f - c) + effects.brightness / 100f * 255f
            postConcat(ColorMatrix(floatArrayOf(c,0f,0f,0f,offset, 0f,c,0f,0f,offset,
                0f,0f,c,0f,offset, 0f,0f,0f,1f,0f)))
        }
    }
    val sharpen = remember(effects.sharpen) {
        if (Build.VERSION.SDK_INT >= 33 && effects.sharpen > 0f)
            runCatching { SharpenEffect() }.getOrNull() else null
    }
    var result = this
    if (matrix != null) result = result.drawWithCache {
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                val saved = canvas.nativeCanvas.saveLayer(0f, 0f, size.width, size.height, paint)
                try { drawContent() } finally { canvas.nativeCanvas.restoreToCount(saved) }
            }
        }
    }
    if (Build.VERSION.SDK_INT >= 33 && sharpen != null) result = result.graphicsLayer {
        if (!sharpen.failed) {
            try { renderEffect = sharpen.effect(effects.sharpen / 100f * 1.2f) }
            catch (_: Exception) { sharpen.failed = true; renderEffect = null }
        }
    }
    return result
}

@RequiresApi(33)
private class SharpenEffect {
    var failed = false
    private var lastAmount = -1f
    private val shader = RuntimeShader("""
        uniform shader content;
        uniform float amount;
        half4 main(float2 p) {
            half4 c = content.eval(p);
            half4 n = (content.eval(p+float2(-1,0)) + content.eval(p+float2(1,0))
                     + content.eval(p+float2(0,-1)) + content.eval(p+float2(0,1))) * 0.25;
            return half4(clamp((c+(c-n)*half(amount)).rgb, half3(0), half3(1)), c.a);
        }
    """)
    private val cachedEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    fun effect(amount: Float): androidx.compose.ui.graphics.RenderEffect {
        if (amount != lastAmount) { shader.setFloatUniform("amount", amount); lastAmount = amount }
        return cachedEffect
    }
}
