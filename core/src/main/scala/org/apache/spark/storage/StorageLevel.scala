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

package org.apache.spark.storage

import java.io.{Externalizable, IOException, ObjectInput, ObjectOutput}
import java.util.concurrent.ConcurrentHashMap

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.util.Utils

/**
 * :: DeveloperApi ::
 * Flags for controlling the storage of an RDD. Each StorageLevel records whether to use memory,
 * or ExternalBlockStore, whether to drop the RDD to disk if it falls out of memory or
 * ExternalBlockStore, whether to keep the data in memory in a serialized format, and whether
 * to replicate the RDD partitions on multiple nodes.
 *
 * The [org.apache.spark.storage.StorageLevel$]singleton object contains some static constants
 * for commonly useful storage levels. To create your own storage level object, use the
 * factory method of the singleton object (`StorageLevel(...)`).
 */
@DeveloperApi
class StorageLevel private(
    private var _useDisk: Boolean,
    private var _useMemory: Boolean,
    private var _useOffHeap: Boolean,//使用扩展存储
    private var _deserialized: Boolean,
    private var _replication: Int = 1)
  extends Externalizable {

  // TODO: Also add fields for caching priority, dataset ID, and flushing.
  private def this(flags: Int, replication: Int) {
    this((flags & 8) != 0, (flags & 4) != 0, (flags & 2) != 0, (flags & 1) != 0, replication)
  }

  def this() = this(false, true, false, false)  // For deserialization

  def useDisk: Boolean = _useDisk
  def useMemory: Boolean = _useMemory
  def useOffHeap: Boolean = _useOffHeap
  def deserialized: Boolean = _deserialized
  def replication: Int = _replication

  assert(replication < 40, "Replication restricted to be less than 40 for calculating hash codes")

  if (useOffHeap) {
    require(!useDisk, "Off-heap storage level does not support using disk")
    require(!useMemory, "Off-heap storage level does not support using heap memory")
    require(!deserialized, "Off-heap storage level does not support deserialized storage")
    require(replication == 1, "Off-heap storage level does not support multiple replication")
  }

  override def clone(): StorageLevel = {
    new StorageLevel(useDisk, useMemory, useOffHeap, deserialized, replication)
  }

  override def equals(other: Any): Boolean = other match {
    case s: StorageLevel =>
      s.useDisk == useDisk &&
      s.useMemory == useMemory &&
      s.useOffHeap == useOffHeap &&
      s.deserialized == deserialized &&
      s.replication == replication
    case _ =>
      false
  }

  def isValid: Boolean = (useMemory || useDisk || useOffHeap) && (replication > 0)

  def toInt: Int = {
    var ret = 0
    if (_useDisk) {
      ret |= 8
    }
    if (_useMemory) {
      ret |= 4
    }
    if (_useOffHeap) {
      ret |= 2
    }
    if (_deserialized) {
      ret |= 1
    }
    ret
  }

  override def writeExternal(out: ObjectOutput): Unit = Utils.tryOrIOException {
    out.writeByte(toInt)
    out.writeByte(_replication)
  }

  override def readExternal(in: ObjectInput): Unit = Utils.tryOrIOException {
    val flags = in.readByte()
    _useDisk = (flags & 8) != 0
    _useMemory = (flags & 4) != 0
    _useOffHeap = (flags & 2) != 0
    _deserialized = (flags & 1) != 0
    _replication = in.readByte()
  }

  @throws(classOf[IOException])
  private def readResolve(): Object = StorageLevel.getCachedStorageLevel(this)

  override def toString: String = {
    s"StorageLevel($useDisk, $useMemory, $useOffHeap, $deserialized, $replication)"
  }

  override def hashCode(): Int = toInt * 41 + replication

  def description: String = {
    var result = ""
    result += (if (useDisk) "Disk " else "")
    result += (if (useMemory) "Memory " else "")
    result += (if (useOffHeap) "ExternalBlockStore " else "")
    result += (if (deserialized) "Deserialized " else "Serialized ")
    result += s"${replication}x Replicated"
    result
  }
}


/**
 * Various [[org.apache.spark.storage.StorageLevel]] defined and utility functions for creating
 * new storage levels.
 */
