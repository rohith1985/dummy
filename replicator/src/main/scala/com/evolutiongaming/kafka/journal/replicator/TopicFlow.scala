package com.evolutiongaming.kafka.journal.replicator

import cats.data.{NonEmptyList => Nel, NonEmptyMap => Nem}
import com.evolutiongaming.kafka.journal.ConsRecord
import com.evolutiongaming.skafka.{Offset, Partition}


trait TopicFlow[F[_]] {

  def assign(partitions: Nel[Partition]): F[Unit]

  def apply(records: Nem[Partition, Nel[ConsRecord]]): F[Map[Partition, Offset]]

  def revoke(partitions: Nel[Partition]): F[Unit]
}