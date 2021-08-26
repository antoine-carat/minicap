/*
 * Copyright (C) 2020 Orange
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.devicefarmer.minicap.provider

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.util.Size
import io.devicefarmer.minicap.output.DisplayOutput
import io.devicefarmer.minicap.output.MinicapClientOutput
import io.devicefarmer.minicap.SimpleServer
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.Buffer
import java.util.Scanner

/**
 * Base class to provide images of the screen. Those captures can be setup from SurfaceControl - as
 * it currently is - but could as well comes from MediaProjection API if useful in a future use case.
 * It basically receives screen images, do whatever processing needed (here, encodes in jpeg format)
 * and sends the results to an output (could be a file for screenshot, or a minicap client receiving the
 * jpeg stream)
 */
abstract class BaseProvider(private val targetSize: Size, val rotation: Int) : SimpleServer.Listener,
    ImageReader.OnImageAvailableListener {

    companion object {
        val log = LoggerFactory.getLogger(BaseProvider::class.java.simpleName)
    }

    private lateinit var clientOutput: DisplayOutput
    private lateinit var imageReader: ImageReader
    private lateinit var lastImage: ByteArray
    private var previousTimeStamp: Long = 0L
    private var framePeriodMs: Long = 0
    private var bitmap: Bitmap? = null //is used to compress the images

    var quality: Int = 100
    var frameRate: Float = Float.MAX_VALUE
        set(value) {
            this.framePeriodMs = (1000 / value).toLong()
            log.info("framePeriodMs: $framePeriodMs")
            field = value
        }

    abstract fun screenshot(printer: PrintStream)
    abstract fun getScreenSize(): Size

    fun getTargetSize(): Size = if(rotation%2 != 0) Size(targetSize.height, targetSize.width) else targetSize
    fun getImageReader(): ImageReader = imageReader

    fun init(out: DisplayOutput) {
        imageReader = ImageReader.newInstance(
            getTargetSize().width,
            getTargetSize().height,
            PixelFormat.RGBA_8888,
            2
        )
        clientOutput = out
    }

    override fun onConnection(socket: Socket) {
        clientOutput = MinicapClientOutput(socket)
        log.info("New client connected")
        init(clientOutput)
        Thread {
          val input = Scanner(socket.inputStream)
          while(input.hasNext()) {
            val read_byte = input.next()
            log.info("Received ${read_byte}, sending the last image through")
            clientOutput.send()
          }
        }.start()
    }

    override fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage()
        val currentTime = System.currentTimeMillis()
        if (image != null) {
            if (currentTime - previousTimeStamp > framePeriodMs) {
                previousTimeStamp = currentTime
                clientOutput.imageBuffer
                encode(image, quality, clientOutput.imageBuffer)
            } else {
                log.warn("skipping frame ($currentTime/$previousTimeStamp)")
            }
            image.close()
        } else {
            log.warn("no image available")
        }
    }

    private fun encode(image: Image, q: Int, out: OutputStream) {
        with(image) {
            val planes: Array<Image.Plane> = planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride: Int = planes[0].pixelStride
            val rowStride: Int = planes[0].rowStride
            val rowPadding: Int = rowStride - pixelStride * width
            // createBitmap can be resources consuming
            bitmap ?: Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            ).apply {
                copyPixelsFromBuffer(buffer)
            }.run {
                //the image need to be cropped
                Bitmap.createBitmap(this, 0, 0, getTargetSize().width, getTargetSize().height)
            }.apply {
                (out as ByteArrayOutputStream).reset()
                // copyPixelsToBuffer(out as Buffer)
                compress(Bitmap.CompressFormat.JPEG, q, out)
            }
        }
    }
}
