package com.evolutiongaming.kafka.journal

import cats.implicits._
import com.evolutiongaming.kafka.journal.FoldWhile._
import com.evolutiongaming.kafka.journal.FoldWhileHelper._
import com.evolutiongaming.kafka.journal.IO2.ops._
import com.evolutiongaming.skafka.{Offset, Partition}

trait FoldActions[F[_]] {
  def apply[S](offset: Option[Offset], s: S)(f: Fold[S, Action.User]): F[S]
}

object FoldActions {

  def empty[F[_] : IO2]: FoldActions[F] = new FoldActions[F] {
    def apply[S](offset: Option[Offset], s: S)(f: Fold[S, Action.User]) = IO2[F].pure(s)
  }

  // TODO add range argument
  def apply[F[_] : IO2](
    key: Key,
    from: SeqNr,
    marker: Marker,
    offsetReplicated: Option[Offset],
    withReadActions: WithReadActions[F]): FoldActions[F] = {

    // TODO compare partitions !
    val partition = marker.partition

    val replicated = offsetReplicated.exists(_ >= marker.offset)

    if (replicated) empty
    else new FoldActions[F] {

      def apply[S](offset: Option[Offset], s: S)(f: Fold[S, Action.User]) = {

        val max = marker.offset - 1

        val replicated = offset.exists(_ >= max)

        if (replicated) IO2[F].pure(s)
        else {
          val last = offset max offsetReplicated
          withReadActions(key, partition, last) { readActions =>

            val ff = (s: S) => {
              for {
                actions <- readActions()
              } yield {
                actions.foldWhile(s) { case (s, action) =>
                  val switch = action.action match {
                    case action: Action.Append => if (action.range.to < from) s.continue else f(s, action)
                    case action: Action.Delete => f(s, action)
                    case action: Action.Mark   => s switch action.id != marker.id
                  }
                  if (switch.stop) switch
                  else switch.switch(action.offset < max)
                }
              }
            }
            ff.foldWhile(s)
          }
        }
      }
    }
  }
}

final case class Marker(id: String, partitionOffset: PartitionOffset) {
  def offset: Offset = partitionOffset.offset
  def partition: Partition = partitionOffset.partition
}