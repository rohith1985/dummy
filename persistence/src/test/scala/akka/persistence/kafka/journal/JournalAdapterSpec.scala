package akka.persistence.kafka.journal

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.persistence.{AtomicWrite, PersistentRepr}
import cats.Id
import cats.data.{NonEmptyList => Nel}
import cats.effect.Clock
import cats.implicits._
import com.evolutiongaming.catshelper.ClockHelper._
import com.evolutiongaming.catshelper.Log
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.sstream.Stream
import org.scalatest.{FunSuite, Matchers}
import play.api.libs.json.{JsValue, Json}

class JournalAdapterSpec extends FunSuite with Matchers {
  import JournalAdapterSpec.StateT._
  import JournalAdapterSpec._

  private val eventSerializer = EventSerializer.const[StateT](event, persistentRepr)

  private val aws = List(
    AtomicWrite(List(persistentRepr)),
    AtomicWrite(List(persistentRepr)))

  private val metadataAndHeadersOf = {
    val metadataAndHeaders = MetadataAndHeaders(metadata.data, headers)
    MetadataAndHeadersOf.const(metadataAndHeaders.pure[StateT])
  }

  private val journalAdapter = JournalAdapter[StateT](StateT.JournalStateF, toKey, eventSerializer, metadataAndHeadersOf)

  private def appendOf(key: Key, events: Nel[Event]) = {
    Append(key, events, timestamp, metadata, headers)
  }

  test("write") {
    val (data, result) = journalAdapter.write(aws).run(State.empty)
    result shouldEqual Nil
    data shouldEqual State(appends = List(appendOf(key1, Nel.of(event, event))))
  }

  test("delete") {
    val (data, _) = journalAdapter.delete(persistenceId, SeqNr.max).run(State.empty)
    data shouldEqual State(deletes = List(Delete(key1, SeqNr.max, timestamp)))
  }

  test("lastSeqNr") {
    val (data, result) = journalAdapter.lastSeqNr(persistenceId, SeqNr.max).run(State.empty)
    result shouldEqual None
    data shouldEqual State(pointers = List(Pointer(key1)))
  }

  test("replay") {
    val range = SeqRange(from = SeqNr.min, to = SeqNr.max)
    val initial = State(events = List(eventRecord))
    val f = (a: PersistentRepr) => StateT { s =>
      val s1 = s.copy(replayed = a :: s.replayed)
      (s1, ())
    }
    val (data, _) = journalAdapter.replay(persistenceId, range, Int.MaxValue)(f).run(initial)
    data shouldEqual State(reads = List(Read(key1, SeqNr.min)), replayed = List(persistentRepr))
  }

  test("withBatching") {
    val grouping = new Batching[StateT] {
      def apply(aws: List[AtomicWrite]) = aws.map(aw => List(aw)).pure[StateT]
    }
    val (data, result) = journalAdapter
      .withBatching(grouping)
      .write(aws).run(State.empty)
    result shouldEqual Nil
    data shouldEqual State(appends = List(
      appendOf(key1, Nel.of(event)),
      appendOf(key1, Nel.of(event))))
  }
}

object JournalAdapterSpec {

  private val timestamp: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
  private val toKey = ToKey.default[StateT]
  private val key1 = Key(id = "id", topic = "journal")
  private val event = Event(SeqNr.min)
  private val partitionOffset = PartitionOffset.empty
  private val persistenceId = "id"
  private val persistentRepr = PersistentRepr(None, persistenceId = persistenceId)
  private val metadata = Metadata(Json.obj(("key", "value")).some)
  private val headers = Headers(("key", "value"))
  private val origin = Origin("origin")
  private val eventRecord = EventRecord(event, timestamp, partitionOffset, origin.some, metadata, headers)

  final case class Append(
    key: Key,
    events: Nel[Event],
    timestamp: Instant,
    metadata: Metadata,
    headers: Headers)

  final case class Read(key: Key, from: SeqNr)

  final case class Delete(key: Key, to: SeqNr, timestamp: Instant)

  final case class Pointer(key: Key)

  final case class State(
    events: List[EventRecord] = Nil,
    appends: List[Append] = Nil,
    pointers: List[Pointer] = Nil,
    deletes: List[Delete] = Nil,
    reads: List[Read] = Nil,
    replayed: List[PersistentRepr] = Nil)

  object State {
    val empty: State = State()
  }


  type StateT[A] = cats.data.StateT[Id, State, A]

  object StateT {

    implicit val LogStateF: Log[StateT] = Log.empty[StateT]

    implicit val clockStateF: Clock[StateT] = Clock.const(nanos = 0, millis = timestamp.toEpochMilli) // TODO add Instant as argument

    implicit val JournalStateF: Journal[StateT] = new Journal[StateT] {

      def append(key: Key, events: Nel[Event], metadata: Option[JsValue], headers: Headers) = {
        StateT { s =>
          val append = Append(key, events, timestamp, Metadata(metadata), headers)
          val s1 = s.copy(appends = append :: s.appends)
          (s1, partitionOffset)
        }
      }

      def read(key: Key, from: SeqNr) = {
        val stream = StateT { state =>
          val stream = Stream[StateT].apply(state.events)
          val read = Read(key, from)
          val state1 = state.copy(reads = read :: state.reads, events = Nil)
          (state1, stream)
        }
        Stream.lift(stream).flatten
      }

      def pointer(key: Key) = {
        StateT { state =>
          val state1 = state.copy(pointers = Pointer(key) :: state.pointers)
          (state1, none[SeqNr])
        }
      }

      def delete(key: Key, to: SeqNr) = {
        StateT { state =>
          val delete = Delete(key, to, timestamp)
          val state1 = state.copy(deletes = delete :: state.deletes)
          (state1, none[PartitionOffset])
        }
      }
    }

    def apply[A](f: State => (State, A)): StateT[A] = cats.data.StateT[Id, State, A](f)
  }
}
