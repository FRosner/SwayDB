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

import java.util.concurrent.ConcurrentSkipListMap

import bloomfilter.mutable.BloomFilter
import org.scalatest.Assertion
import swaydb.core.data.KeyValue.{ReadOnly, WriteOnly}
import swaydb.core.data.Value.FromValue
import swaydb.core.data.{Memory, Value, _}
import swaydb.core.level.zero.{LevelZero, LevelZeroRef, LevelZeroSkipListMerge}
import swaydb.core.level.{Level, LevelRef}
import swaydb.core.map.MapEntry
import swaydb.core.map.serializer.MapEntryWriter
import swaydb.core.segment.format.one.{KeyMatcher, SegmentReader}
import swaydb.core.segment.{KeyValueMerger, Segment, SegmentMerger}
import swaydb.data.slice.{Reader, Slice}
import swaydb.data.util.StorageUnits._
import swaydb.order.KeyOrder

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Random, Try}

trait CommonAssertions extends TryAssert with FutureBase with TestData {

  def randomly[T](f: => T): Option[T] =
    if (Random.nextBoolean())
      Some(f)
    else
      None

  def eitherOne[T](left: => T, right: => T): T =
    if (Random.nextBoolean())
      left
    else
      right

  def anyOrder[T](execution1: => T, execution2: => T): Unit =
    if (Random.nextBoolean()) {
      execution1
      execution2
    } else {
      execution2
      execution1
    }

  def runThis(times: Int)(f: => Unit): Unit =
    (1 to times) foreach {
      i =>
        println(s"Iteration number: $i")
        f
    }

  implicit class IsExpectedInLastLevel(fromValue: FromValue) {
    def toExpectedLastLevelKeyValue(key: Slice[Byte]): Option[Memory.Fixed] =
      fromValue match {
        case _: Value.Remove =>
          None
        case Value.Put(value, deadline) =>
          if (deadline.forall(_.hasTimeLeft()))
            Some(Memory.Put(key, value, deadline))
          else
            None
        case _: Value.Update =>
          None
      }
  }

  implicit class IsKeyValueExpectedInLastLevel(keyValue: Memory.Fixed) {
    def isExpectedInLastLevel: Boolean =
      keyValue match {
        case Memory.Put(key, value, deadline) =>
          if (deadline.forall(_.hasTimeLeft()))
            true
          else
            false
        case _: Memory.Update =>
          false
        case _: Memory.Remove =>
          false
      }
  }

  implicit class ApplyValue(keyValues: (Memory.Fixed, Memory.Fixed)) {
    def merge: ReadOnly.Fixed =
      KeyValueMerger.applyValue(keyValues._1, keyValues._2, 10.seconds).assertGet
  }

  implicit class KeyValueWriteOnlyImplicits(keyValues: Iterable[KeyValue.WriteOnly]) {

    //    def print = {
    //      val skipList = new ConcurrentSkipListMap[Slice[Byte], Value](KeyOrder.default)
    //      keyValues.map(_.toValueTry.assertGet) foreach {
    //        case (key, value) =>
    //          skipList.put(key, value)
    //      }
    //      skipList.print
    //    }

    def toMemory: Slice[Memory] = {
      val slice = Slice.create[Memory](keyValues.size)

      keyValues foreach {
        keyValue =>
          slice add keyValue.toMemory
      }
      slice
    }
  }

  implicit class ToSlice[T: ClassTag](items: Iterable[T]) {
    def toSlice: Slice[T] = {
      val slice = Slice.create[T](items.size)
      items foreach {
        item =>
          slice add item
      }
      slice
    }
  }

  implicit class WriteOnlyToMemory(keyValue: KeyValue.WriteOnly) {
    def toMemory: Memory = {
      keyValue match {
        case fixed: KeyValue.WriteOnly.Fixed =>
          fixed match {
            case Transient.Remove(key, deadline, stats) =>
              Memory.Remove(key, deadline)
            case Transient.Update(key, value, deadline, stats) =>
              Memory.Update(key, value, deadline)
            case Transient.Put(key, value, deadline, stats) =>
              Memory.Put(key, value, deadline)
          }

        case range: KeyValue.WriteOnly.Range =>
          range match {
            case Transient.Range(id, fromKey, toKey, fullKey, fromValue, rangeValue, value, stats) =>
              Memory.Range(fromKey, toKey, fromValue, rangeValue)
          }
      }
    }
  }

  implicit class WriteOnlysToMemory(keyValues: Iterable[KeyValue]) {
    def toMemory: Iterable[Memory] = {
      keyValues map {
        case readOnly: ReadOnly =>
          readOnly.toMemory
        case writeOnly: WriteOnly =>
          writeOnly.toMemory

      }
    }
  }

