package akka.persistence.kafka.journal

import com.evolutiongaming.config.ConfigHelper._
import com.evolutiongaming.kafka.journal.Journal.CallTimeThresholds
import com.evolutiongaming.kafka.journal.JournalConfig
import com.evolutiongaming.kafka.journal.eventual.cassandra.EventualCassandraConfig
import com.typesafe.config.Config
import pureconfig.ConfigSource

import scala.concurrent.duration._

final case class KafkaJournalConfig(
  journal: JournalConfig = JournalConfig.default,
  cassandra: EventualCassandraConfig = EventualCassandraConfig.default,
  startTimeout: FiniteDuration = 1.minute,
  stopTimeout: FiniteDuration = 1.minute,
  maxEventsInBatch: Int = 10,
  callTimeThresholds: CallTimeThresholds = CallTimeThresholds.default)

object KafkaJournalConfig {

  val default: KafkaJournalConfig = KafkaJournalConfig()


  def apply(config: Config): KafkaJournalConfig = apply(config, default)

  def apply(config: Config, default: => KafkaJournalConfig): KafkaJournalConfig = {

    def get[T: FromConf](name: String) = config.getOpt[T](name)

    val callTimeThresholds = ConfigSource.fromConfig(config)
      .at("call-time-thresholds")
      .load[CallTimeThresholds]
      .getOrElse(CallTimeThresholds.default)

    KafkaJournalConfig(
      journal = JournalConfig(config),
      cassandra = get[Config]("cassandra").fold(default.cassandra)(EventualCassandraConfig(_, default.cassandra)),
      startTimeout = get[FiniteDuration]("start-timeout") getOrElse default.startTimeout,
      stopTimeout = get[FiniteDuration]("stop-timeout") getOrElse default.stopTimeout,
      maxEventsInBatch = get[Int]("max-events-in-batch") getOrElse default.maxEventsInBatch,
      callTimeThresholds = callTimeThresholds)
  }
}