object StorageLevel {
  //不会保存任务数据
  val NONE = new StorageLevel(false, false, false, false)
  //直接将RDD的partition保存在该节点的Disk上
  val DISK_ONLY = new StorageLevel(true, false, false, false)
  //直接将RDD的partition保存在该节点的Disk上,在其他节点上保存一个相同的备份
  val DISK_ONLY_2 = new StorageLevel(true, false, false, false, 2)
  //将RDD的partition对应的原生的Java Object保存在JVM中,如果RDD太大导致它的部分partition不能存储在内存中
  //那么这些partition将不会缓存,并且需要的时候被重新计算,默认缓存的级别
  val MEMORY_ONLY = new StorageLevel(false, true, false, true)
  //将RDD的partition对应的原生的Java Object保存在JVM中,在其他节点上保存一个相同的备份
  val MEMORY_ONLY_2 = new StorageLevel(false, true, false, true, 2)  
  val MEMORY_ONLY_SER = new StorageLevel(false, true, false, false)
  val MEMORY_ONLY_SER_2 = new StorageLevel(false, true, false, false, 2)
  //将RDD的partition反序列化后的对象存储在JVM中,如果RDD太大导致它的部分partition不能存储在内存中
  //超出的partition将被保存在Disk上,并且在需要时读取
  val MEMORY_AND_DISK = new StorageLevel(true, true, false, true)
  //在其他节点上保存一个相同的备份
  val MEMORY_AND_DISK_2 = new StorageLevel(true, true, false, true, 2)
  val MEMORY_AND_DISK_SER = new StorageLevel(true, true, false, false)
  val MEMORY_AND_DISK_SER_2 = new StorageLevel(true, true, false, false, 2)
  //将RDD的partition序列化后存储在Tachyon中
  val OFF_HEAP = new StorageLevel(false, false, true, false)

  /**
   * :: DeveloperApi ::
   * Return the StorageLevel object with the specified name.
   */
  @DeveloperApi
  def fromString(s: String): StorageLevel = s match {
    case "NONE" => NONE
    case "DISK_ONLY" => DISK_ONLY
    case "DISK_ONLY_2" => DISK_ONLY_2
    case "MEMORY_ONLY" => MEMORY_ONLY
    case "MEMORY_ONLY_2" => MEMORY_ONLY_2
    case "MEMORY_ONLY_SER" => MEMORY_ONLY_SER
    case "MEMORY_ONLY_SER_2" => MEMORY_ONLY_SER_2
    case "MEMORY_AND_DISK" => MEMORY_AND_DISK
    case "MEMORY_AND_DISK_2" => MEMORY_AND_DISK_2
    case "MEMORY_AND_DISK_SER" => MEMORY_AND_DISK_SER
    case "MEMORY_AND_DISK_SER_2" => MEMORY_AND_DISK_SER_2
    case "OFF_HEAP" => OFF_HEAP
    case _ => throw new IllegalArgumentException(s"Invalid StorageLevel: $s")
  }

  /**
   * :: DeveloperApi ::
   * Create a new StorageLevel object without setting useOffHeap.
    * 创建一个新的StorageLevel对象而不设置useOffHeap
   */
  @DeveloperApi
  def apply(
      useDisk: Boolean,
      useMemory: Boolean,
      useOffHeap: Boolean,
      deserialized: Boolean,
      replication: Int): StorageLevel = {
    getCachedStorageLevel(
      new StorageLevel(useDisk, useMemory, useOffHeap, deserialized, replication))
  }

  /**
   * :: DeveloperApi ::
   * Create a new StorageLevel object.
    * 创建一个新的StorageLevel对象
   */
  @DeveloperApi
  def apply(
      useDisk: Boolean,
      useMemory: Boolean,
      deserialized: Boolean,
      replication: Int = 1): StorageLevel = {
    getCachedStorageLevel(new StorageLevel(useDisk, useMemory, false, deserialized, replication))
  }

  /**
   * :: DeveloperApi ::
   * Create a new StorageLevel object from its integer representation.
    * 从其整数表示形式创建新的StorageLevel对象
   */
  @DeveloperApi
  def apply(flags: Int, replication: Int): StorageLevel = {
    getCachedStorageLevel(new StorageLevel(flags, replication))
  }

  /**
   * :: DeveloperApi ::
   * Read StorageLevel object from ObjectInput stream.
    * 从ObjectInput流读取StorageLevel对象
   */
  @DeveloperApi
  def apply(in: ObjectInput): StorageLevel = {
    val obj = new StorageLevel()
    obj.readExternal(in)
    getCachedStorageLevel(obj)
  }

  private[spark] val storageLevelCache = new ConcurrentHashMap[StorageLevel, StorageLevel]()

  private[spark] def getCachedStorageLevel(level: StorageLevel): StorageLevel = {
    storageLevelCache.putIfAbsent(level, level)
    storageLevelCache.get(level)
  }
}
