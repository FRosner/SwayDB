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

package swaydb.core

import swaydb.core.data.Memory
import swaydb.core.map.serializer.{MapEntryReader, MapEntryWriter}
import swaydb.data.slice.Slice

import scala.util.Random
import swaydb.data.util.StorageUnits._

import scala.concurrent.ExecutionContext

package object map extends TryAssert {

  //cannot be added to TestBase because PersistentMap cannot leave the map package.
  implicit class ReopenMap(map: PersistentMap[Slice[Byte], Memory]) {
    def reopen(implicit ordering: Ordering[Slice[Byte]],
               ec: ExecutionContext,
               writer: MapEntryWriter[MapEntry.Put[Slice[Byte], Memory]],
               reader: MapEntryReader[MapEntry[Slice[Byte], Memory]],
               skipListMerge: SkipListMerge[Slice[Byte], Memory]) = {
      map.close().assertGet
      Map.persistent[Slice[Byte], Memory](map.path, mmap = Random.nextBoolean(), flushOnOverflow = Random.nextBoolean(), 10.mb, dropCorruptedTailEntries = false).assertGet.item
    }
  }
}