  implicit class ReadOnlyToMemory(keyValues: Iterable[KeyValue.ReadOnly]) {
    def toTransient: Slice[Transient] = {
      val slice = Slice.create[Transient](keyValues.size)

      keyValues foreach {
        keyValue =>
          slice add keyValue.toTransient.updateStats(0.1, slice.lastOption).asInstanceOf[Transient]
      }
      slice
    }

    def toMemory: Slice[Memory] = {
      val slice = Slice.create[Memory](keyValues.size)

      keyValues foreach {
        keyValue =>
          slice add keyValue.toMemory
      }
      slice
    }
  }

  implicit class ReadOnlyKeyValueToMemory(keyValue: KeyValue.ReadOnly) {

    import swaydb.core.map.serializer.RangeValueSerializers._

    def toTransient: Transient = {
      keyValue match {
        case memory: Memory =>
          memory match {
            case fixed: Memory.Fixed =>
              fixed match {
                case Memory.Put(key, value, deadline) =>
                  Transient.Put(key, value, 0.1, None, deadline)

                case Memory.Update(key, value, deadline) =>
                  Transient.Update(key, value, 0.1, None, deadline)

                case Memory.Remove(key, deadline) =>
                  Transient.Remove(key, 0.1, None, deadline)
              }
            case Memory.Range(fromKey, toKey, fromValue, rangeValue) =>
              Transient.Range[Value.FromValue, Value.RangeValue](fromKey, toKey, fromValue, rangeValue, 0.1, None)
          }

        case persistent: Persistent =>
          persistent match {
            case persistent: Persistent.Fixed =>
              persistent match {
                case put @ Persistent.Put(key, deadline, valueReader, nextIndexOffset, nextIndexSize, indexOffset, valueOffset, valueLength) =>
                  Transient.Put(key, put.getOrFetchValue.assertGetOpt, 0.1, None, deadline)

                case put @ Persistent.Update(key, deadline, valueReader, nextIndexOffset, nextIndexSize, indexOffset, valueOffset, valueLength) =>
                  Transient.Update(key, put.getOrFetchValue.assertGetOpt, 0.1, None, deadline)

                case Persistent.Remove(_key, deadline, indexOffset, nextIndexOffset, nextIndexSize) =>
                  Transient.Remove(_key, 0.1, None, deadline)
              }

            case range @ Persistent.Range(id, _fromKey, _toKey, valueReader, nextIndexOffset, nextIndexSize, indexOffset, valueOffset, valueLength) =>
              val (fromValue, rangeValue) = range.fetchFromAndRangeValue.assertGet
              Transient.Range(_fromKey, _toKey, fromValue, rangeValue, 0.1, None)
          }
      }
    }

    def toMemory: Memory = {
      keyValue match {
        case memory: Memory =>
          memory

        case persistent: Persistent =>
          persistent match {
            case persistent: Persistent.Fixed =>
              persistent match {
                case put @ Persistent.Put(key, deadline, valueReader, nextIndexOffset, nextIndexSize, indexOffset, valueOffset, valueLength) =>
                  Memory.Put(key, put.getOrFetchValue.assertGetOpt, deadline)

                case put @ Persistent.Update(key, deadline, valueReader, nextIndexOffset, nextIndexSize, indexOffset, valueOffset, valueLength) =>
                  Memory.Update(key, put.getOrFetchValue.assertGetOpt, deadline)

                case Persistent.Remove(_key, deadline, indexOffset, nextIndexOffset, nextIndexSize) =>
                  Memory.Remove(_key, deadline)
              }

            case range @ Persistent.Range(id, _fromKey, _toKey, valueReader, nextIndexOffset, nextIndexSize, indexOffset, valueOffset, valueLength) =>
              val (fromValue, rangeValue) = range.fetchFromAndRangeValue.assertGet
              Memory.Range(_fromKey, _toKey, fromValue, rangeValue)
          }
      }
    }
  }

  implicit class PrintSkipList(skipList: ConcurrentSkipListMap[Slice[Byte], Memory]) {

    import swaydb.serializers.Default._
    import swaydb.serializers._

    //stringify the skipList so that it's readable
    def asString(value: Value): String =
      value match {
        case Value.Remove(deadline) =>
          s"Remove(deadline = $deadline)"
        case Value.Put(value, deadline) =>
          s"Put(${value.map(_.read[Int]).getOrElse("None")}, deadline = $deadline)"
        case Value.Update(value, deadline) =>
          s"Update(${value.map(_.read[Int]).getOrElse("None")}, deadline = $deadline)"
      }

    //    def print = {
    //      println {
    //        skipList.asScala.foldLeft("") {
    //          case (string, (key, value)) =>
    ////            string + "\n" + s"""${key.read[Int].toString} -> ${asString(value.toValue)}"""
    //          ???
    //        }
    //      }
    //      println
    //    }
  }

  def assertSkipListMerge(newKeyValues: Iterable[KeyValue.ReadOnly],
                          oldKeyValues: Iterable[KeyValue.ReadOnly],
                          expected: KeyValue.WriteOnly,
                          hasTimeLeftAtLeast: FiniteDuration)(implicit ordering: Ordering[Slice[Byte]]): ConcurrentSkipListMap[Slice[Byte], Memory] =
    assertSkipListMerge(newKeyValues, oldKeyValues, Slice(expected), hasTimeLeftAtLeast)

