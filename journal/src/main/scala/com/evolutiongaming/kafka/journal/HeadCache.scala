package com.evolutiongaming.kafka.journal

import java.time.Instant

import cats._
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import cats.temp.par._
import com.evolutiongaming.kafka.journal.KafkaConverters._
import com.evolutiongaming.kafka.journal.cache.Cache
import com.evolutiongaming.kafka.journal.eventual.{EventualJournal, TopicPointers}
import com.evolutiongaming.retry.Retry
import com.evolutiongaming.kafka.journal.CatsHelper._
import com.evolutiongaming.catshelper.ClockHelper._
import com.evolutiongaming.catshelper.{Log, LogOf, SerialRef}
import com.evolutiongaming.random.Random
import com.evolutiongaming.kafka.journal.util.EitherHelper._
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.skafka.consumer.{AutoOffsetReset, ConsumerConfig, ConsumerRecord, ConsumerRecords}
import com.evolutiongaming.skafka.{Offset, Partition, Topic, TopicPartition}

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

/**
  * TODO
  * 1. handle cancellation in case of timeouts and not leak memory
  * 2. Journal should close HeadCache
  * 3. Remove half of partition cache on cleanup
  * 4. Support configuration
  * 5. Clearly handle cases when topic is not yet created, but requests are coming
  * 6. Keep 1000 last seen entries, even if replicated.
  * 7. Fail headcache when background tasks failed
  */
trait HeadCache[F[_]] {
  import HeadCache._

  // TODO change API
  def get(key: Key, partition: Partition, offset: Offset): F[Result]
}


object HeadCache {

  def empty[F[_] : Applicative]: HeadCache[F] = new HeadCache[F] {

    def get(key: Key, partition: Partition, offset: Offset) = Result.invalid.pure[F]
  }

  def of[F[_] : Concurrent : Par : Timer : ContextShift : LogOf : KafkaConsumerOf](
    consumerConfig: ConsumerConfig,
    eventualJournal: EventualJournal[F],
    metrics: Option[Metrics[F]]): Resource[F, HeadCache[F]] = {

    implicit val eventual = Eventual[F](eventualJournal)

    val consumer = Consumer.of[F](consumerConfig)
    for {
      log       <- Resource.liftF(LogOf[F].apply(HeadCache.getClass))
      headCache <- HeadCache.of[F](log, consumer, metrics getOrElse Metrics.empty[F])
    } yield {
      val headCache1 = headCache.withLog(log)
      metrics.fold(headCache1) { metrics => headCache1.withMetrics(metrics) }
    }
  }

  def of[F[_] : Concurrent : Eventual : Par : Timer : ContextShift](
    log: Log[F],
    consumer: Resource[F, Consumer[F]],
    metrics: Metrics[F],
    config: Config = Config.Default): Resource[F, HeadCache[F]] = {

    val result = for {
      cache <- Cache.of[F, Topic, TopicCache[F]]
      cache <- Ref.of(cache.pure[F])
    } yield {

      def topicCache(topic: Topic) = {
        val logTopic = log.prefixed(topic)
        for {
          _          <- logTopic.info("create")
          consumer1   = consumer.map(Consumer(_, logTopic))
          topicCache <- TopicCache.of(
            topic = topic,
            config = config,
            consumer = consumer1,
            metrics = metrics)(Concurrent[F], Eventual[F], Par[F], logTopic, Timer[F], ContextShift[F])
        } yield {
          topicCache
        }
      }

      val release = for {
        _ <- cache.get.flatten
        c <- cache.modify { c =>
          val cc = for {
            _ <- c
            c <- ClosedException.raiseError[F, Cache[F, Topic, TopicCache[F]]]
          } yield c
          (cc, c)
        }
        c <- c
        v <- c.values
        _ <- Parallel.foldMap(v.values) { v =>
          for {
            v <- v
            _ <- v.close
          } yield {}
        }
      } yield {}

      val headCache = new HeadCache[F] {

        def get(key: Key, partition: Partition, offset: Offset) = {
          val topic = key.topic
          for {
            cache      <- cache.get
            cache      <- cache
            topicCache <- cache.getOrUpdate(topic)(topicCache(topic))
            result     <- topicCache.get(id = key.id, partition = partition, offset = offset)
          } yield result
        }
      }

      (headCache, release)
    }

    Resource(result)
  }


