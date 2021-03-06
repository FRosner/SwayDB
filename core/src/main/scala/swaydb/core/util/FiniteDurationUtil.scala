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

package swaydb.core.util

import java.util.TimerTask
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

private[core] object FiniteDurationUtil {

  implicit class FiniteDurationImplicits(duration: Duration) {
    def asString: String = {
      val seconds: Double = duration.toMillis / 1000D
      s"$seconds seconds"
    }
  }

  implicit class TimerTaskToDuration(task: TimerTask) {
    def deadline() =
      timeLeft().fromNow

    def timeLeft(): FiniteDuration =
      FiniteDuration(task.scheduledExecutionTime() - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
  }
}