  def assertSkipListMerge(newKeyValues: Iterable[KeyValue.ReadOnly],
                          oldKeyValues: Iterable[KeyValue.ReadOnly],
                          expected: Iterable[KeyValue],
                          hasTimeLeftAtLeast: FiniteDuration)(implicit ordering: Ordering[Slice[Byte]]): ConcurrentSkipListMap[Slice[Byte], Memory] = {
    val skipList = new ConcurrentSkipListMap[Slice[Byte], Memory](ordering)
    (oldKeyValues ++ newKeyValues).map(_.toMemory) foreach (memory => LevelZeroSkipListMerge(hasTimeLeftAtLeast).insert(memory.key, memory, skipList))
    skipList.size() shouldBe expected.size
    skipList.asScala.toList shouldBe expected.map(keyValue => (keyValue.key, keyValue.toMemory))
    skipList
  }

  def assertMerge(newKeyValue: KeyValue.ReadOnly,
                  oldKeyValue: KeyValue.ReadOnly,
                  expected: Slice[KeyValue.WriteOnly],
                  hasTimeLeftAtLeast: FiniteDuration,
                  isLastLevel: Boolean = false)(implicit ordering: Ordering[Slice[Byte]]): Iterable[Iterable[KeyValue.WriteOnly]] =
    assertMerge(Slice(newKeyValue), Slice(oldKeyValue), expected, hasTimeLeftAtLeast, isLastLevel)

  def assertMerge(newKeyValues: Slice[KeyValue.ReadOnly],
                  oldKeyValues: Slice[KeyValue.ReadOnly],
                  expected: Slice[KeyValue.WriteOnly],
                  hasTimeLeftAtLeast: FiniteDuration,
                  isLastLevel: Boolean)(implicit ordering: Ordering[Slice[Byte]]): Iterable[Iterable[KeyValue.WriteOnly]] = {
    val result = SegmentMerger.merge(newKeyValues, oldKeyValues, 10.mb, isLastLevel = isLastLevel, false, 0.1, hasTimeLeftAtLeast = hasTimeLeftAtLeast).assertGet
    if (expected.size == 0) {
      result shouldBe empty
    } else {
      result should have size 1
      result.head should have size expected.size
      result.head.toList should contain inOrderElementsOf expected
    }
    result
  }

  def assertMerge(newKeyValue: KeyValue.ReadOnly,
                  oldKeyValue: KeyValue.ReadOnly,
                  expected: KeyValue.ReadOnly,
                  lastLevelExpect: KeyValue.ReadOnly,
                  hasTimeLeftAtLeast: FiniteDuration)(implicit ordering: Ordering[Slice[Byte]]): Iterable[Iterable[KeyValue.WriteOnly]] =
    assertMerge(newKeyValue, oldKeyValue, Slice(expected), Slice(lastLevelExpect), hasTimeLeftAtLeast)

  def assertMerge(newKeyValue: KeyValue.ReadOnly,
                  oldKeyValue: KeyValue.ReadOnly,
                  expected: KeyValue.ReadOnly,
                  lastLevelExpect: Option[KeyValue.ReadOnly],
                  hasTimeLeftAtLeast: FiniteDuration)(implicit ordering: Ordering[Slice[Byte]]): Unit = {
    //println("*** Expected assert ***")
    assertMerge(newKeyValue, oldKeyValue, Slice(expected), lastLevelExpect.map(Slice(_)).getOrElse(Slice.empty), hasTimeLeftAtLeast)
    //println("*** Skip list assert ***")
    assertSkipListMerge(Slice(newKeyValue), Slice(oldKeyValue), Slice(expected), hasTimeLeftAtLeast)
  }

  def assertMerge(newKeyValues: Slice[KeyValue.ReadOnly],
                  oldKeyValues: Slice[KeyValue.ReadOnly],
                  expected: Slice[KeyValue.ReadOnly],
                  lastLevelExpect: Slice[KeyValue.ReadOnly],
                  hasTimeLeftAtLeast: FiniteDuration)(implicit ordering: Ordering[Slice[Byte]]): Unit = {
    //println("*** Expected assert ***")
    assertMerge(newKeyValues, oldKeyValues, expected.toTransient, hasTimeLeftAtLeast, isLastLevel = false)
    //println("*** Expected last level ***")
    assertMerge(newKeyValues, oldKeyValues, lastLevelExpect.toTransient, hasTimeLeftAtLeast, isLastLevel = true)
    //println("*** Skip list assert ***")
    assertSkipListMerge(newKeyValues, oldKeyValues, expected, hasTimeLeftAtLeast)
  }