  final case class Config(
    pollTimeout: FiniteDuration = 10.millis,
    cleanInterval: FiniteDuration = 3.seconds,
    maxSize: Int = 100000) {

    require(maxSize >= 1, s"maxSize($maxSize) >= 1")
  }

  object Config {
    val Default: Config = Config()
  }


  sealed abstract class Result extends Product

  object Result {

    def invalid: Result = Invalid

    def valid(info: JournalInfo): Result = Valid(info)

    val empty: Result = valid(JournalInfo.empty)


    case object Invalid extends Result

    final case class Valid(info: JournalInfo) extends Result
  }


  trait TopicCache[F[_]] {

    def get(id: Id, partition: Partition, offset: Offset): F[Result]

    def close: F[Unit]
  }

  object TopicCache {

    type Listener[F[_]] = Map[Partition, PartitionEntry] => Option[F[Unit]]

    def of[F[_] : Concurrent : Eventual : Par : Log : Timer : ContextShift](
      topic: Topic,
      config: Config,
      consumer: Resource[F, Consumer[F]],
      metrics: Metrics[F]): F[TopicCache[F]] = {

      for {
        pointers  <- Eventual[F].pointers(topic)
        entries    = for {
          (partition, offset) <- pointers.values
        } yield {
          val entry = PartitionEntry(partition = partition, offset = offset, entries = Map.empty, trimmed = None)
          (entry.partition, entry)
        }
        state     <- SerialRef[F].of(State[F](entries, List.empty))
        consuming <- GracefulFiber[F].apply { cancel =>
          // TODO not use `.start` here
          consumer.start { consumer =>

            val consuming = ConsumeTopic(
              topic = topic,
              from = pointers.values,
              pollTimeout = config.pollTimeout,
              consumer = consumer,
              cancel = cancel
            ) { records =>

              for {
                now     <- Clock[F].millis
                latency  = records.values.flatten.headOption.fold(0l) { record => now - record.timestamp.toEpochMilli }
                entries  = partitionEntries(records)
                state   <- state.modify { state =>
                  val combined = combineAndTrim(state.entries, entries, config.maxSize)
                  val (listeners, completed) = runListeners(state.listeners, combined)
                  for {
                    _      <- completed.parFold
                    state1  = state.copy(entries = combined, listeners = listeners)
                  } yield (state1, state1)
                }
                _       <- metrics.round(
                  topic = topic,
                  entries = state.size,
                  listeners = state.listeners.size,
                  deliveryLatency = latency)
              } yield {}
            }

            consuming.onError { case error =>
              Log[F].error(s"consuming failed with $error", error)
            } // TODO fail head cache
          }
        }

        cleaning <- Concurrent[F].start {
          val cleaning = for {
            _        <- Timer[F].sleep(config.cleanInterval)
            pointers <- Eventual[F].pointers(topic)
            before   <- state.get
            _        <- state.update { _.removeUntil(pointers.values).pure[F] }
            after    <- state.get
            removed   = before.size - after.size
            _        <- if (removed > 0) Log[F].debug(s"remove $removed entries") else ().pure[F]
          } yield {}
          cleaning.foreverM[Unit].onError { case error =>
            Log[F].error(s"cleaning failed with $error", error) // TODO fail head cache
          }
        }
      } yield {
        val release = List(consuming, cleaning).parFoldMap { _.cancel }
        apply(topic, release, state, metrics)
      }
    }

