package com.evolutiongaming.kafka.journal

import akka.event.LoggingAdapter
import cats.effect.Sync
import com.evolutiongaming.catshelper.Log

object LogFromAkka {

  def apply[F[_] : Sync](log: LoggingAdapter): Log[F] = new Log[F] {

    def debug(msg: => String, mdc: Log.Mdc) = {
      Sync[F].delay {
        if (log.isDebugEnabled) log.debug(msg, mdc)
      }
    }

    def info(msg: => String, mdc: Log.Mdc) = {
      Sync[F].delay {
        if (log.isInfoEnabled) log.info(msg, mdc)
      }
    }

    def warn(msg: => String, mdc: Log.Mdc) = {
      Sync[F].delay {
        if (log.isWarningEnabled) log.warning(msg, mdc)
      }
    }

    def warn(msg: => String, cause: Throwable, mdc: Log.Mdc) = {
      Sync[F].delay {
        if (log.isWarningEnabled) log.warning(s"$msg: $cause", mdc)
      }
    }

    def error(msg: => String, mdc: Log.Mdc) = {
      Sync[F].delay {
        if (log.isErrorEnabled) log.error(msg, mdc)
      }
    }

    def error(msg: => String, cause: Throwable, mdc: Log.Mdc) = {
      Sync[F].delay {
        if (log.isErrorEnabled) log.error(cause, msg, mdc)
      }
    }

  }
}