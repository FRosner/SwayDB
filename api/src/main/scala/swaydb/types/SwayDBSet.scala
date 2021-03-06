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

package swaydb.types

import swaydb.Batch
import swaydb.api.SwayDBAPI
import swaydb.data.accelerate.Level0Meter
import swaydb.data.compaction.LevelMeter
import swaydb.data.request
import swaydb.data.slice.Slice
import swaydb.iterator.KeysIterator
import swaydb.serializers.{Serializer, _}
import swaydb.types.BatchImplicits._

import scala.concurrent.duration.{Deadline, FiniteDuration}
import scala.util.Try

object SwayDBSet {
  def apply[T](api: SwayDBAPI)(implicit serializer: Serializer[T]): SwayDBSet[T] =
    new SwayDBSet(api)
}

/**
  * Set database API.
  *
  * For documentation check - http://swaydb.io/api/
  */
class SwayDBSet[T](api: SwayDBAPI)(implicit serializer: Serializer[T]) extends KeysIterator[T](api, None) {

  def get(elem: T): Try[Option[T]] =
    api.getKey(elem).map(_.map(_.read[T]))

  def contains(elem: T): Try[Boolean] =
    api contains elem

  def mightContain(elem: T): Try[Boolean] =
    api mightContain elem

  def add(elem: T): Try[Level0Meter] =
    api.put(elem)

  def add(elem: T, expireAt: Deadline): Try[Level0Meter] =
    api.put(elem, None, expireAt)

  def add(elem: T, expireAfter: FiniteDuration): Try[Level0Meter] =
    api.put(elem, None, expireAfter.fromNow)

  def remove(elem: T): Try[Level0Meter] =
    api.remove(elem)

  def remove(from: T, to: T): Try[Level0Meter] =
    api.remove(from, to)

  def expire(elem: T, after: FiniteDuration): Try[Level0Meter] =
    api.expire(elem, after.fromNow)

  def expire(elem: T, at: Deadline): Try[Level0Meter] =
    api.expire(elem, at)

  def expire(from: T, to: T, after: FiniteDuration): Try[Level0Meter] =
    api.expire(from, to, after.fromNow)

  def expire(from: T, to: T, at: Deadline): Try[Level0Meter] =
    api.expire(from, to, at)

  def batch(batch: Batch[T, Nothing]*): Try[Level0Meter] =
    api.batch(batch)

  def batch(batch: Iterable[Batch[T, Nothing]]): Try[Level0Meter] =
    api.batch(batch)

  def batchAdd(elems: T*): Try[Level0Meter] =
    batchAdd(elems)

  def batchAdd(elems: Iterable[T]): Try[Level0Meter] =
    api.batch(elems.map(elem => request.Batch.Put(elem, None, None)))

  def batchRemove(elems: T*): Try[Level0Meter] =
    batchRemove(elems)

  def batchRemove(elems: Iterable[T]): Try[Level0Meter] =
    api.batch(elems.map(elem => request.Batch.Remove(elem, None)))

  def batchExpire(elems: (T, Deadline)*): Try[Level0Meter] =
    batchExpire(elems)

  def batchExpire(elems: Iterable[(T, Deadline)]): Try[Level0Meter] =
    api.batch(elems.map(elemWithExpire => request.Batch.Remove(elemWithExpire._1, Some(elemWithExpire._2))))

  def level0Meter: Level0Meter =
    api.level0Meter

  def level1Meter: LevelMeter =
    api.level1Meter

  def levelMeter(levelNumber: Int): Option[LevelMeter] =
    api.levelMeter(levelNumber)

  def sizeOfSegments: Long =
    api.sizeOfSegments

  def elemSize(elem: T): Int =
    (elem: Slice[Byte]).size

  def expiration(elem: T): Try[Option[Deadline]] =
    api deadline elem

  def timeLeft(elem: T): Try[Option[FiniteDuration]] =
    expiration(elem).map(_.map(_.timeLeft))
}