    def apply[F[_] : Concurrent : Eventual : Monad : Log](
      topic: Topic,
      release: F[Unit],
      stateRef: SerialRef[F, State[F]],
      metrics: Metrics[F]
    ): TopicCache[F] = {

      // TODO handle case with replicator being down

      new TopicCache[F] {

        def get(id: Id, partition: Partition, offset: Offset) = {

          sealed trait Error

          object Error {
            case object Trimmed extends Error
            case object Invalid extends Error
            case object Behind extends Error
          }

          def entryOf(entries: Map[Partition, PartitionEntry]): Option[Result] = {
            val result = for {
              pe <- entries.get(partition) toRight Error.Invalid
              _  <- pe.offset >= offset trueOr Error.Behind
              r  <- pe.entries.get(id).fold {
                // TODO Test this
                // TODO
                //                  val replicatedTo: Offset =
                //
                //                  if (offset <= replicatedTo) {
                //                    Result(None, None).asRight
                //                  } else if (partitionEntry.trimmed.) {
                for {
                  _ <- pe.trimmed.isEmpty trueOr Error.Trimmed
                } yield {
                  Result.empty
                }
              } { e =>
                Result.valid(e.info).asRight
              }
            } yield r

            result match {
              case Right(result)       => result.some
              case Left(Error.Behind)  => none
              case Left(Error.Trimmed) => Result.invalid.some
              case Left(Error.Invalid) => none
            }
          }

          def update(state: State[F]) = {
            for {
              deferred <- Deferred[F, Result]
              listener  = (entries: Map[Partition, PartitionEntry]) => {
                for {
                  r <- entryOf(entries)
                } yield for {
                  _ <- deferred.complete(r)
                  _ <- Log[F].debug(s"remove listener, id: $id, offset: $partition:$offset")
                } yield {}
              }
              _        <- Log[F].debug(s"add listener, id: $id, offset: $partition:$offset")
              state1    = state.copy(listeners = listener :: state.listeners)
              _        <- metrics.listeners(topic, state1.listeners.size)
            } yield {
              val stateNew = state.copy(listeners = listener :: state.listeners)
              (stateNew, deferred.get)
            }
          }

          for {
            state  <- stateRef.get
            result <- entryOf(state.entries).fold {
              for {
                result <- stateRef.modify { state =>
                  entryOf(state.entries).fold {
                    update(state)
                  } { entry =>
                    (state, entry.pure[F]).pure[F]
                  }
                }
                result <- result
              } yield result
            } {
              _.pure[F]
            }
          } yield result
        }

        def close = {
          for {
            _ <- Log[F].debug("close")
            _ <- release // TODO should be idempotent
          } yield {}
        }
      }
    }


    private def combineAndTrim(
      x: Map[Partition, PartitionEntry],
      y: Map[Partition, PartitionEntry],
      maxSize: Int
    ): Map[Partition, PartitionEntry] = {

      def sizeOf(map: Map[Partition, PartitionEntry]) = {
        map.values.foldLeft(0L) { _ + _.entries.size }
      }

      val combined = x combine y
      if (sizeOf(combined) <= maxSize) {
        combined
      } else {
        val partitions = combined.size
        val maxSizePartition = maxSize / partitions max 1
        for {
          (partition, partitionEntry) <- combined
        } yield {
          val updated = {
            if (partitionEntry.entries.size <= maxSizePartition) {
              partitionEntry
            } else {
              // TODO
              val offset = partitionEntry.entries.values.foldLeft(Offset.Min) { _ max _.offset }
              // TODO remove half
              partitionEntry.copy(entries = Map.empty, trimmed = Some(offset))
            }
          }
          (partition, updated)
        }
      }
    }


    private def partitionEntries(
      records: Map[Partition, List[KafkaRecord]]
    ): Map[Partition, PartitionEntry] = {

      for {
        (partition, records) <- records
      } yield {
        val entries = for {
          (id, records)  <- records.groupBy(_.id)
          (info, offset)  = records.foldLeft((JournalInfo.empty, Offset.Min)) { case ((info, offset), record) =>
            val info1 = info(record.header)
            val offset1 = record.header match {
              case _: ActionHeader.AppendOrDelete => record.offset max offset
              case _: ActionHeader.Mark           => offset
            }
            (info1, offset1)
          }
          entry          <- info match {
            case JournalInfo.Empty          => none[Entry]
            case info: JournalInfo.NonEmpty => Entry(id = id, offset = offset, info).some
          }
        } yield {
          (entry.id, entry)
        }

        // TODO
        val offset = records.foldLeft(Offset.Min) { _ max _.offset }
        val partitionEntry = PartitionEntry(
          partition = partition,
          offset = offset,
          entries = entries,
          trimmed = None /*TODO*/)
        (partitionEntry.partition, partitionEntry)
      }
    }


