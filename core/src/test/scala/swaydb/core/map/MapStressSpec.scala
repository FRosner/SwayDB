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

package swaydb.core.map

import swaydb.core.TestBase
import swaydb.core.data.Value
import swaydb.core.map.serializer.LevelZeroMapEntryWriter.Level0AddWriter
import swaydb.data.slice.Slice
import swaydb.data.util.StorageUnits._
import swaydb.order.KeyOrder

class MapStressSpec extends TestBase {

  implicit val ordering: Ordering[Slice[Byte]] = KeyOrder.default

  "Map" should {
    "write entries when flushOnOverflow is true and map size is 1.kb" in {
      val keyValues = randomIntKeyValues(100)

      def test(map: Map[Slice[Byte], Value]) = {
        keyValues foreach {
          keyValue =>
            val entry = MapEntry.Add[Slice[Byte], Value.Put](keyValue.key, Value.Put(keyValue.getOrFetchValue.assertGetOpt))(Level0AddWriter)
            map.write(entry).assertGet shouldBe true
        }

        testRead(map)
      }

      def testRead(map: Map[Slice[Byte], Value]) = {
        keyValues foreach {
          keyValue =>
            map.get(keyValue.key).map(_._2) shouldBe Option(Value.Put(keyValue.getOrFetchValue.assertGetOpt))
        }
      }

      val dir1 = createRandomDir
      val dir2 = createRandomDir

      import swaydb.core.map.serializer.LevelZeroMapEntryReader.Level0Reader
      import swaydb.core.map.serializer.LevelZeroMapEntryWriter.Level0AddValueWriter

      test(Map.persistent[Slice[Byte], Value](dir1, mmap = true, flushOnOverflow = true, 1.kb, dropCorruptedTailEntries = false).assertGet.item)
      test(Map.persistent[Slice[Byte], Value](dir2, mmap = false, flushOnOverflow = true, 1.kb, dropCorruptedTailEntries = false).assertGet.item)
      test(Map.memory(flushOnOverflow = true, fileSize = 1.kb))

      //reopen - all the entries should get recovered for persistent maps. Also switch mmap types.
      testRead(Map.persistent[Slice[Byte], Value](dir1, mmap = false, flushOnOverflow = true, 1.kb, dropCorruptedTailEntries = false).assertGet.item)
      testRead(Map.persistent[Slice[Byte], Value](dir2, mmap = true, flushOnOverflow = true, 1.kb, dropCorruptedTailEntries = false).assertGet.item)

      //write the same data again
      test(Map.persistent[Slice[Byte], Value](dir1, mmap = true, flushOnOverflow = true, 1.kb, dropCorruptedTailEntries = false).assertGet.item)
      test(Map.persistent[Slice[Byte], Value](dir2, mmap = false, flushOnOverflow = true, 1.kb, dropCorruptedTailEntries = false).assertGet.item)

      //read again
      testRead(Map.persistent[Slice[Byte], Value](dir1, mmap = false, flushOnOverflow = true, 1.kb, dropCorruptedTailEntries = false).assertGet.item)
      testRead(Map.persistent[Slice[Byte], Value](dir2, mmap = true, flushOnOverflow = true, 1.kb, dropCorruptedTailEntries = false).assertGet.item)
    }
  }
}
