package com.evolutiongaming.kafka.journal.eventual

import cats._
import cats.arrow.FunctionK
import cats.effect.Resource
import cats.syntax.all._
import com.evolutiongaming.catshelper.{Log, MonadThrowable}
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.util.StreamHelper._
import com.evolutiongaming.skafka.Topic
import com.evolutiongaming.smetrics.MetricsHelper._
import com.evolutiongaming.smetrics._
import com.evolutiongaming.sstream.Stream

import scala.concurrent.duration.FiniteDuration

trait EventualJournal[F[_]] {
  
  def pointer(key: Key): F[Option[JournalPointer]]

  def pointers(topic: Topic): F[TopicPointers]

  def read(key: Key, from: SeqNr): Stream[F, EventRecord[EventualPayloadAndType]]
}

object EventualJournal {

  def apply[F[_]](implicit F: EventualJournal[F]): EventualJournal[F] = F

  def apply[F[_] : FlatMap : MeasureDuration](journal: EventualJournal[F], log: Log[F]): EventualJournal[F] = {

    val functionKId = FunctionK.id[F]

    new EventualJournal[F] {

      def pointers(topic: Topic) = {
        for {
          d <- MeasureDuration[F].start
          r <- journal.pointers(topic)
          d <- d
          _ <- log.debug(s"$topic pointers in ${ d.toMillis }ms, result: $r")
        } yield r
      }

      def read(key: Key, from: SeqNr) = {
        val logging = new (F ~> F) {
          def apply[A](fa: F[A]) = {
            for {
              d <- MeasureDuration[F].start
              r <- fa
              d <- d
              _ <- log.debug(s"$key read in ${ d.toMillis }ms, from: $from, result: $r")
            } yield r
          }
        }
        journal.read(key, from).mapK(logging, functionKId)
      }

      def pointer(key: Key) = {
        for {
          d <- MeasureDuration[F].start
          r <- journal.pointer(key)
          d <- d
          _ <- log.debug(s"$key pointer in ${ d.toMillis }ms, result: $r")
        } yield r
      }
    }
  }


  def apply[F[_] : FlatMap : MeasureDuration](
    journal: EventualJournal[F],
    metrics: Metrics[F]
  ): EventualJournal[F] = {

    val functionKId = FunctionK.id[F]

    new EventualJournal[F] {

      def pointers(topic: Topic) = {
        for {
          d <- MeasureDuration[F].start
          r <- journal.pointers(topic)
          d <- d
          _ <- metrics.pointers(topic, d)
        } yield r
      }

      def read(key: Key, from: SeqNr) = {
        val measure = new (F ~> F) {
          def apply[A](fa: F[A]) = {
            for {
              d <- MeasureDuration[F].start
              r <- fa
              d <- d
              _ <- metrics.read(topic = key.topic, latency = d)
            } yield r
          }
        }

        for {
          a <- journal.read(key, from).mapK(measure, functionKId)
          _ <- Stream.lift(metrics.read(key.topic))
        } yield a
      }

      def pointer(key: Key) = {
        for {
          d <- MeasureDuration[F].start
          r <- journal.pointer(key)
          d <- d
          _ <- metrics.pointer(key.topic, d)
        } yield r
      }
    }
  }


  def empty[F[_] : Applicative]: EventualJournal[F] = new EventualJournal[F] {

    def pointers(topic: Topic) = TopicPointers.empty.pure[F]

    def read(key: Key, from: SeqNr) = Stream.empty[F, EventRecord[EventualPayloadAndType]]

    def pointer(key: Key) = none[JournalPointer].pure[F]
  }


  trait Metrics[F[_]] {

    def pointers(topic: Topic, latency: FiniteDuration): F[Unit]

    def read(topic: Topic, latency: FiniteDuration): F[Unit]

    def read(topic: Topic): F[Unit]

    def pointer(topic: Topic, latency: FiniteDuration): F[Unit]
  }

  object Metrics {

    def empty[F[_] : Applicative]: Metrics[F] = const(Applicative[F].unit)

    def const[F[_]](unit: F[Unit]): Metrics[F] = new Metrics[F] {

      def pointers(topic: Topic, latency: FiniteDuration) = unit

      def read(topic: Topic, latency: FiniteDuration) = unit

      def read(topic: Topic) = unit

      def pointer(topic: Topic, latency: FiniteDuration) = unit
    }


    def of[F[_] : Monad](
      registry: CollectorRegistry[F],
      prefix: String = "eventual_journal"
    ): Resource[F, Metrics[F]] = {

      val latencySummary = registry.summary(
        name = s"${ prefix }_topic_latency",
        help = "Journal call latency in seconds",
        quantiles = Quantiles(
          Quantile(0.9, 0.05),
          Quantile(0.99, 0.005)),
        labels = LabelNames("topic", "type"))

      val eventsSummary = registry.summary(
        name = s"${ prefix }_events",
        help = "Number of events",
        quantiles = Quantiles.Empty,
        labels = LabelNames("topic"))

      for {
        latencySummary <- latencySummary
        eventsSummary  <- eventsSummary
      } yield {

        def observeLatency(name: String, topic: Topic, latency: FiniteDuration) = {
          latencySummary
            .labels(topic, name)
            .observe(latency.toNanos.nanosToSeconds)
        }

        new Metrics[F] {

          def pointers(topic: Topic, latency: FiniteDuration) = {
            observeLatency(name = "pointers", topic = topic, latency = latency)
          }

          def read(topic: Topic, latency: FiniteDuration) = {
            observeLatency(name = "read", topic = topic, latency = latency)
          }

          def read(topic: Topic) = {
            eventsSummary.labels(topic).observe(1.0)
          }

          def pointer(topic: Topic, latency: FiniteDuration) = {
            observeLatency(name = "pointer", topic = topic, latency = latency)
          }
        }
      }
    }
  }


  implicit class EventualJournalOps[F[_]](val self: EventualJournal[F]) extends AnyVal {

    def mapK[G[_]](fg: F ~> G, gf: G ~> F): EventualJournal[G] = new EventualJournal[G] {

      def pointers(topic: Topic) = fg(self.pointers(topic))

      def read(key: Key, from1: SeqNr) = self.read(key, from1).mapK(fg, gf)

      def pointer(key: Key) = fg(self.pointer(key))
    }

    def withLog(log: Log[F])(implicit F: FlatMap[F], measureDuration: MeasureDuration[F]): EventualJournal[F] = {
      EventualJournal[F](self, log)
    }

    def withMetrics(metrics: Metrics[F])(implicit F: FlatMap[F], measureDuration: MeasureDuration[F]): EventualJournal[F] = {
      EventualJournal(self, metrics)
    }

    def enhanceError(implicit F: MonadThrowable[F]): EventualJournal[F] = {

      def error[A](msg: String, cause: Throwable) = {
        JournalError(s"EventualJournal.$msg failed with $cause", cause).raiseError[F, A]
      }

      new EventualJournal[F] {

        def pointers(topic: Topic) = {
          self
            .pointers(topic)
            .handleErrorWith { a => error(s"pointers topic: $topic", a) }
        }

        def read(key: Key, from: SeqNr) = {
          self
            .read(key, from)
            .handleErrorWith { a: Throwable => Stream.lift(error[EventRecord[EventualPayloadAndType]](s"read key: $key, from: $from", a)) }
        }

        def pointer(key: Key) = {
          self
            .pointer(key)
            .handleErrorWith { a => error(s"pointer key: $key", a) }
        }
      }
    }
  }
}
