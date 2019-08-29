package com.evolutiongaming.kafka

package object journal {

  type Tag = String

  type Tags = Set[Tag]

  type Headers = Map[String, String]
}
