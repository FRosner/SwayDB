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

package swaydb.core.merge

import org.scalatest.WordSpec
import swaydb.core.CommonAssertions
import swaydb.core.data.Memory
import swaydb.order.KeyOrder
import swaydb.serializers.Default._
import swaydb.serializers._

import scala.concurrent.duration._

class SegmentMerger7_Update_None_None_Into_Put_Spec extends WordSpec with CommonAssertions {

  implicit val ordering = KeyOrder.default

  /**
    * Update None None -> Put None None
    */
  "Update None None -> Put None None" when {
    "Put None None" in {
      assertMerge(
        newKeyValue = Memory.Update(1, None, None),
        oldKeyValue = Memory.Put(1, None, None),
        expected = Memory.Put(1, None, None),
        lastLevelExpect = Memory.Put(1, None, None),
        hasTimeLeftAtLeast = 10.seconds
      )
    }
  }

  /**
    * Update None None -> Put None Some
    */
  "Update None None -> Put None None" when {
    "Put None HasTimeLeft" in {
      val deadline = 20.seconds.fromNow
      assertMerge(
        newKeyValue = Memory.Update(1, None, None),
        oldKeyValue = Memory.Put(1, None, deadline),
        expected = Memory.Put(1, None, deadline),
        lastLevelExpect = Memory.Put(1, None, deadline),
        hasTimeLeftAtLeast = 10.seconds
      )
    }

    "Put None HasNoTimeLeft" in {
      val deadline = 2.seconds.fromNow
      assertMerge(
        newKeyValue = Memory.Update(1, None, None),
        oldKeyValue = Memory.Put(1, None, deadline),
        expected = Memory.Put(1, None, deadline),
        lastLevelExpect = Memory.Put(1, None, deadline),
        hasTimeLeftAtLeast = 10.seconds
      )
    }

    "Put None Expired" in {
      val deadline = expiredDeadline()
      assertMerge(
        newKeyValue = Memory.Update(1, None, None),
        oldKeyValue = Memory.Put(1, None, deadline),
        expected = Memory.Put(1, None, deadline),
        lastLevelExpect = None,
        hasTimeLeftAtLeast = 10.seconds
      )
    }
  }

  /**
    * Update None None -> Put Some None
    */
  "Update None None -> Put Some None" when {
    "Put Some None" in {
      assertMerge(
        newKeyValue = Memory.Update(1, None, None),
        oldKeyValue = Memory.Put(1, 1, None),
        expected = Memory.Put(1, None, None),
        lastLevelExpect = Memory.Put(1, None, None),
        hasTimeLeftAtLeast = 10.seconds
      )
    }
  }

  /**
    * Update None None -> Put Some Some
    */
  "Update None None -> Put Some Some" when {
    "Put Some HasTimeLeft" in {
      val deadline = 20.seconds.fromNow
      assertMerge(
        newKeyValue = Memory.Update(1, None, None),
        oldKeyValue = Memory.Put(1, 1, deadline),
        expected = Memory.Put(1, None, deadline),
        lastLevelExpect = Memory.Put(1, None, deadline),
        hasTimeLeftAtLeast = 10.seconds
      )
    }

    "Put Some HasNoTimeLeft" in {
      val deadline = 2.seconds.fromNow
      assertMerge(
        newKeyValue = Memory.Update(1, None, None),
        oldKeyValue = Memory.Put(1, 1, deadline),
        expected = Memory.Put(1, None, deadline),
        lastLevelExpect = Memory.Put(1, None, deadline),
        hasTimeLeftAtLeast = 10.seconds
      )
    }

    "Put Some Expired" in {
      val deadline = expiredDeadline()
      assertMerge(
        newKeyValue = Memory.Update(1, None, None),
        oldKeyValue = Memory.Put(1, 1, deadline),
        expected = Memory.Put(1, None, deadline),
        lastLevelExpect = None,
        hasTimeLeftAtLeast = 10.seconds
      )
    }
  }

}