    private def runListeners[F[_]](
      listeners: List[Listener[F]],
      entries: Map[Partition, PartitionEntry]
    ): (List[Listener[F]], List[F[Unit]]) = {

      val zero = (List.empty[Listener[F]], List.empty[F[Unit]])
      listeners.foldLeft(zero) { case ((listeners, completed), listener) =>
        listener(entries) match {
          case None         => (listener :: listeners, completed)
          case Some(result) => (listeners, result :: completed)
        }
      }
    }


    final case class Entry(id: Id, offset: Offset, info: JournalInfo.NonEmpty)

    object Entry {

      implicit val SemigroupImpl: Semigroup[Entry] = new Semigroup[Entry] {

        def combine(x: Entry, y: Entry) = {
          val offset = x.offset max y.offset
          val info = x.info combine y.info
          x.copy(info = info, offset = offset)
        }
      }
    }


    final case class PartitionEntry(
      partition: Partition,
      offset: Offset,
      entries: Map[Id, Entry],
      trimmed: Option[Offset] /*TODO remove this field*/)

    object PartitionEntry {

      implicit val SemigroupImpl: Semigroup[PartitionEntry] = new Semigroup[PartitionEntry] {

        def combine(x: PartitionEntry, y: PartitionEntry) = {
          val entries = x.entries combine y.entries
          val offset = x.offset max y.offset
          x.copy(entries = entries, offset = offset)
        }
      }
    }

    final case class State[F[_]](
      entries: Map[Partition, PartitionEntry],
      listeners: List[Listener[F]]) {

      def size: Long = entries.values.foldLeft(0l) { _ + _.entries.size }

      def removeUntil(pointers: Map[Partition, Offset]): State[F] = {
        val updated = for {
          (partition, offset) <- pointers
          partitionEntry <- entries.get(partition)
        } yield {
          val entries = for {
            (id, entry) <- partitionEntry.entries
            if entry.offset > offset
          } yield {
            (id, entry)
          }
          val trimmed = partitionEntry.trimmed.filter(_ > offset)
          val updated = partitionEntry.copy(entries = entries, trimmed = trimmed)
          (partition, updated)
        }

        copy(entries = entries ++ updated)
      }
    }
  }


  trait Consumer[F[_]] {

    def assign(topic: Topic, partitions: Nel[Partition]): F[Unit]

    def seek(topic: Topic, offsets: Map[Partition, Offset]): F[Unit]

    def poll(timeout: FiniteDuration): F[ConsumerRecords[Id, Bytes]]

    def partitions(topic: Topic): F[Set[Partition]]
  }

  object Consumer {

    def apply[F[_]](implicit F: Consumer[F]): Consumer[F] = F

    def apply[F[_] : Monad](consumer: KafkaConsumer[F, Id, Bytes]): Consumer[F] = {

      implicit val monoidUnit = Applicative.monoid[F, Unit]

      new Consumer[F] {

        def assign(topic: Topic, partitions: Nel[Partition]) = {
          val topicPartitions = for {
            partition <- partitions
          } yield {
            TopicPartition(topic = topic, partition)
          }
          consumer.assign(topicPartitions)
        }

        def seek(topic: Topic, offsets: Map[Partition, Offset]) = {
          offsets.toIterable.foldMap { case (partition, offset) =>
            val topicPartition = TopicPartition(topic = topic, partition = partition)
            consumer.seek(topicPartition, offset)
          }
        }

        def poll(timeout: FiniteDuration) = consumer.poll(timeout)

        def partitions(topic: Topic) = consumer.partitions(topic)
      }
    }

