package com.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.data.network.LocalMovieBoxServer
import com.example.ui.screens.MovieBoxApp
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MovieViewModel

class MainActivity : AppCompatActivity() {
    private val viewModel: MovieViewModel by viewModels()
    private var localServer: LocalMovieBoxServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Start local MovieBox proxy server automatically inside the phone when opened
        localServer = LocalMovieBoxServer(applicationContext, 3000)
        localServer?.start()

        val imageDispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 48
        }

        // Dedicated high-speed OkHttpClient for image/poster loading with parallel connection pool
        val imageOkHttpClient = okhttp3.OkHttpClient.Builder()
            .dispatcher(imageDispatcher)
            .connectionPool(okhttp3.ConnectionPool(64, 15, java.util.concurrent.TimeUnit.MINUTES))
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        // Configure high-performance Coil ImageLoader with instant Memory & Disk cache and SVG support
        val imageLoader = coil.ImageLoader.Builder(applicationContext)
            .components {
                add(coil.decode.SvgDecoder.Factory())
            }
            .okHttpClient(imageOkHttpClient)
            .memoryCache {
                coil.memory.MemoryCache.Builder(applicationContext)
                    .maxSizePercent(0.35) // 35% app memory dedicated to instant poster display
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(applicationContext.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024) // 250MB persistent fast disk cache
                    .build()
            }
            .respectCacheHeaders(false) // Cache posters aggressively regardless of remote headers
            .crossfade(false) // Instant 0ms transition for zero display lag
            .allowHardware(true) // Direct GPU rendering for fastest draw speed
            .allowRgb565(true) // Reduce memory footprint by 50% for 2x faster bitmap decode
            .build()
        coil.Coil.setImageLoader(imageLoader)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MovieBoxApp(viewModel = viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        localServer?.stop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (viewModel.playInfo != null) {
            val pipBuilder = android.app.PictureInPictureParams.Builder()
            val aspectRatio = android.util.Rational(16, 9)
            pipBuilder.setAspectRatio(aspectRatio)
            enterPictureInPictureMode(pipBuilder.build())
        }
    }
}
