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

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.util.Size
import io.devicefarmer.minicap.output.DisplayOutput
import io.devicefarmer.minicap.output.MinicapClientOutput
import io.devicefarmer.minicap.SimpleServer
import org.slf4j.LoggerFactory
import java.io.*
import java.net.Socket
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.thread

/**
 * Base class to provide images of the screen. Those captures can be setup from SurfaceControl - as
 * it currently is - but could as well comes from MediaProjection API if useful in a future use case.
 * It basically receives screen images, do whatever processing needed (here, encodes in jpeg format)
 * and sends the results to an output (could be a file for screenshot, or a minicap client receiving the
 * jpeg stream)
 */
abstract class BaseProvider(private val targetSize: Size, var rotation: Int) : SimpleServer.Listener,
    ImageReader.OnImageAvailableListener {

    companion object {
        val log = LoggerFactory.getLogger(BaseProvider::class.java.simpleName)
    }

    private lateinit var clientSocket: Socket
    private lateinit var serverSocket: ServerSocket
    private lateinit var imageReader: ImageReader
    private var previousTimeStamp: Long = 0L
    private var framePeriodMs: Long = 0
    private lateinit var lastImage: ByteArray
    private var senderStarted = false
    private var debug = false

    private val lock = ReentrantLock()

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

    @SuppressLint("WrongConstant")
    fun init() {
        log.info("Initialising image reader instance")
        imageReader = ImageReader.newInstance(
            getTargetSize().width,
            getTargetSize().height,
            PixelFormat.RGBA_8888,
            1
        )
    }


    fun Bitmap.rotate(degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    override fun onConnection(socket: Socket, server: ServerSocket, debugMode: Boolean) {
        log.info("New connection")
        init()
        clientSocket = socket
        serverSocket = server
        debug = debugMode
    }

    override fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage()
        if (image != null) {
            val encodedImage = encode(image, quality)
            synchronized(this) {
                // log.info("Saving new image")
                lastImage = encodedImage
            }
            if (!senderStarted) {
                startSender()
                senderStarted = true
            }
            image.close()
        } else {
            // log.info("no image available")
        }
    }

    private fun startSender() {
      thread {
          var input = DataInputStream(clientSocket.inputStream)
          while(true) {
              //log.info("waiting for client to send 1 byte")
              // Wait for client to send 1 byte char
              try {
                val askImage = input.readByte()
              } catch(e: Exception) {
                clientSocket.close()
              }
              //log.info("after reading 1 byte")
              var currentImage: ByteArray
              synchronized(this) {
                  currentImage = lastImage.copyOf()
              }
              //log.info("currentImage size is ${currentImage.size}")
              try {
                log.info("Sending image to client (${currentImage.size} bytes)")
                with(clientSocket.outputStream) {
                  write(currentImage)
                  flush()
                  if (debug){
                    clientSocket.close()
                  }
                }
              }
              catch(e: Exception) {
                clientSocket.close()
              }
              if (clientSocket.isClosed() || debug){ // To restart the sending thread in case of reconnect
                log.warn("Waiting on new connection")
                clientSocket = serverSocket.accept()
                input = DataInputStream(clientSocket.inputStream)
                log.info("Reconnected!")
              }
              //log.info("done")
          }
      }
    }

    private fun encode(image: Image, q:Int): ByteArray {
        val planes: Array<Image.Plane> = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride: Int = planes[0].pixelStride
        val rowStride: Int = planes[0].rowStride
        val rowPadding: Int = rowStride - pixelStride * image.width

        // createBitmap can be resources consuming
        var bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888)


        bitmap.copyPixelsFromBuffer(buffer)
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, getTargetSize().width, getTargetSize().height)

        // If landscape mode, rotate image
        if (image.width > image.height) {
            bitmap = bitmap.rotate(90f)
        }

        val finalSize = bitmap.rowBytes * bitmap.height
        val byteBuffer = ByteBuffer.allocate(finalSize)
        bitmap.copyPixelsToBuffer(byteBuffer)

        return byteBuffer.array()
    }
}