    def apply[F[_] : Monad](consumer: Consumer[F], log: Log[F]): Consumer[F] = {

      new Consumer[F] {

        def assign(topic: Topic, partitions: Nel[Partition]) = {
          for {
            _ <- log.debug(s"assign topic: $topic, partitions: $partitions")
            r <- consumer.assign(topic, partitions)
          } yield r
        }

        def seek(topic: Topic, offsets: Map[Partition, Offset]) = {
          for {
            _ <- log.debug(s"seek topic: $topic, offsets: $offsets")
            r <- consumer.seek(topic, offsets)
          } yield r
        }

        def poll(timeout: FiniteDuration) = {
          for {
            r <- consumer.poll(timeout)
            _ <- {
              if (r.values.isEmpty) ().pure[F]
              else log.debug {
                val size = r.values.values.foldLeft(0l) { _ + _.size }
                s"poll timeout: $timeout, result: $size"
              }
            }
          } yield r
        }

        def partitions(topic: Topic) = {
          for {
            r <- consumer.partitions(topic)
            _ <- log.debug(s"partitions topic: $topic, result: $r")
          } yield r
        }
      }
    }

    def of[F[_] : Monad : KafkaConsumerOf](config: ConsumerConfig): Resource[F, Consumer[F]] = {

      val config1 = config.copy(
        autoOffsetReset = AutoOffsetReset.Earliest,
        groupId = None,
        autoCommit = false)

      for {
        consumer <- KafkaConsumerOf[F].apply[Id, Bytes](config1)
      } yield {
        HeadCache.Consumer[F](consumer)
      }
    }
  }


  trait Eventual[F[_]] {
    def pointers(topic: Topic): F[TopicPointers]
  }

  object Eventual {

    def apply[F[_]](implicit F: Eventual[F]): Eventual[F] = F

    def apply[F[_]](eventualJournal: EventualJournal[F]): Eventual[F] = new HeadCache.Eventual[F] {
      def pointers(topic: Topic) = eventualJournal.pointers(topic)
    }

    def empty[F[_] : Applicative]: Eventual[F] = const(Applicative[F].pure(TopicPointers.Empty))

    def const[F[_] : Applicative](value: F[TopicPointers]): Eventual[F] = new Eventual[F] {
      def pointers(topic: Topic) = value
    }
  }


  object ConsumeTopic {

    def apply[F[_] : Sync : Timer : Log : ContextShift](
      topic: Topic,
      from: Map[Partition, Offset],
      pollTimeout: FiniteDuration,
      consumer: Consumer[F], // TODO resource
      cancel: F[Boolean])(
      onRecords: Map[Partition, List[KafkaRecord]] => F[Unit]): F[Unit] = {

      def kafkaRecords(records: ConsumerRecords[Id, Bytes]) = {
        for {
          (partition, records0) <- records.values
          records                = for {
            record <- records0
            record <- KafkaRecord.opt(record)
          } yield record
          if records.nonEmpty
        } yield {
          (partition.partition, records)
        }
      }

      val poll = for {
        cancel <- cancel
        result <- {
          if (cancel) ().some.pure[F]
          else for {
            _        <- ContextShift[F].shift
            records0 <- consumer.poll(pollTimeout)
            records   = kafkaRecords(records0)
            _        <- if (records.isEmpty) ().pure[F] else onRecords(records)
          } yield none[Unit]
        }
      } yield result

      val partitionsOf: F[Nel[Partition]] = {

        val onError = (error: Throwable, details: Retry.Details) => {
          import Retry.Decision

          def prefix = s"consumer.partitions($topic) failed"

          details.decision match {
            case Decision.Retry(delay) =>
              Log[F].error(s"$prefix, retrying in $delay, error: $error")

            case Decision.GiveUp =>
              val retries = details.retries
              Log[F].error(s"$prefix, retried $retries times, error: $error", error)
          }
        }

        val partitions = for {
          partitions <- consumer.partitions(topic)
          partitions <- Nel.opt(partitions) match {
            case Some(a) => a.pure[F]
            case None    => NoPartitionsException.raiseError[F, Nel[Partition]]
          }
        } yield partitions

        implicit val clock = Timer[F].clock

        for {
          random     <- Random.State.fromClock[F]()
          strategy    = Retry.Strategy.fullJitter(3.millis, random).cap(300.millis)
          partitions <- Retry[F, Throwable](strategy, onError).apply(partitions)
        } yield {
          partitions
        }
      }

      for {
        partitions <- partitionsOf
        _          <- consumer.assign(topic, partitions)
        offsets     = for {
          partition <- partitions
        } yield {
          val offset = from.get(partition).fold(Offset.Min)(_ + 1l)
          (partition, offset)
        }
        _          <- consumer.seek(topic, offsets.toMap)
        _          <- poll.untilDefinedM
      } yield {}
    }
  }