  def assertMerge(newKeyValue: KeyValue.ReadOnly,
                  oldKeyValue: KeyValue.ReadOnly,
                  expected: Slice[KeyValue.ReadOnly],
                  lastLevelExpect: Slice[KeyValue.ReadOnly],
                  hasTimeLeftAtLeast: FiniteDuration)(implicit ordering: Ordering[Slice[Byte]]): Iterable[Iterable[KeyValue.WriteOnly]] = {
    //println("*** Last level = false ***")
    assertMerge(Slice(newKeyValue), Slice(oldKeyValue), expected.toTransient, hasTimeLeftAtLeast, isLastLevel = false)
    //println("*** Last level = true ***")
    assertMerge(Slice(newKeyValue), Slice(oldKeyValue), lastLevelExpect.toTransient, hasTimeLeftAtLeast, isLastLevel = true)
  }

  def assertMerge(newKeyValues: Slice[KeyValue.ReadOnly],
                  oldKeyValues: Slice[KeyValue.ReadOnly],
                  expected: KeyValue.WriteOnly,
                  hasTimeLeftAtLeast: FiniteDuration,
                  isLastLevel: Boolean)(implicit ordering: Ordering[Slice[Byte]]): Iterable[Iterable[KeyValue.WriteOnly]] =
    assertMerge(newKeyValues, oldKeyValues, Slice(expected), hasTimeLeftAtLeast, isLastLevel)

  implicit class SliceKeyValueImplicits(actual: Iterable[KeyValue]) {
    def shouldBe(expected: Iterable[KeyValue]): Unit = {
      actual.size shouldBe expected.size
      actual.zip(expected) foreach {
        case (left, right) =>
          left shouldBe right
      }
    }

    def toMapEntry(implicit serializer: MapEntryWriter[MapEntry.Put[Slice[Byte], Memory]]) =
      actual.foldLeft(Option.empty[MapEntry[Slice[Byte], Memory]]) {
        case (mapEntry, keyValue) =>
          val newEntry = MapEntry.Put[Slice[Byte], Memory](keyValue.key, keyValue.toMemory)
          mapEntry.map(_ ++ newEntry) orElse Some(newEntry)
      }
  }

  implicit class MemoryImplicits(actual: Iterable[Memory]) {
    def toMapEntry(implicit serializer: MapEntryWriter[MapEntry.Put[Slice[Byte], Memory]]) =
      actual.foldLeft(Option.empty[MapEntry[Slice[Byte], Memory]]) {
        case (mapEntry, keyValue) =>
          val newEntry = MapEntry.Put[Slice[Byte], Memory](keyValue.key, keyValue)
          mapEntry.map(_ ++ newEntry) orElse Some(newEntry)
      }
  }

  implicit class SegmentsImplicits(actual: Iterable[Segment]) {
    def shouldHaveSameInOrderedIds(expected: Iterable[Segment]): Unit = {
      actual.map(_.path) shouldBe expected.map(_.path)
    }

    def shouldHaveSameIds(expected: Iterable[Segment]): Unit = {
      actual.map(_.path) should contain allElementsOf expected.map(_.path)
    }

    def shouldHaveSameKeyValuesAs(expected: Iterable[Segment]): Unit = {
      Segment.getAllKeyValues(0.1, actual).assertGet shouldBe Segment.getAllKeyValues(0.1, expected).assertGet
    }
  }

  implicit class SliceByteImplicits(actual: Slice[Byte]) {
    def shouldHaveSameKey(expected: KeyValue): Unit = {
      actual shouldBe expected.key
    }
  }

  def getStats(keyValue: KeyValue): Option[Stats] =
    keyValue match {
      case _: KeyValue.ReadOnly =>
        None
      case keyValue: KeyValue.WriteOnly =>
        Some(keyValue.stats)
    }

  implicit class KeyValueImplicits(actual: KeyValue) {

    def toMemory: Memory =
      actual match {
        case readOnly: ReadOnly => readOnly.toMemory
        case writeOnly: WriteOnly => writeOnly.toMemory
      }

    def shouldBe(expected: KeyValue): Unit =
      actual.toMemory should be(expected.toMemory)
  }

  implicit class StatsOptionImplicits(actual: Option[Stats]) {
    def shouldBe(expected: Option[Stats], ignoreValueOffset: Boolean = false) = {
      if (actual.isDefined && expected.isDefined)
        actual.assertGet shouldBe(expected.assertGet, ignoreValueOffset)
    }
  }

  implicit class PersistentReadOnlyOptionImplicits(actual: Option[Persistent]) {
    def shouldBe(expected: Option[Persistent]) = {
      actual.isDefined shouldBe expected.isDefined
      if (actual.isDefined)
        actual.get shouldBe expected.get
    }
  }

  implicit class PersistentReadOnlyKeyValueOptionImplicits(actual: Option[Persistent]) {
    def shouldBe(expected: Option[KeyValue.WriteOnly]) = {
      actual.isDefined shouldBe expected.isDefined
      if (actual.isDefined)
        actual.get shouldBe expected.get
    }

    def shouldBe(expected: KeyValue.WriteOnly) =
      actual.assertGet shouldBe expected
  }

