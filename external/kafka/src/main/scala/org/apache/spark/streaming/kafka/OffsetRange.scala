/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.kafka

import kafka.common.TopicAndPartition

/**
 * Represents any object that has a collection of [[OffsetRange]]s. This can be used access the
 * offset ranges in RDDs generated by the direct Kafka DStream (see
 * [[KafkaUtils.createDirectStream()]]).
 * {{{
 *   KafkaUtils.createDirectStream(...).foreachRDD { rdd =>
 *      val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
 *      ...
 *   }
 * }}}
 */
trait HasOffsetRanges {
  def offsetRanges: Array[OffsetRange]
}

/**
 * Represents a range of offsets from a single Kafka TopicAndPartition. Instances of this class
 * can be created with `OffsetRange.create()`.
  * 代表单个Kafka TopicAndPartition的一系列偏移量,这个类的实例可以用“OffsetRange.create()创建
 * @param topic Kafka topic name 卡夫卡主题名称
 * @param partition Kafka partition id Kafka分区ID
 * @param fromOffset Inclusive starting offset 包含起始偏移
 * @param untilOffset Exclusive ending offset 不包含结束偏移
 */
final class OffsetRange private(
    val topic: String,
    val partition: Int,
    val fromOffset: Long,
    val untilOffset: Long) extends Serializable {
  import OffsetRange.OffsetRangeTuple

  /** Kafka TopicAndPartition object, for convenience
    * Kafka TopicAndPartition对象,方便*/
  def topicAndPartition(): TopicAndPartition = TopicAndPartition(topic, partition)

  /** Number of messages this OffsetRange refers to
    * OffsetRange引用的消息数 */
  def count(): Long = untilOffset - fromOffset

  override def equals(obj: Any): Boolean = obj match {
    case that: OffsetRange =>
      this.topic == that.topic &&
        this.partition == that.partition &&
        this.fromOffset == that.fromOffset &&
        this.untilOffset == that.untilOffset
    case _ => false
  }

  override def hashCode(): Int = {
    toTuple.hashCode()
  }

  override def toString(): String = {
    s"OffsetRange(topic: '$topic', partition: $partition, range: [$fromOffset -> $untilOffset])"
  }

  /** this is to avoid ClassNotFoundException during checkpoint restore
    * 这是在检查点恢复期间避免ClassNotFoundException*/
  private[streaming]
  def toTuple: OffsetRangeTuple = (topic, partition, fromOffset, untilOffset)
}

/**
 * Companion object the provides methods to create instances of [[OffsetRange]].
  * Companion对象提供的方法来创建[[OffsetRange]]的实例
 */
object OffsetRange {
  def create(topic: String, partition: Int, fromOffset: Long, untilOffset: Long): OffsetRange =
    new OffsetRange(topic, partition, fromOffset, untilOffset)

  def create(
      topicAndPartition: TopicAndPartition,
      fromOffset: Long,
      untilOffset: Long): OffsetRange =
    new OffsetRange(topicAndPartition.topic, topicAndPartition.partition, fromOffset, untilOffset)

  def apply(topic: String, partition: Int, fromOffset: Long, untilOffset: Long): OffsetRange =
    new OffsetRange(topic, partition, fromOffset, untilOffset)

  def apply(
      topicAndPartition: TopicAndPartition,
      fromOffset: Long,
      untilOffset: Long): OffsetRange =
    new OffsetRange(topicAndPartition.topic, topicAndPartition.partition, fromOffset, untilOffset)

  /** this is to avoid ClassNotFoundException during checkpoint restore
    * 这是在检查点恢复期间避免ClassNotFoundException*/
  private[kafka]
  type OffsetRangeTuple = (String, Int, Long, Long)

  private[kafka]
  def apply(t: OffsetRangeTuple) =
    new OffsetRange(t._1, t._2, t._3, t._4)
}