  case object NoPartitionsException extends RuntimeException("No partitions") with NoStackTrace

  case object ClosedException extends RuntimeException("HeadCache is closed") with NoStackTrace


  implicit class HeadCacheOps[F[_]](val self: HeadCache[F]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): HeadCache[G] = new HeadCache[G] {

      def get(key: Key, partition: Partition, offset: Offset) = {
        f(self.get(key, partition, offset))
      }
    }


    def withMetrics(metrics: Metrics[F])(implicit F: Sync[F], clock: Clock[F]): HeadCache[F] = new HeadCache[F] {

      def get(key: Key, partition: Partition, offset: Offset) = {
        for {
          rl      <- Latency { self.get(key, partition, offset).attempt }
          (r, l)  = rl
          result = r match {
            case Right(Result.Valid(_: JournalInfo.NonEmpty)) => Metrics.Result.NotReplicated
            case Right(Result.Valid(JournalInfo.Empty))       => Metrics.Result.Replicated
            case Right(Result.Invalid)                        => Metrics.Result.Invalid
            case Left(_)                                      => Metrics.Result.Failure
          }
          _      <- metrics.get(key.topic, l, result)
          r      <- r.fold(_.raiseError[F, Result], _.pure[F])
        } yield r
      }
    }


    def withLog(log: Log[F])(implicit F: FlatMap[F], clock: Clock[F]): HeadCache[F] = new HeadCache[F] {

      def get(key: Key, partition: Partition, offset: Offset) = {
        for {
          rl     <- Latency { self.get(key, partition, offset) }
          (r, l)  = rl
          _      <- log.debug(s"$key get in ${ l }ms, offset: $partition:$offset, result: $r")
        } yield r
      }
    }
  }


  trait Metrics[F[_]] {

    def get(topic: Topic, latency: Long, result: Metrics.Result): F[Unit]

    def listeners(topic: Topic, size: Int): F[Unit]

    def round(topic: Topic, entries: Long, listeners: Int, deliveryLatency: Long): F[Unit]
  }


  object Metrics {

    def const[F[_]](unit: F[Unit]): Metrics[F] = new Metrics[F] {

      def get(topic: Topic, latency: Offset, result: Metrics.Result) = unit

      def listeners(topic: Topic, size: Int) = unit

      def round(topic: Topic, entries: Long, listeners: Int, deliveryLatency: Long) = unit
    }

    def empty[F[_] : Applicative]: Metrics[F] = const(().pure[F])


    sealed abstract class Result extends Product

    object Result {
      case object Replicated extends Result
      case object NotReplicated extends Result
      case object Invalid extends Result
      case object Failure extends Result
    }
  }


  final case class KafkaRecord(
    id: Id,
    timestamp: Instant,
    offset: Offset,
    header: ActionHeader)

  object KafkaRecord {

    def opt(record: ConsumerRecord[Id, Bytes]): Option[KafkaRecord] = {
      for {
        key <- record.key
        id = key.value
        timestampAndType <- record.timestampAndType
        timestamp = timestampAndType.timestamp
        header <- record.toActionHeader
      } yield {
        KafkaRecord(id, timestamp, record.offset, header)
      }
    }
  }
}