  implicit class PersistentReadOnlyKeyValueImplicits(actual: Persistent) {
    def shouldBe(expected: KeyValue.WriteOnly) = {
      expected match {
        case expectedRange: KeyValue.WriteOnly.Range =>
          actual should be(a[Persistent.Range])
          val actualRange = actual.asInstanceOf[Persistent.Range]
          actualRange.fromKey shouldBe expectedRange.fromKey
          actualRange.toKey shouldBe expectedRange.toKey
          actualRange.fetchFromAndRangeValue.assertGet shouldBe expectedRange.fetchFromAndRangeValue.assertGet
          actualRange.fetchFromValue.assertGetOpt shouldBe expectedRange.fetchFromValue.assertGetOpt
          actualRange.fetchRangeValue.assertGet shouldBe expectedRange.fetchRangeValue.assertGet
          actualRange.id shouldBe expectedRange.id
          actualRange.isRemove shouldBe expectedRange.isRemove

        case expected: KeyValue.WriteOnly.Fixed =>
          actual.key shouldBe expected.key
          actual.getOrFetchValue.assertGetOpt shouldBe expected.getOrFetchValue.assertGetOpt
          actual.asInstanceOf[KeyValue.ReadOnly.Fixed].deadline shouldBe expected.deadline
      }

    }
  }

  implicit class PersistentReadOnlyImplicits(actual: Persistent) {
    def shouldBe(expected: Persistent) = {
      expected match {
        case expectedRange: KeyValue.WriteOnly.Range =>
          actual should be(a[Persistent.Range])
          val actualRange = actual.asInstanceOf[Persistent.Range]
          actualRange.fromKey shouldBe expectedRange.fromKey
          actualRange.toKey shouldBe expectedRange.toKey
          actualRange.fetchFromValue.assertGetOpt shouldBe expectedRange.fetchFromValue.assertGetOpt
          actualRange.fetchRangeValue.assertGet shouldBe expectedRange.fetchRangeValue.assertGet
          actualRange.id shouldBe expectedRange.id
          actualRange.isRemove shouldBe expectedRange.isRemove

        case expected: KeyValue.WriteOnly.Fixed =>
          actual.key shouldBe expected.key
          actual.getOrFetchValue.assertGetOpt shouldBe expected.getOrFetchValue.assertGetOpt
          actual.asInstanceOf[KeyValue.WriteOnly.Fixed].deadline shouldBe expected.deadline
      }
    }
  }

  implicit class StatsImplicits(actual: Stats) {

    def shouldBe(expected: Stats, ignoreValueOffset: Boolean = false): Assertion = {
      actual.segmentSize shouldBe expected.segmentSize
      actual.valueLength shouldBe expected.valueLength
      if (!ignoreValueOffset && actual.valueLength != 0) {
        actual.valueOffset shouldBe expected.valueOffset
        actual.toValueOffset shouldBe expected.toValueOffset
      }
      actual.segmentSizeWithoutFooter shouldBe expected.segmentSizeWithoutFooter
      actual.segmentValuesSize shouldBe expected.segmentValuesSize
      actual.thisKeyValuesIndexSizeWithoutFooter shouldBe expected.thisKeyValuesIndexSizeWithoutFooter
      actual.thisKeyValuesSegmentSizeWithoutFooter shouldBe expected.thisKeyValuesSegmentSizeWithoutFooter
    }
  }

  implicit class SegmentImplicits(actual: Segment) {

    def shouldBe(expected: Segment): Unit = {
      actual.segmentSize shouldBe expected.segmentSize
      actual.minKey shouldBe expected.minKey
      actual.maxKey shouldBe expected.maxKey
      actual.existsOnDisk shouldBe expected.existsOnDisk
      actual.path shouldBe expected.path
      assertReads(expected.getAll().assertGet, actual)
    }

    def shouldContainAll(keyValues: Slice[KeyValue]): Unit =
      keyValues.foreach {
        keyValue =>
          actual.get(keyValue.key).assertGet shouldBe keyValue
      }
  }

  implicit class MapEntryImplicits(actual: MapEntry[Slice[Byte], Memory]) {

    def shouldBe(expected: MapEntry[Slice[Byte], Memory]): Unit = {
      actual.entryBytesSize shouldBe expected.entryBytesSize
      actual.totalByteSize shouldBe expected.totalByteSize
      actual match {
        case MapEntry.Put(key, value) =>
          val exp = expected.asInstanceOf[MapEntry.Put[Slice[Byte], Memory]]
          key shouldBe exp.key
          value shouldBe exp.value

        case MapEntry.Remove(key) =>
          val exp = expected.asInstanceOf[MapEntry.Remove[Slice[Byte]]]
          key shouldBe exp.key

        case _ => //MapEntry is a batch of other MapEntries, iterate and assert.
          expected.entries.size shouldBe actual.entries.size
          expected.entries.zip(actual.entries) foreach {
            case (expected, actual) =>
              actual shouldBe expected
          }
      }
    }
  }

