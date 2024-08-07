// Copyright (c) 2013-2020 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.log4cats

import doobie.LogHandler
import doobie.util.log.*
import org.typelevel.log4cats.*

/** A LogHandler that writes a default format to a log4cats MessageLogger. This is provided for debugging purposes and
  * is not intended for production use, because it could log sensitive data.
  *
  * @group Constructors
  */
class Log4CatsDebuggingLogHandler[F[_]](logger: MessageLogger[F]) extends LogHandler[F] {
  override def run(logEvent: LogEvent): F[Unit] = logEvent match {
    case Success(s, a, l, e1, e2) => {
      val paramsStr = a match {
        case nonBatch: Parameters.NonBatch => s"[${nonBatch.paramsAsList.mkString(", ")}]"
        case _: Parameters.Batch           => "<batch arguments not rendered>"
      }
      logger.info(
        s"""Successful Statement Execution:
           |
           |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
           |
           | arguments = $paramsStr
           | label     = $l
           |   elapsed = ${e1.toMillis.toString} ms exec + ${e2.toMillis.toString} ms processing (${(e1 + e2).toMillis
            .toString} ms total)
        """.stripMargin)
    }

    case ProcessingFailure(s, a, l, e1, e2, t) => {
      val paramsStr = a.allParams.map(thisArgs => thisArgs.mkString("(", ", ", ")"))
        .mkString("[", ", ", "]")
      logger.warn(
        s"""Failed Resultset Processing:
           |
           |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
           |
           | parameters = $paramsStr
           | label     = $l
           | elapsed = ${e1.toMillis.toString} ms exec + ${e2.toMillis.toString} ms processing (failed) (${(e1 + e2)
            .toMillis.toString} ms total)
           |   failure = ${t.getMessage}
        """.stripMargin)
    }

    case ExecFailure(s, a, l, e1, t) => {
      val paramsStr = a.allParams.map(thisArgs => thisArgs.mkString("(", ", ", ")"))
        .mkString("[", ", ", "]")
      logger.error(
        s"""Failed Statement Execution:
           |
           |  ${s.linesIterator.dropWhile(_.trim.isEmpty).mkString("\n  ")}
           |
           | parameters = $paramsStr
           | label     = $l
           | elapsed = ${e1.toMillis.toString} ms exec (failed)
           | failure = ${t.getMessage}
        """.stripMargin)
    }
  }
}
