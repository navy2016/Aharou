package com.aharou.core.ui

import android.app.Application
import androidx.compose.ui.graphics.asAndroidBitmap
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgImageDecodeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun svgWithExplicitSizeAndNoViewBoxRendersToPreviewBitmap() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60"><rect width="120" height="60" fill="#ff0000"/></svg>"""
        val file = temporary.newFile("preview.svg").apply { writeText(svg) }
        val bitmap = decodeSampledBitmap(file.absolutePath, 1024)!!
        assertEquals(120, bitmap.width)
        assertEquals(60, bitmap.height)
        assertEquals(0xffff0000.toInt(), bitmap.asAndroidBitmap().getPixel(60, 30))
    }

    @Test fun svgBytesWithViewBoxAreDecodedWithoutFileExtension() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 80 40"><rect width="80" height="40"/></svg>"""
        val bitmap = decodeSampledBitmap(svg.toByteArray(), 1024)!!
        assertEquals(80, bitmap.width)
        assertEquals(40, bitmap.height)
    }

    @Test fun sampleFitsEdgeAndPixelBudget() {
        val (width, height) = svgBitmapSize(10_000.0, 10_000.0, 2048, 4_000_000)
        assertTrue(width <= 2048 && height <= 2048)
        assertTrue(width.toLong() * height <= 4_000_000)
        assertEquals(width, height)
    }

    @Test fun narrowImageStillHonorsPixelBudgetAfterRounding() {
        val (width, height) = svgBitmapSize(100_000.0, 1.0, 2048, 10)
        assertEquals(1, height)
        assertTrue(width.toLong() * height <= 10)
    }

    @Test fun galleryLoaderRegistersSvgAndDisablesEntityExpansion() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = temporary.newFile("gallery.svg").apply {
            writeText("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 80 40"><rect width="80" height="40"/></svg>""")
        }
        val loader = svgImageLoader(context)
        try {
            val result = loader.execute(ImageRequest.Builder(context).data(file).size(80, 40).build())
            assertTrue(result is SuccessResult)
            assertFalse(SVG.isInternalEntitiesEnabled())
        } finally {
            loader.shutdown()
        }
    }
}
