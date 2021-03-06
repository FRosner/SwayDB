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

import java.nio.ReadOnlyBufferException
import java.nio.channels.{NonReadableChannelException, NonWritableChannelException}
import java.nio.file.{FileAlreadyExistsException, NoSuchFileException}

import org.scalamock.scalatest.MockFactory
import swaydb.core.TestBase
import swaydb.core.segment.SegmentException
import swaydb.core.segment.SegmentException.CannotCopyInMemoryFiles
import swaydb.core.util.Benchmark
import swaydb.data.slice.Slice
import swaydb.core.util.PipeOps._

class DBFileSpec extends TestBase with Benchmark with MockFactory {

  "DBFile.write" should {
    "write bytes to a File" in {
      val testFile = randomFilePath
      val bytes = Slice(randomBytes())

      DBFile.write(bytes, testFile).assertGet
      DBFile.mmapRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet shouldBe bytes
          file.close.assertGet
      }
      DBFile.channelRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet shouldBe bytes
          file.close.assertGet
      }
    }

    "write empty bytes to a File" in {
      val testFile = randomFilePath
      val bytes = Slice.create[Byte](0)

      DBFile.write(bytes, testFile).assertGet
      DBFile.mmapRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet.toArray shouldBe empty
          file.close.assertGet
      }
      IO.exists(testFile) shouldBe true
    }

    "fail to write bytes if the Slice contains empty bytes" in {
      val testFile = randomFilePath
      val bytes = Slice.create[Byte](10)
      bytes.addIntUnsigned(1)
      bytes.addIntUnsigned(2)

      bytes.written shouldBe 2

      DBFile.write(bytes, testFile).failed.assertGet shouldBe SegmentException.FailedToWriteAllBytes(10, 2, bytes.size)
    }

    "fail to write if the file already exists" in {
      val testFile = randomFilePath
      val bytes = randomBytesSlice()

      DBFile.write(bytes, testFile).assertGet
      DBFile.write(bytes, testFile).failed.assertGet shouldBe a[FileAlreadyExistsException] //creating the same file again should fail
      //file remains unchanged
      DBFile.channelRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet shouldBe bytes
          file.close.assertGet
      }
    }
  }

  "DBFile.channelWrite" should {
    "initialise a FileChannel for writing and not reading and invoke the onOpen function on open" in {
      val testFile = randomFilePath
      val bytes = randomBytesSlice()

      //opening a file should trigger the onOpen function
      val onOpen = mockFunction[DBFile, Unit]
      onOpen expects * onCall {
        (dbFile: DBFile) =>
          dbFile.path shouldBe testFile
          dbFile.isOpen shouldBe true
          dbFile.file shouldBe defined
          ()
      } repeat 3.times

      val file = DBFile.channelWrite(testFile, onOpen).assertGet
      //above onOpen is also invoked
      file.isFileDefined shouldBe true //file is set
      file.isOpen shouldBe true
      file.append(bytes).assertGet

      file.readAll.failed.assertGet shouldBe a[NonReadableChannelException]
      file.read(0, 1).failed.assertGet shouldBe a[NonReadableChannelException]
      file.get(0).failed.assertGet shouldBe a[NonReadableChannelException]

      //closing the channel and reopening it will open it in read only mode
      file.close.assertGet
      file.isFileDefined shouldBe false
      file.isOpen shouldBe false
      file.readAll.assertGet shouldBe bytes //read
      //above onOpen is also invoked
      file.isFileDefined shouldBe true
      file.isOpen shouldBe true
      //cannot write to a reopened file channel. Ones closed! It cannot be reopened for writing.
      file.append(bytes).failed.assertGet shouldBe a[NonWritableChannelException]

      file.close.assertGet

      //open new file channel for read.
      DBFile.channelRead(testFile, onOpen).assertGet ==> {
        file =>
          file.readAll.assertGet shouldBe bytes
          file.close.assertGet
      }
      //above onOpen is also invoked
    }

    "fail write if the slice is partially written" in {
      val testFile = randomFilePath
      val bytes = Slice.create[Byte](10)
      bytes.addIntUnsigned(1)
      bytes.addIntUnsigned(2)

      bytes.written shouldBe 2

      val channelFile = DBFile.channelWrite(testFile).assertGet
      channelFile.append(bytes).failed.assertGet shouldBe SegmentException.FailedToWriteAllBytes(10, 2, bytes.size)
      channelFile.close.assertGet
    }

    "fail initialisation if the file already exists" in {
      val testFile = randomFilePath

      DBFile.channelWrite(testFile).assertGet ==> {
        file =>
          file.existsOnDisk shouldBe true
          file.close.assertGet
      }
      //creating the same file again should fail
      DBFile.channelWrite(testFile).failed.assertGet.toString shouldBe new FileAlreadyExistsException(testFile.toString).toString
      //file remains unchanged
      DBFile.channelRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet.toArray shouldBe empty
          file.close.assertGet
      }
    }
  }

  "DBFile.channelRead" should {
    "initialise a FileChannel for reading only" in {
      val testFile = randomFilePath
      val bytes = randomBytesSlice()

      //opening a file should trigger the onOpen function
      val onOpen = mockFunction[DBFile, Unit]
      onOpen expects * onCall {
        (dbFile: DBFile) =>
          dbFile.path shouldBe testFile
          dbFile.isOpen shouldBe true
          dbFile.file shouldBe defined
          ()
      } repeat 3.times

      IO.write(bytes, testFile).assertGet

      val readFile = DBFile.channelRead(testFile, onOpen).assertGet
      //reading a file should load the file lazily
      readFile.isFileDefined shouldBe false
      readFile.isOpen shouldBe false
      //reading the opens the file
      readFile.readAll.assertGet shouldBe bytes
      //file is now opened
      readFile.isFileDefined shouldBe true
      readFile.isOpen shouldBe true

      //writing fails since the file is readonly
      readFile.append(bytes).failed.assertGet shouldBe a[NonWritableChannelException]
      //data remain unchanged
      DBFile.channelRead(testFile, onOpen).assertGet.readAll.assertGet shouldBe bytes

      readFile.close.assertGet
      readFile.isOpen shouldBe false
      readFile.isFileDefined shouldBe false
      //read bytes one by one
      (0 until bytes.size) foreach {
        index =>
          readFile.get(index).assertGet shouldBe bytes(index)
      }
      readFile.isOpen shouldBe true

      readFile.close.assertGet
    }

    "fail initialisation if the file does not exists" in {
      DBFile.channelRead(randomFilePath).failed.assertGet shouldBe a[NoSuchFileException]
    }
  }

  "DBFile.mmapWriteAndRead" should {
    "write bytes to a File, extend the buffer on overflow and reopen it for reading via mmapRead" in {
      val testFile = randomFilePath
      val bytes = Slice("bytes one".getBytes())

      //opening a file should trigger the onOpen function
      val onOpen = mockFunction[DBFile, Unit]
      onOpen expects * onCall {
        (dbFile: DBFile) =>
          dbFile.path shouldBe testFile
          dbFile.isOpen shouldBe true
          dbFile.file shouldBe defined
          ()
      } repeat 3.times

      val file = DBFile.mmapWriteAndRead(bytes, testFile, onOpen).assertGet
      file.readAll.assertGet shouldBe bytes
      file.isFull.assertGet shouldBe true

      //overflow bytes
      val bytes2 = Slice("bytes two".getBytes())
      file.append(bytes2).assertGet
      file.isFull.assertGet shouldBe true //complete fit - no extra bytes

      //overflow bytes
      val bytes3 = Slice("bytes three".getBytes())
      file.append(bytes3).assertGet
      file.isFull.assertGet shouldBe true //complete fit - no extra bytes

      val expectedBytes = bytes ++ bytes2 ++ bytes3

      file.readAll.assertGet.toArray shouldBe expectedBytes

      //close buffer
      file.close.assertGet
      file.isFileDefined shouldBe false
      file.isOpen shouldBe false
      file.readAll.assertGet.toArray shouldBe expectedBytes
      file.isFileDefined shouldBe true
      file.isOpen shouldBe true

      //writing fails since the file is now readonly
      file.append(bytes).failed.assertGet shouldBe a[ReadOnlyBufferException]
      file.close.assertGet

      //open read only buffer
      DBFile.mmapRead(testFile, onOpen).assertGet ==> {
        file =>
          file.readAll.assertGet.toArray shouldBe expectedBytes
          file.close.assertGet
      }
    }

    "fail write if the slice is partially written" in {
      val testFile = randomFilePath
      val bytes = Slice.create[Byte](10)
      bytes.addIntUnsigned(1)
      bytes.addIntUnsigned(2)

      bytes.written shouldBe 2

      DBFile.mmapWriteAndRead(bytes, testFile).failed.assertGet shouldBe SegmentException.FailedToWriteAllBytes(0, 2, bytes.size)
    }

    "fail to write if the file already exists" in {
      val testFile = randomFilePath
      val bytes = randomBytesSlice()

      DBFile.mmapWriteAndRead(bytes, testFile).assertGet.close.assertGet
      DBFile.mmapWriteAndRead(bytes, testFile).failed.assertGet shouldBe a[FileAlreadyExistsException] //creating the same file again should fail
      //file remains unchanged
      DBFile.mmapRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet shouldBe bytes
          file.close.assertGet
      }
    }
  }

  "DBFile.mmapRead" should {
    "open an existing file for reading" in {
      val testFile = randomFilePath
      val bytes = Slice("bytes one".getBytes())

      DBFile.write(bytes, testFile).assertGet

      val readFile = DBFile.mmapRead(testFile).assertGet

      def doRead = {
        readFile.isFileDefined shouldBe false //reading a file should load the file lazily
        readFile.isOpen shouldBe false
        readFile.readAll.assertGet shouldBe bytes
        readFile.isFileDefined shouldBe true
        readFile.isOpen shouldBe true
      }

      doRead

      //close and read again
      readFile.close.assertGet
      doRead

      DBFile.write(bytes, testFile).failed.assertGet shouldBe a[FileAlreadyExistsException] //creating the same file again should fail

      readFile.close.assertGet
    }

    "fail to read if the file does not exists" in {
      DBFile.mmapRead(randomFilePath).failed.assertGet shouldBe a[NoSuchFileException]
    }
  }

  "DBFile.mmapInit" should {
    "open a file for writing" in {
      val testFile = randomFilePath
      val bytes1 = Slice("bytes one".getBytes())
      val bytes2 = Slice("bytes two".getBytes())
      val bytes3 = Slice("bytes three".getBytes())
      val bytes4 = Slice("bytes four".getBytes())

      val file = DBFile.mmapInit(testFile, bytes1.size + bytes2.size + bytes3.size).assertGet
      file.append(bytes1).assertGet
      file.isFull.assertGet shouldBe false
      file.append(bytes2).assertGet
      file.isFull.assertGet shouldBe false
      file.append(bytes3).assertGet
      file.isFull.assertGet shouldBe true
      file.append(bytes4).assertGet //overflow write, buffer gets extended
      file.isFull.assertGet shouldBe true

      file.readAll.assertGet.toArray shouldBe (bytes1 ++ bytes2 ++ bytes3 ++ bytes4)

      file.close.assertGet
    }

    "fail to initialise if it already exists" in {
      val testFile = randomFilePath
      DBFile.write(Slice(randomBytes()), testFile).assertGet

      DBFile.mmapInit(testFile, 10).failed.assertGet shouldBe a[FileAlreadyExistsException]
    }
  }

  "DBFile.close" should {
    "close a file channel and reopen on read" in {
      val testFile = randomFilePath
      val bytes = randomBytesSlice()

      //opening a file should trigger the onOpen function
      val onOpen = mockFunction[DBFile, Unit]
      onOpen expects * onCall {
        (dbFile: DBFile) =>
          dbFile.path shouldBe testFile
          dbFile.isOpen shouldBe true
          dbFile.file shouldBe defined
          ()
      } repeat 4.times

      val file = DBFile.channelWrite(testFile, onOpen).assertGet
      file.append(bytes).assertGet

      def close = {
        file.close().assertGet
        file.isOpen shouldBe false
        file.isFileDefined shouldBe false
        file.existsOnDisk shouldBe true
      }

      def open = {
        file.read(0, bytes.size).assertGet shouldBe bytes
        file.isOpen shouldBe true
        file.isFileDefined shouldBe true
      }

      close
      //closing an already closed channel should not fail
      close
      open

      close
      open

      close
      open

      close
    }

    "close a memory mapped file and reopen on read" in {
      val testFile = randomFilePath
      val bytes = randomBytesSlice()

      val file = DBFile.mmapInit(testFile, bytes.size).assertGet
      file.append(bytes).assertGet

      def close = {
        file.close().assertGet
        file.isOpen shouldBe false
        file.isFileDefined shouldBe false
        file.existsOnDisk shouldBe true
      }

      def open = {
        file.read(0, bytes.size).assertGet shouldBe bytes
        file.isOpen shouldBe true
        file.isFileDefined shouldBe true
      }

      //closing multiple times should not fail
      close
      close
      close

      open

      close
      open

      close
      open

      close
    }

    "close a FileChannel and then reopening the file should open it in read only mode" in {
      val testFile = randomFilePath
      val bytes = randomBytesSlice()

      val file = DBFile.channelWrite(testFile).assertGet
      file.append(bytes).assertGet
      file.close.assertGet

      file.append(bytes).failed.assertGet shouldBe a[NonWritableChannelException]
      file.readAll.assertGet shouldBe bytes

      file.close.assertGet
    }

    "close that MMAPFile and reopening the file should open it in read only mode" in {
      val testFile = randomFilePath
      val bytes = randomBytesSlice()

      val file = DBFile.mmapInit(testFile, bytes.size).assertGet
      file.append(bytes).assertGet
      file.close.assertGet

      file.append(bytes).failed.assertGet shouldBe a[ReadOnlyBufferException]
      file.readAll.assertGet shouldBe bytes

      file.close.assertGet
    }
  }

  "DBFile.append" should {
    "append bytes to the end of the ChannelFile" in {
      val testFile = randomFilePath
      val bytes = List(randomBytesSlice(), randomBytesSlice(), randomBytesSlice())

      val file = DBFile.channelWrite(testFile).assertGet
      file.append(bytes(0)).assertGet
      file.append(bytes(1)).assertGet
      file.append(bytes(2)).assertGet
      file.read(0, 1).isFailure shouldBe true //not open for read

      file.close.assertGet

      val expectedAllBytes = bytes.foldLeft(List.empty[Byte])(_ ++ _).toArray

      DBFile.channelRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet.toArray shouldBe expectedAllBytes
          file.close.assertGet
      }
      DBFile.mmapRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet.toArray shouldBe expectedAllBytes
          file.close.assertGet
      }

      file.close.assertGet
    }

    "append bytes to the end of the MMAP file" in {
      val testFile = randomFilePath
      val bytes = List(randomBytesSlice(), randomBytesSlice(), randomBytesSlice())

      val allBytesSize = bytes.foldLeft(0)(_ + _.size)
      val file = DBFile.mmapInit(testFile, allBytesSize).assertGet
      file.append(bytes(0)).assertGet
      file.append(bytes(1)).assertGet
      file.append(bytes(2)).assertGet
      file.get(0).assertGet shouldBe bytes.head.head
      file.get(allBytesSize - 1).assertGet shouldBe bytes.last.last

      val expectedAllBytes = bytes.foldLeft(List.empty[Byte])(_ ++ _).toArray

      file.readAll.assertGet.toArray shouldBe expectedAllBytes
      file.close.assertGet //close

      //reopen
      DBFile.mmapRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet.toArray shouldBe expectedAllBytes
          file.close.assertGet
      }
      DBFile.channelRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet.toArray shouldBe expectedAllBytes
          file.close.assertGet
      }
    }

    "append bytes by extending an overflown buffer of MMAP file" in {
      val testFile = randomFilePath
      val bytes = List(randomBytesSlice(), randomBytesSlice(), randomBytesSlice(), randomBytesSlice(), randomBytesSlice())
      val allBytesSize = bytes.foldLeft(0)(_ + _.size)

      val file = DBFile.mmapInit(testFile, bytes.head.size).assertGet
      file.append(bytes(0)).assertGet
      file.append(bytes(1)).assertGet
      file.append(bytes(2)).assertGet
      file.append(bytes(3)).assertGet
      file.append(bytes(4)).assertGet
      file.get(0).assertGet shouldBe bytes.head.head
      file.get(allBytesSize - 1).assertGet shouldBe bytes.last.last

      val expectedAllBytes = bytes.foldLeft(List.empty[Byte])(_ ++ _).toArray

      file.readAll.assertGet.toArray shouldBe expectedAllBytes
      file.close.assertGet //close

      //reopen
      DBFile.mmapRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet.toArray shouldBe expectedAllBytes
          file.close.assertGet
      }
      DBFile.channelRead(testFile).assertGet ==> {
        file =>
          file.readAll.assertGet.toArray shouldBe expectedAllBytes
          file.close.assertGet
      }
    }

    "not fail when appending empty bytes to ChannelFile" in {
      val file = DBFile.channelWrite(randomFilePath).assertGet
      file.append(Slice.create[Byte](0)).assertGet
      DBFile.channelRead(file.path).assertGet ==> {
        file =>
          file.readAll.assertGet.toArray shouldBe empty
          file.close.assertGet
      }
      file.close.assertGet
    }

    "not fail when appending empty bytes to MMAPFile" in {
      val file = DBFile.mmapInit(randomFilePath, 100).assertGet
      file.append(Slice.create[Byte](0)).assertGet
      file.readAll.assertGet.toArray shouldBe Array.fill(file.fileSize.get.toInt)(0)
      file.close.assertGet

      DBFile.mmapRead(file.path).assertGet ==> {
        file2 =>
          file2.readAll.assertGet.toArray shouldBe Array.fill(file.fileSize.get.toInt)(0)
          file2.close.assertGet
      }
    }
  }

  "DBFile.read and get" should {
    "read and get bytes at a position from a ChannelFile" in {
      val testFile = randomFilePath
      val bytes = randomBytesSlice(100)

      val file = DBFile.channelWrite(testFile).assertGet
      file.append(bytes).assertGet
      file.read(0, 1).isFailure shouldBe true //not open for read

      file.close.assertGet

      val readFile = DBFile.channelRead(testFile).assertGet

      (0 until bytes.size) foreach {
        index =>
          readFile.read(index, 1).assertGet should contain only bytes(index)
      }

      readFile.read(0, bytes.size / 2).assertGet.toList should contain theSameElementsInOrderAs bytes.dropRight(bytes.size / 2).toList
      readFile.read(bytes.size / 2, bytes.size / 2).assertGet.toList should contain theSameElementsInOrderAs bytes.drop(bytes.size / 2).toList
      readFile.get(1000).assertGet shouldBe 0

      readFile.close.assertGet
    }
  }

  "DBFile.memory" should {
    "create an immutable DBFile" in {
      val path = randomFilePath
      val bytes = randomBytesSlice(100)

      val file = DBFile.memory(path, bytes).assertGet
      //cannot write to a memory file as it's immutable
      file.append(bytes).failed.assertGet shouldBe a[UnsupportedOperationException]
      file.isFull.assertGet shouldBe true
      file.isOpen shouldBe true
      file.existsOnDisk shouldBe false

      file.readAll.assertGet shouldBe bytes

      (0 until bytes.size) foreach {
        index =>
          val readBytes = file.read(index, 1).assertGet
          readBytes.underlyingArraySize shouldBe bytes.size
          readBytes.head shouldBe bytes(index)
          file.get(index).assertGet shouldBe bytes(index)
      }

      file.close.assertGet
    }

    "exist in memory after being closed" in {
      val path = randomFilePath
      val bytes = randomBytesSlice(100)

      val file = DBFile.memory(path, bytes).assertGet
      file.isFull.assertGet shouldBe true
      file.isOpen shouldBe true
      file.existsOnDisk shouldBe false
      file.isFileDefined shouldBe true
      file.fileSize.assertGet shouldBe bytes.size

      file.close.assertGet

      file.isFull.assertGet shouldBe true
      //in memory files are never closed
      file.isOpen shouldBe false
      file.existsOnDisk shouldBe false
      //memory files are not remove from DBFile's reference when they closed.
      file.isFileDefined shouldBe true
      file.fileSize.assertGet shouldBe bytes.size

      //reading an in-memory file
      file.readAll.assertGet shouldBe bytes
      //      file.isOpen shouldBe true

      file.close.assertGet
    }
  }

  "DBFile.delete" should {
    "delete a ChannelFile" in {
      val bytes = randomBytesSlice(100)

      val file = DBFile.channelWrite(randomFilePath).assertGet
      file.append(bytes).assertGet

      file.delete().assertGet
      file.existsOnDisk shouldBe false
      file.isOpen shouldBe false
      file.isFileDefined shouldBe false
    }

    "delete a MMAPFile" in {
      val file = DBFile.mmapWriteAndRead(randomBytesSlice(), randomFilePath).assertGet
      file.close.assertGet

      file.delete().assertGet
      file.existsOnDisk shouldBe false
      file.isOpen shouldBe false
      file.isFileDefined shouldBe false
    }

    "delete a MemoryFile" in {
      val file = DBFile.memory(randomFilePath, randomBytesSlice()).assertGet
      file.close.assertGet

      file.delete().assertGet
      file.existsOnDisk shouldBe false
      file.isOpen shouldBe false
      file.isFileDefined shouldBe false
      //bytes are nulled to be garbage collected
      file.get(0).failed.assertGet shouldBe a[NoSuchFileException]
      file.isOpen shouldBe false
    }
  }

  "DBFile.copy" should {
    "copy a ChannelFile" in {
      val bytes = randomBytesSlice(100)

      val file = DBFile.channelWrite(randomFilePath).assertGet
      file.append(bytes).assertGet

      val targetFile = randomFilePath
      file.copyTo(targetFile).assertGet shouldBe targetFile

      DBFile.channelRead(targetFile).assertGet ==> {
        file =>
          file.readAll.assertGet shouldBe bytes
          file.close.assertGet
      }

      file.close.assertGet
    }

    "copy a MMAPFile" in {
      val bytes = randomBytesSlice(100)

      val file = DBFile.mmapInit(randomFilePath, bytes.size).assertGet
      file.append(bytes).assertGet
      file.isFull.assertGet shouldBe true
      file.close.assertGet

      val targetFile = randomFilePath
      file.copyTo(targetFile).assertGet shouldBe targetFile

      DBFile.channelRead(targetFile).assertGet ==> {
        file =>
          file.readAll.assertGet shouldBe bytes
          file.close.assertGet
      }
    }

    "fail when copying a MemoryFile" in {
      val bytes = randomBytesSlice(100)
      val file = DBFile.memory(randomFilePath, bytes).assertGet

      file.copyTo(randomFilePath).failed.assertGet shouldBe CannotCopyInMemoryFiles(file.path)

    }
  }

}
