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

package swaydb.core.segment

import java.nio.file.Path
import java.util.concurrent.ConcurrentSkipListMap

import bloomfilter.mutable.BloomFilter
import com.typesafe.scalalogging.LazyLogging
import swaydb.core.data.{Persistent, _}
import swaydb.core.io.file.DBFile
import swaydb.core.io.reader.Reader
import swaydb.core.level.PathsDistributor
import swaydb.core.segment.format.one.SegmentReader._
import swaydb.core.segment.format.one.{KeyMatcher, SegmentFooter, SegmentReader}
import swaydb.core.util.TryUtil._
import swaydb.core.util._
import swaydb.data.config.Dir
import swaydb.data.segment.MaxKey
import swaydb.data.segment.MaxKey.{Fixed, Range}
import swaydb.data.slice.{Reader, Slice}
import PipeOps._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Deadline, FiniteDuration}
import scala.util.{Failure, Success, Try}

private[segment] case class PersistentSegment(file: DBFile,
                                              mmapReads: Boolean,
                                              mmapWrites: Boolean,
                                              minKey: Slice[Byte],
                                              maxKey: MaxKey,
                                              segmentSize: Int,
                                              removeDeletes: Boolean,
                                              nearestExpiryDeadline: Option[Deadline])(implicit ordering: Ordering[Slice[Byte]],
                                                                                           keyValueLimiter: (Persistent, Segment) => Unit,
                                                                                           fileOpenLimited: DBFile => Unit,
                                                                                           ec: ExecutionContext) extends Segment with LazyLogging {

  import ordering._

  def path = file.path

  private[segment] val cache = new ConcurrentSkipListMap[Slice[Byte], Persistent](ordering)
  @volatile private[segment] var footer = Option.empty[SegmentFooter]

  def close: Try[Unit] =
    file.close map {
      _ =>
        footer = None
    }

  def isOpen: Boolean =
    file.isOpen

  def isFileDefined =
    file.isFileDefined

  private def createReader(): Reader =
    Reader(file)

  private def addToCache(keyValue: Persistent): Unit = {
    cache.put(keyValue.key, keyValue)
    keyValueLimiter(keyValue, this)
  }

  def delete: Try[Unit] = {
    logger.trace(s"{}: DELETING FILE", path)
    file.delete() recoverWith {
      case exception =>
        logger.error(s"{}: Failed to delete Segment file.", path, exception)
        Failure(exception)
    } map {
      _ =>
        footer = None
    }
  }

  def copyTo(toPath: Path): Try[Path] =
    file copyTo toPath

  /**
    * Default targetPath is set to this [[PersistentSegment]]'s parent directory.
    */
  def put(newKeyValues: Slice[KeyValue.ReadOnly],
          minSegmentSize: Long,
          bloomFilterFalsePositiveRate: Double,
          hasTimeLeftAtLeast: FiniteDuration,
          targetPaths: PathsDistributor = PathsDistributor(Seq(Dir(path.getParent, 1)), () => Seq()))(implicit idGenerator: IDGenerator): Try[Slice[Segment]] =
    getAll() flatMap {
      currentKeyValues =>
        SegmentMerger.merge(
          newKeyValues = newKeyValues,
          oldKeyValues = currentKeyValues,
          minSegmentSize = minSegmentSize,
          forInMemory = false,
          isLastLevel = removeDeletes,
          bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate,
          hasTimeLeftAtLeast = hasTimeLeftAtLeast
        ) flatMap {
          splits =>
            splits.tryMap(
              tryBlock =
                keyValues => {
                  Segment.persistent(
                    path = targetPaths.next.resolve(idGenerator.nextSegmentID),
                    mmapReads = mmapReads,
                    mmapWrites = mmapWrites,
                    keyValues = keyValues,
                    bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate,
                    removeDeletes = removeDeletes
                  )
                },

              recover =
                (segments: Slice[Segment], _: Failure[Slice[Segment]]) =>
                  segments foreach {
                    segmentToDelete =>
                      segmentToDelete.delete.failed foreach {
                        exception =>
                          logger.error(s"{}: Failed to delete Segment '{}' in recover due to failed put", path, segmentToDelete.path, exception)
                      }
                  }
            )
        }
    }

  def refresh(minSegmentSize: Long,
              bloomFilterFalsePositiveRate: Double,
              targetPaths: PathsDistributor = PathsDistributor(Seq(Dir(path.getParent, 1)), () => Seq()))(implicit idGenerator: IDGenerator): Try[Slice[Segment]] =
    getAll() flatMap {
      currentKeyValues =>
        SegmentMerger.split(
          keyValues = currentKeyValues,
          minSegmentSize = minSegmentSize,
          forInMemory = false,
          isLastLevel = removeDeletes,
          bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate
        ) ==> {
          splits =>
            splits.tryMap(
              tryBlock =
                keyValues => {
                  Segment.persistent(
                    path = targetPaths.next.resolve(idGenerator.nextSegmentID),
                    mmapReads = mmapReads,
                    mmapWrites = mmapWrites,
                    keyValues = keyValues,
                    bloomFilterFalsePositiveRate = bloomFilterFalsePositiveRate,
                    removeDeletes = removeDeletes
                  )
                },

              recover =
                (segments: Slice[Segment], _: Failure[Slice[Segment]]) =>
                  segments foreach {
                    segmentToDelete =>
                      segmentToDelete.delete.failed foreach {
                        exception =>
                          logger.error(s"{}: Failed to delete Segment '{}' in recover due to failed refresh", path, segmentToDelete.path, exception)
                      }
                  }
            )
        }
    }

  private def prepareGet[T](getOperation: (SegmentFooter, Reader) => Try[T]): Try[T] =
    getFooter() flatMap {
      footer =>
        getOperation(footer, createReader())
    } recoverWith {
      case ex: Exception =>
        ExceptionUtil.logFailure(s"$path: Failed to read Segment.", ex)
        Failure(ex)
    }

  private def returnResponse(response: Try[Option[Persistent]]): Try[Option[Persistent]] =
    response flatMap {
      case Some(keyValue) =>
        if (persistent) keyValue.unsliceKey
        addToCache(keyValue)
        response

      case None =>
        response
    }

  def getFooter(): Try[SegmentFooter] =
    footer.map(Success(_)) getOrElse {
      SegmentReader.readFooter(createReader()) map {
        segmentFooter =>
          footer = Some(segmentFooter)
          segmentFooter
      }
    }

  override def getBloomFilter: Try[Option[BloomFilter[Slice[Byte]]]] =
    getFooter().map(_.bloomFilter)

  def getFromCache(key: Slice[Byte]): Option[Persistent] =
    Option(cache.get(key))

  def mightContain(key: Slice[Byte]): Try[Boolean] =
    getFooter().map(_.bloomFilter.forall(_.mightContain(key)))

  def get(key: Slice[Byte]): Try[Option[Persistent]] =
    maxKey match {
      case Fixed(maxKey) if key > maxKey =>
        TryUtil.successNone

      case range: Range if key >= range.maxKey =>
        TryUtil.successNone

      //check for minKey inside the Segment is not required since Levels already do minKey check.
      //      case _ if key < minKey =>
      //        TryUtil.successNone

      case _ =>
        val floorValue = Option(cache.floorEntry(key)).map(_.getValue)
        floorValue match {
          case Some(floor) if floor.key equiv key =>
            Success(Some(floor))

          case Some(floorRange: Persistent.Range) if key >= floorRange.fromKey && key < floorRange.toKey =>
            Success(Some(floorRange))

          case _ =>
            prepareGet {
              (footer, reader) =>
                if (!footer.hasRange && !footer.bloomFilter.forall(_.mightContain(key)))
                  TryUtil.successNone
                else
                  returnResponse {
                    find(KeyMatcher.Get(key), startFrom = floorValue, reader, footer)
                  }
            }
        }
    }

  private def satisfyLowerFromCache(key: Slice[Byte],
                                    lowerKeyValue: Persistent): Option[Persistent] =
    Option(cache.ceilingEntry(key)).map(_.getValue) flatMap {
      ceilingKeyValue =>
        if (lowerKeyValue.nextIndexOffset == ceilingKeyValue.indexOffset)
          Some(lowerKeyValue)
        else
          None
    }

  def lower(key: Slice[Byte]): Try[Option[Persistent]] =
    if (key <= minKey)
      TryUtil.successNone
    else {
      maxKey match {
        case Fixed(maxKey) if key > maxKey =>
          get(maxKey)

        case Range(fromKey, _) if key > fromKey =>
          get(fromKey)

        case _ =>
          val lowerKeyValue = Option(cache.lowerEntry(key)).map(_.getValue)
          val lowerFromCache = lowerKeyValue.flatMap(satisfyLowerFromCache(key, _))
          if (lowerFromCache.isDefined)
            Success(lowerFromCache)
          else
            prepareGet {
              (footer, reader) =>
                returnResponse {
                  find(KeyMatcher.Lower(key), startFrom = lowerKeyValue, reader, footer)
                }
            }
      }
    }

  private def satisfyHigherFromCache(key: Slice[Byte],
                                     floorKeyValue: Persistent): Option[Persistent] =
    floorKeyValue match {
      case floorRange: Persistent.Range if key >= floorRange.fromKey && key < floorRange.toKey =>
        Some(floorKeyValue)

      case _ =>
        Option(cache.higherEntry(key)).map(_.getValue) flatMap {
          higherKeyValue =>
            if (floorKeyValue.nextIndexOffset == higherKeyValue.indexOffset)
              Some(higherKeyValue)
            else
              None
        }
    }

  def higher(key: Slice[Byte]): Try[Option[Persistent]] =
    maxKey match {
      case Fixed(maxKey) if key >= maxKey =>
        TryUtil.successNone

      case Range(_, maxKey) if key >= maxKey =>
        TryUtil.successNone

      case _ =>
        val floorKeyValue = Option(cache.floorEntry(key)).map(_.getValue)
        val higherFromCache = floorKeyValue.flatMap(satisfyHigherFromCache(key, _))

        if (higherFromCache.isDefined)
          Success(higherFromCache)
        else
          prepareGet {
            (footer, reader) =>
              returnResponse {
                find(KeyMatcher.Higher(key), startFrom = floorKeyValue, reader, footer)
              }
          }

    }

  def getAll(addTo: Option[Slice[KeyValue.ReadOnly]] = None): Try[Slice[KeyValue.ReadOnly]] =
    prepareGet {
      (footer, reader) =>
        SegmentReader.readAll(footer, reader, addTo) recoverWith {
          case exception =>
            logger.trace("{}: Reading index block failed. Segment file is corrupted.", path)
            Failure(exception)
        }
    }

  def existsOnDisk: Boolean =
    file.existsOnDisk

  def existsInMemory: Boolean =
    file.existsInMemory

  def notExistsOnDisk: Boolean =
    !file.existsOnDisk

  def getKeyValueCount(): Try[Int] =
    getFooter().map(_.keyValueCount)

  def memory: Boolean =
    file.memory

  def persistent: Boolean =
    file.persistent

  override def isFooterDefined: Boolean =
    footer.isDefined

  override def hasRange: Try[Boolean] =
    getFooter().map(_.hasRange)
}