  //  implicit class MatchResultImplicits(actual: MatchResult) {
  //    def shouldBe(expected: MatchResult): Unit = {
  //      expected match {
  //        case Matched(result) =>
  //          actual match {
  //            case Matched(actualResult) =>
  //              actualResult shouldBe result
  //            case _ =>
  //              fail(s"Expected ${classOf[Matched].getSimpleName} got $actual")
  //          }
  //        case Next =>
  //          actual match {
  //            case Next =>
  //              Assertions.succeed
  //            case _ =>
  //              fail(s"Expected ${Next.getClass.getSimpleName} got $actual")
  //          }
  //
  //        case Stop =>
  //          actual match {
  //            case Stop =>
  //              Assertions.succeed
  //            case _ =>
  //              fail(s"Expected ${Stop.getClass.getSimpleName} got $actual")
  //          }
  //      }
  //    }
  //  }

  implicit class SegmentsPersistentMapImplicits(actual: MapEntry[Slice[Byte], Segment]) {

    def shouldBe(expected: MapEntry[Slice[Byte], Segment]): Unit = {
      actual.entryBytesSize shouldBe expected.entryBytesSize

      val actualMap = new ConcurrentSkipListMap[Slice[Byte], Segment](KeyOrder.default)
      actual.applyTo(actualMap)

      val expectedMap = new ConcurrentSkipListMap[Slice[Byte], Segment](KeyOrder.default)
      expected.applyTo(expectedMap)

      actualMap.size shouldBe expectedMap.size

      val actualArray = actualMap.asScala.toArray
      val expectedArray = expectedMap.asScala.toArray

      actualArray.indices.foreach {
        i =>
          val actual = actualArray(i)
          val expected = expectedArray(i)
          actual._1 shouldBe expected._1
          actual._2 shouldBe expected._2
      }
    }
  }

  def assertHigher(keyValuesIterable: Iterable[KeyValue],
                   level: LevelRef): Unit = {
    val keyValues = keyValuesIterable.toSlice
    assertHigher(keyValues, getHigher = key => level.higher(key))
  }

  def assertLower(keyValuesIterable: Iterable[KeyValue],
                  level: LevelRef) = {
    val keyValues = keyValuesIterable.toArray

    @tailrec
    def assertLowers(index: Int) {
      if (index > keyValues.size - 1) {
        //end
      } else if (index == 0) {
        level.lower(keyValues(0).key).assertGetOpt shouldBe empty
        assertLowers(index + 1)
      } else {
        val expectedLowerKeyValue = keyValues(index - 1)
        val lower = level.lower(keyValues(index).key).assertGet
        lower.key shouldBe expectedLowerKeyValue.key
        lower.getOrFetchValue.assertGetOpt shouldBe expectedLowerKeyValue.getOrFetchValue.assertGetOpt
        assertLowers(index + 1)
      }
    }

    assertLowers(0)
  }

  def assertLowerFromThisLevel(keyValuesIterable: Iterable[KeyValue],
                               level: Level) = {
    val keyValues = keyValuesIterable.toArray

    @tailrec
    def assertLowers(index: Int) {
      if (index > keyValues.size - 1) {
        //end
      } else if (index == 0) {
        level.lowerInThisLevel(keyValues(0).key).assertGetOpt shouldBe empty
        assertLowers(index + 1)
      } else {
        val expectedLowerKeyValue = keyValues(index - 1)
        val lower = level.lowerInThisLevel(keyValues(index).key).assertGet
        lower.key shouldBe expectedLowerKeyValue.key
        lower.getOrFetchValue.assertGetOpt shouldBe expectedLowerKeyValue.getOrFetchValue.assertGetOpt
        assertLowers(index + 1)
      }
    }

    assertLowers(0)
  }

  def assertHigher(keyValues: Slice[KeyValue],
                   level: LevelZeroRef) = {
    @tailrec
    def assertHigher(index: Int) {
      //      println(s"assertLowers : ${index}")
      val lastIndex = keyValues.size - 1
      if (index > keyValues.size - 1) {
        //end
      } else if (index == lastIndex) {
        level.higher(keyValues(lastIndex).key).assertGetOpt shouldBe empty
        assertHigher(index + 1)
      } else {
        val expectedHigherKeyValue = keyValues(index + 1)
        val (higherKey, higherValue) = level.higher(keyValues(index).key).assertGet
        higherKey shouldBe expectedHigherKeyValue.key
        higherValue shouldBe expectedHigherKeyValue.getOrFetchValue.assertGetOpt
        assertHigher(index + 1)
      }
    }

    assertHigher(0)
  }

  def assertLower(keyValues: Slice[KeyValue],
                  level: LevelZeroRef) = {

    @tailrec
    def assertLowers(index: Int) {
      if (index > keyValues.size - 1) {
        //end
      } else if (index == 0) {
        level.lower(keyValues(0).key).assertGetOpt shouldBe empty
        assertLowers(index + 1)
      } else {
        val expectedLowerKeyValue = keyValues(index - 1)

        val (lowerKey, lowerValue) = level.lower(keyValues(index).key).assertGet
        lowerKey shouldBe expectedLowerKeyValue.key
        lowerValue shouldBe expectedLowerKeyValue.getOrFetchValue.assertGetOpt
        assertLowers(index + 1)
      }
    }

    assertLowers(0)
  }

