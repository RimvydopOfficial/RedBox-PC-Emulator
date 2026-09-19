package com.rimvydop.redboxpcemulator

import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.libsdl.app.SDLActivity

@Composable
fun VMDisplay(
    modifier: Modifier = Modifier,
    onSurfaceReady: (Surface) -> Unit = {},
    onSurfaceDestroyed: () -> Unit = {}
) {
    AndroidView(
        modifier = modifier,

        factory = { context ->

            val activity =
                context as? MainActivity

            SDLActivity.ExSDLSurface(context).apply {

                /*
                 * IMPORTANT:
                 * Make this the real SDLActivity surface.
                 *
                 * SDLActivity.getNativeSurface(),
                 * onNativeResize(),
                 * onNativeSurfaceChanged(),
                 * and handleNativeState()
                 * will now use this SurfaceView.
                 */
                SDLActivity.setRedBoxSurface(this)

                Log.d(
                    "RedBoxDisplay",
                    "SDL ExSDLSurface attached to SDLActivity"
                )

                /*
                 * Extra callback only for RedBox logging
                 * and our existing native surface bridge.
                 *
                 * ExSDLSurface already has SDL's own
                 * SurfaceHolder.Callback internally.
                 */
                holder.addCallback(
                    object : SurfaceHolder.Callback {

                        override fun surfaceCreated(
                            holder: SurfaceHolder
                        ) {
                            Log.d(
                                "RedBoxDisplay",
                                "RedBox SDL display surface created"
                            )

                            if (holder.surface.isValid) {
                                val frame = holder.surfaceFrame
                                activity?.setQemuDisplaySurface(
                                    surface = holder.surface,
                                    width = frame.width(),
                                    height = frame.height(),
                                    pixelFormat = 0,
                                    refreshRate = display?.refreshRate ?: 60f
                                )

                                Log.d(
                                    "RedBoxDisplay",
                                    "SDL Android Surface sent to native C++"
                                )

                                onSurfaceReady(
                                    holder.surface
                                )
                            }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            Log.d(
                                "RedBoxDisplay",
                                "RedBox SDL display surface changed: ${width}x${height}"
                            )

                            if (holder.surface.isValid) {
                                activity?.setQemuDisplaySurface(
                                    surface = holder.surface,
                                    width = width,
                                    height = height,
                                    pixelFormat = format,
                                    refreshRate = display?.refreshRate ?: 60f
                                )

                                onSurfaceReady(
                                    holder.surface
                                )
                            }
                        }

                        override fun surfaceDestroyed(
                            holder: SurfaceHolder
                        ) {
                            Log.d(
                                "RedBoxDisplay",
                                "RedBox SDL display surface destroyed"
                            )

                            activity?.setQemuDisplaySurface(
                                null
                            )

                            Log.d(
                                "RedBoxDisplay",
                                "Native display Surface cleared"
                            )

                            onSurfaceDestroyed()
                        }
                    }
                )
            }
        }
    )
}