/*
 * Copyright (C) 2018 Simer Plaha (@simerplaha)
 *
 * This file is a part of SwayDB.
 *
 * SwayDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * SwayDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
 */

package swaydb.core.io.file

import java.nio.channels.FileChannel
import java.nio.channels.FileChannel.MapMode
import java.nio.file.{Path, StandardOpenOption}
import java.nio.{BufferOverflowException, MappedByteBuffer}
import java.util.concurrent.atomic.AtomicBoolean

import com.typesafe.scalalogging.LazyLogging
import swaydb.core.util.TryUtil
import swaydb.data.slice.Slice
import swaydb.data.slice.Slice._

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

private[file] object MMAPFile {

  def read(path: Path)(implicit ec: ExecutionContext): Try[MMAPFile] =
    Try(FileChannel.open(path, StandardOpenOption.READ)) flatMap {
      channel =>
        MMAPFile(path, channel, MapMode.READ_ONLY, channel.size())
    }

  def write(path: Path,
            bufferSize: Long)(implicit ec: ExecutionContext): Try[MMAPFile] =
    Try(FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) flatMap {
      channel =>
        MMAPFile(path, channel, MapMode.READ_WRITE, bufferSize)
    }

  private def apply(path: Path,
                    channel: FileChannel,
                    mode: MapMode,
                    bufferSize: Long)(implicit ec: ExecutionContext): Try[MMAPFile] =
    Try {
      val buff = channel.map(mode, 0, bufferSize)
      new MMAPFile(path, channel, mode, bufferSize, buff)
    }
}

private[file] class MMAPFile(val path: Path,
                             channel: FileChannel,
                             mode: MapMode,
                             bufferSize: Long,
                             @volatile private var buffer: MappedByteBuffer)(implicit ec: ExecutionContext) extends LazyLogging with DBFileType {

  private val open = new AtomicBoolean(true)

  def close(): Try[Unit] = {
    //    logger.info(s"$path: Closing channel")
    if (open.compareAndSet(true, false)) {
      Try {
        forceSave()
        clearBuffer()
        channel.close()
      }
    } else {
      logger.trace("{}: Already closed.", path)
      TryUtil.successUnit
    }
  }

  def forceSave(): Try[Unit] =
    if (mode == MapMode.READ_ONLY)
      TryUtil.successUnit
    else
      Try(buffer.force())

  private def clearBuffer(): Unit = {
    val swapBuffer = buffer
    //null the buffer so that all future read requests do not read this buffer.
    //the resulting NullPointerException will re-route request to the new Segment.
    //TO-DO: Use Option here instead. Test using Option does not have read performance impact.
    buffer = null
    BufferCleaner ! (swapBuffer, path)
  }

  private def extendBuffer(bufferSize: Long): Try[Unit] =
    Try {
      val positionBeforeClear = buffer.position()
      buffer.force()
      clearBuffer()
      buffer = channel.map(mode, 0, positionBeforeClear + bufferSize)
      buffer.position(positionBeforeClear)
    }

  @tailrec
  final def append(slice: Slice[Byte]): Try[Unit] =
    Try(buffer.put(slice.toByteBuffer)) match {
      case _: Success[_] =>
        TryUtil.successUnit

      //Although this code extends the buffer, currently there is no implementation that requires this feature.
      //All the bytes requires for each write operation are pre-calculated EXACTLY and an overflow should NEVER occur.
      case Failure(ex: BufferOverflowException) =>
        val requiredByteSize = slice.size.toLong
        logger.debug("{}: BufferOverflowException. Required bytes: {}. Remaining bytes: {}. Extending buffer with {} bytes.",
          path, requiredByteSize, buffer.remaining(), requiredByteSize, ex)

        val result = extendBuffer(requiredByteSize)
        if (result.isSuccess)
          append(slice)
        else
          result

      case Failure(exception) =>
        Failure(exception)
    }

  def read(position: Int, size: Int): Try[Slice[Byte]] =
    Try {
      val array = new Array[Byte](size)
      var i = 0
      while (i < size) {
        array(i) = buffer.get(i + position)
        i += 1
      }
      Slice(array)
    }

  def get(position: Int): Try[Byte] =
    Try {
      buffer.get(position)
    }

  override def fileSize =
    Success(channel.size())

  override def readAll: Try[Slice[Byte]] =
    read(0, channel.size().toInt)

  override def isOpen =
    channel.isOpen

  override def isMemoryMapped =
    Success(true)

  override def isLoaded: Try[Boolean] =
    Success(buffer.isLoaded)

  override def isFull: Try[Boolean] =
    Success(buffer.remaining() == 0)

  override def memory: Boolean = false

  override def delete(): Try[Unit] =
    close flatMap {
      _ =>
        IO.delete(path)
    }
}