  def assertGet(keyValues: Slice[KeyValue.WriteOnly],
                reader: Reader)(implicit ordering: Ordering[Slice[Byte]]) =
    keyValues foreach {
      keyValue =>
        SegmentReader.find(KeyMatcher.Get(keyValue.key), None, reader.copy()).assertGet shouldBe keyValue
    }

  def assertBloom(keyValues: Slice[KeyValue],
                  bloom: BloomFilter[Slice[Byte]])(implicit ordering: Ordering[Slice[Byte]]) =
    keyValues foreach {
      keyValue =>
        bloom.mightContain(keyValue.key) shouldBe true
    }

  def assertReads(keyValues: Slice[KeyValue],
                  segment: Segment) = {
    val asserts = Seq(() => assertGet(keyValues, segment), () => assertHigher(keyValues, segment), () => assertLower(keyValues, segment))
    Random.shuffle(asserts).foreach(_ ())
  }

  def assertReads(keyValues: Iterable[KeyValue],
                  level: LevelRef) = {
    val asserts = Seq(() => assertGet(keyValues, level), () => assertHigher(keyValues, level), () => assertLower(keyValues, level))
    Random.shuffle(asserts).foreach(_ ())
  }

  def assertGetFromThisLevelOnly(keyValues: Iterable[KeyValue],
                                 level: Level) =
    keyValues foreach {
      keyValue =>
        val actual = level.getFromThisLevel(keyValue.key).assertGet
        actual.getOrFetchValue.assertGetOpt shouldBe keyValue.getOrFetchValue.assertGetOpt
    }

  def assertReads(keyValues: Slice[KeyValue.WriteOnly],
                  reader: Reader)(implicit ordering: Ordering[Slice[Byte]]) = {

    val footer = SegmentReader.readFooter(reader.copy()).assertGet
    //read fullIndex
    SegmentReader.readAll(footer, reader.copy()).assertGet shouldBe keyValues
    //find each KeyValue using all Matchers
    assertGet(keyValues, reader.copy())
    assertLower(keyValues, reader.copy())
    assertHigher(keyValues, reader.copy())
  }

  def assertGet(keyValues: Iterable[KeyValue],
                segment: Segment) =
    keyValues foreach {
      keyValue =>
        segment.get(keyValue.key).assertGet shouldBe keyValue
    }

  def assertGet(keyValues: Iterable[KeyValue],
                level: LevelRef) =
    keyValues foreach {
      keyValue =>
        level.get(keyValue.key).assertGet shouldBe keyValue
    }

  def assertGetNone(keyValues: Iterable[KeyValue],
                    level: LevelRef) =
    keyValues foreach {
      keyValue =>
        level.get(keyValue.key).assertGetOpt shouldBe empty
    }

  def assertGetNone(keyValues: Iterable[KeyValue],
                    level: LevelZero) =
    keyValues foreach {
      keyValue =>
        level.get(keyValue.key).assertGetOpt shouldBe None
    }

  def assertGetNoneFromThisLevelOnly(keyValues: Iterable[KeyValue],
                                     level: Level) =
    keyValues foreach {
      keyValue =>
        level.getFromThisLevel(keyValue.key).assertGetOpt shouldBe empty
    }

  def assertGet(keyValues: Iterable[KeyValue],
                level: LevelZeroRef) =
    keyValues foreach {
      keyValue =>
        level.get(keyValue.key).assertGet shouldBe keyValue.getOrFetchValue.assertGetOpt
    }

  def assertHeadLast(keyValues: Iterable[KeyValue],
                     zero: LevelZeroRef) = {
    val (headKey, headValue) = zero.head.assertGet
    headKey shouldBe keyValues.head.key
    headValue shouldBe keyValues.head.getOrFetchValue.assertGetOpt

    zero.headKey.assertGet shouldBe keyValues.head.key

    val (lastKey, lastValue) = zero.last.assertGet
    lastKey shouldBe keyValues.last.key
    lastValue shouldBe keyValues.last.getOrFetchValue.assertGetOpt
    zero.lastKey.assertGet shouldBe keyValues.last.key
  }

  def assertLower(keyValues: Slice[KeyValue.WriteOnly],
                  reader: Reader)(implicit ordering: Ordering[Slice[Byte]]) = {

    @tailrec
    def assertLowers(index: Int) {
      //      println(s"assertLowers : ${index}")
      if (index > keyValues.size - 1) {
        //end
      } else if (index == 0) {
        keyValues(index) match {
          case range: KeyValue.WriteOnly.Range =>
            SegmentReader.find(KeyMatcher.Lower(range.fromKey), None, reader.copy()).assertGetOpt shouldBe empty
            SegmentReader.find(KeyMatcher.Lower(range.toKey), None, reader.copy()).assertGetOpt shouldBe range

          case _ =>
            SegmentReader.find(KeyMatcher.Lower(keyValues(index).key), None, reader.copy()).assertGetOpt shouldBe empty
        }
        assertLowers(index + 1)
      } else {
        val expectedLowerKeyValue = keyValues(index - 1)
        keyValues(index) match {
          case range: KeyValue.WriteOnly.Range =>
            SegmentReader.find(KeyMatcher.Lower(range.fromKey), None, reader.copy()).assertGet shouldBe expectedLowerKeyValue
            SegmentReader.find(KeyMatcher.Lower(range.toKey), None, reader.copy()).assertGet shouldBe range

          case _ =>
            SegmentReader.find(KeyMatcher.Lower(keyValues(index).key), None, reader.copy()).assertGet shouldBe expectedLowerKeyValue
        }

        assertLowers(index + 1)
      }
    }

    assertLowers(0)
  }

  def assertHigher(keyValues: Slice[KeyValue],
                   reader: Reader)(implicit ordering: Ordering[Slice[Byte]]): Unit =
    assertHigher(
      keyValues,
      getHigher =
        key =>
          SegmentReader.find(KeyMatcher.Higher(key), None, reader.copy())
    )

  def assertLower(keyValues: Slice[KeyValue],
                  segment: Segment) = {

    @tailrec
    def assertLowers(index: Int) {
      if (index > keyValues.size - 1) {
        //end
      } else if (index == 0) {
        val actualKeyValue = keyValues(index)
        segment.lower(actualKeyValue.key).assertGetOpt shouldBe empty
        assertLowers(index + 1)
      } else {
        val expectedLower = keyValues(index - 1)
        val keyValue = keyValues(index)
        val lower = segment.lower(keyValue.key).assertGet
        lower shouldBe expectedLower
        assertLowers(index + 1)
      }
    }

    assertLowers(0)
  }

  def assertHigher(keyValues: Slice[KeyValue],
                   segment: Segment): Unit =
    assertHigher(keyValues, getHigher = key => segment.higher(key))

  def assertHigher(_keyValues: Slice[KeyValue],
                   getHigher: Slice[Byte] => Try[Option[KeyValue]]) = {
    import KeyOrder.default._
    val keyValues = _keyValues.toMemory.toArray
    keyValues.indices foreach {
      index =>
        if (index == keyValues.size - 1) {
          keyValues(index) match {
            case range: KeyValue.ReadOnly.Range =>
              getHigher(range.fromKey).assertGet shouldBe range
              getHigher(range.toKey).assertGetOpt shouldBe empty

            case keyValue =>
              getHigher(keyValue.key).assertGetOpt shouldBe empty
          }
        } else {
          val keyValue = keyValues(index)
          val expectedHigher = keyValues(index + 1)
          keyValue match {
            case range: KeyValue.ReadOnly.Range =>

              getHigher(range.fromKey).assertGet shouldBe range
              val toKeyHigher = getHigher(range.toKey).assertGetOpt
              //suppose this keyValue is Range (1 - 10), second is Put(10), third is Put(11), performing higher on Range's toKey(10) will return 11 and not 10.
              //but 10 will be return if the second key-value was a range key-value.
              //if the toKey is equal to expected higher's key, then the higher is the next 3rd key.
              if (!expectedHigher.isInstanceOf[KeyValue.ReadOnly.Range] && (expectedHigher.key equiv range.toKey)) {
                if (index + 2 == keyValues.size) { //if there is no 3rd key, toKeyHigher should be empty
                  toKeyHigher shouldBe empty
                } else {
                  toKeyHigher.assertGet shouldBe keyValues(index + 2)
                }
              } else {
                toKeyHigher.assertGet shouldBe expectedHigher
              }

            case _ =>
              getHigher(keyValue.key).assertGet shouldBe expectedHigher
          }

        }
    }
  }

  def randomIntKeyValuesMemory(count: Int = 5,
                               startId: Option[Int] = None,
                               valueSize: Option[Int] = None,
                               nonValue: Boolean = false,
                               addRandomRemoves: Boolean = false,
                               addRandomRanges: Boolean = false,
                               addRandomRemoveDeadlines: Boolean = false,
                               addRandomPutDeadlines: Boolean = false): Slice[Memory] =
    randomIntKeyValues(
      count = count,
      startId = startId,
      valueSize = valueSize,
      nonValue = nonValue,
      addRandomRemoves = addRandomRemoves,
      addRandomRanges = addRandomRanges,
      addRandomRemoveDeadlines = addRandomRemoveDeadlines,
      addRandomPutDeadlines = addRandomPutDeadlines
    ).toMemory

  def expiredDeadline(): Deadline = {
    val deadline = 0.nanosecond.fromNow - 100.millisecond
    deadline.hasTimeLeft() shouldBe false
    deadline
  }
}
