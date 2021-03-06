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

package org.apache.spark.streaming.receiver

import java.util.concurrent.{ ArrayBlockingQueue, TimeUnit }

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.{ SparkException, Logging, SparkConf }
import org.apache.spark.storage.StreamBlockId
import org.apache.spark.streaming.util.RecurringTimer
import org.apache.spark.util.{ Clock, SystemClock }

/**
 *  Listener object for BlockGenerator events
 *  对于blockgenerator事件监听器对象
 */
private[streaming] trait BlockGeneratorListener {
  /**
   * Called after a data item is added into the BlockGenerator. The data addition and this
   * callback are synchronized with the block generation and its associated callback,
   * 一个数据项后调用添加到blockgenerator,数据添加和回调同步块生成及其相关回调.
   * so block generation waits for the active data addition+callback to complete. This is useful
   * 所以块生成等待活动数据添加+回调完成,这对于更新数据项成功缓冲的元数据非常有用.
   * for updating metadata on successful buffering of a data item, specifically that metadata
   * that will be useful when a block is generated. Any long blocking operation in this callback
   * will hurt the throughput.
   * 特别是在生成块时将有用的元数据.在此回调中的任何长阻塞操作都会影响吞吐量,
   */
  def onAddData(data: Any, metadata: Any)

  /**
   * Called when a new block of data is generated by the block generator. The block generation
   * and this callback are synchronized with the data addition and its associated callback, so
   * 当块生成器生成新数据块时调用,数据添加和回调同步块生成及其相关回调.
   * the data addition waits for the block generation+callback to complete. This is useful
   * 所以块生成等待活动数据添加+回调完成,这对于更新数据项成功缓冲的元数据非常有用.
   * for updating metadata when a block has been generated, specifically metadata that will
   * be useful when the block has been successfully stored. Any long blocking operation in this
   * callback will hurt the throughput.
   */
  def onGenerateBlock(blockId: StreamBlockId)

  /**
   * Called when a new block is ready to be pushed. Callers are supposed to store the block into
   * Spark in this method. Internally this is called from a single
   * 当新块准备被推送时调用,调用方应该将块存储到Spark中.内部这是单线程调用,这是不与任何其他回调同步
   * thread, that is not synchronized with any other callbacks. Hence it is okay to do long
   * blocking operation in this callback.
   * 因此,在这个回调中可以做长阻塞操作
   */
  def onPushBlock(blockId: StreamBlockId, arrayBuffer: ArrayBuffer[_])

  /**
   * Called when an error has occurred in the BlockGenerator. Can be called form many places
   * so better to not do any long block operation in this callback.
   * 在块生成器中发生错误时调用.可以被称为许多地方,以便更好地在这个回调不做任何长块操作
   */
  def onError(message: String, throwable: Throwable)
}

/**
 * Generates batches of objects received by a
 * [[org.apache.spark.streaming.receiver.Receiver]] and puts them into appropriately
 * named blocks at regular intervals. This class starts two threads,
 * one to periodically start a new batch and prepare the previous batch of as a block,
 * the other to push the blocks into the block manager.
 * 生成由接收机接收的对象的批处理,并定期将它们放入适当的块中
 * Note: Do not create BlockGenerator instances directly inside receivers. Use
 * `ReceiverSupervisor.createBlockGenerator` to create a BlockGenerator and use it.
 */
private[streaming] class BlockGenerator(
    listener: BlockGeneratorListener,
    receiverId: Int,
    conf: SparkConf,
    clock: Clock = new SystemClock()) extends RateLimiter(conf) with Logging {

  private case class Block(id: StreamBlockId, buffer: ArrayBuffer[Any])

  /**
   * The BlockGenerator can be in 5 possible states, in the order as follows.
   * blockgenerator可以在5种可能的状态顺序如下
   * - Initialized: Nothing has been started 没有已经运行
   * - Active: start() has been called, and it is generating blocks on added data.
   * 					活动:调用start()方法,它是在添加数据上生成块
   * - StoppedAddingData: stop() has been called, the adding of data has been stopped,
   * -									:已调用stop(),数据的添加已被停止,但块仍然被生成和推送
   *                      but blocks are still being generated and pushed.
   * - StoppedGeneratingBlocks: Generating of blocks has been stopped, but
   * 													:块的生成已被停止,但他们仍然被推送
   *                            they are still being pushed.
   * - StoppedAll: Everything has stopped, and the BlockGenerator object can be GCed.
   * 						 : 一切都停止
   */
  private object GeneratorState extends Enumeration {
    type GeneratorState = Value
    val Initialized, Active, StoppedAddingData, StoppedGeneratingBlocks, StoppedAll = Value
  }
  import GeneratorState._
  //Spark Streaming接收器将接收数据合并成数据块并存储在Spark里的时间间隔,毫秒
  private val blockIntervalMs = conf.getTimeAsMs("spark.streaming.blockInterval", "200ms")
  require(blockIntervalMs > 0, s"'spark.streaming.blockInterval' should be a positive value")
  //以一个固定间隔生成block(默认200ms),用于将CurrentBuffer中缓存的数据流封装为Block后放入blocksForPushing
  private val blockIntervalTimer =
    new RecurringTimer(clock, blockIntervalMs, updateCurrentBuffer, "BlockGenerator")
  private val blockQueueSize = conf.getInt("spark.streaming.blockQueueSize", 10)
  //用于缓存将要使用的Block
  private val blocksForPushing = new ArrayBlockingQueue[Block](blockQueueSize)
  //此线程每隔10秒从blocksForPushing中获取一个Block数据存入存储体系,并缓存到ReceivedBlockQueue
  private val blockPushingThread = new Thread() { override def run() { keepPushingBlocks() } }
  //currentBuffer 用于缓存输入流接收器接收的数据流,接收一条一条数据放入currentBuffer中
  @volatile private var currentBuffer = new ArrayBuffer[Any]
  @volatile private var state = Initialized

  /**
   *  Start block generating and pushing threads.
   *  启动生成块和推送线程
   */
  def start(): Unit = synchronized {
    if (state == Initialized) {
      state = Active
      blockIntervalTimer.start()
      blockPushingThread.start()
      logInfo("Started BlockGenerator")
    } else {
      throw new SparkException(
        s"Cannot start BlockGenerator as its not in the Initialized state [state = $state]")
    }
  }

  /**
   * Stop everything in the right order such that all the data added is pushed out correctly.
   * 以正确的顺序停止一切,这样所有的数据添加被正确地推出来
   * - First, stop adding data to the current buffer.不要将数据添加到当前缓冲区
   * - Second, stop generating blocks.停止生成块
   * - Finally, wait for queue of to-be-pushed blocks to be drained.等待被推块被耗尽的队列
   */
  def stop(): Unit = {
    // Set the state to stop adding data
    //设置状态停止添加数据
    synchronized {
      if (state == Active) {
        state = StoppedAddingData
      } else {
        logWarning(s"Cannot stop BlockGenerator as its not in the Active state [state = $state]")
        return
      }
    }

    // Stop generating blocks and set the state for block pushing thread to start draining the queue
    //停止生成块并设置块推进线程的状态开始排空队列
    logInfo("Stopping BlockGenerator")
    blockIntervalTimer.stop(interruptTimer = false)
    synchronized { state = StoppedGeneratingBlocks }

    // Wait for the queue to drain and mark generated as stopped
    //等待队列来消耗和标记生成的停止
    logInfo("Waiting for block pushing thread to terminate")
    blockPushingThread.join()
    synchronized { state = StoppedAll }
    logInfo("Stopped BlockGenerator")
  }

  /**
   * Push a single data item into the buffer.
   * 将单个数据项推送到缓冲区中
   */
  def addData(data: Any): Unit = {
    if (state == Active) {
      waitToPush()
      synchronized {
        if (state == Active) {
          currentBuffer += data
        } else {
          throw new SparkException(
            "Cannot add data as BlockGenerator has not been started or has been stopped")
        }
      }
    } else {
      throw new SparkException(
        "Cannot add data as BlockGenerator has not been started or has been stopped")
    }
  }

  /**
   * Push a single data item into the buffer. After buffering the data, the
   * `BlockGeneratorListener.onAddData` callback will be called.
   * 将单个数据项推送到缓冲区中,缓冲数据后,BlockGeneratorListener.onAddData将被回调
   */
  def addDataWithCallback(data: Any, metadata: Any): Unit = {
    if (state == Active) {
      waitToPush()
      synchronized {
        if (state == Active) {
          currentBuffer += data
          listener.onAddData(data, metadata)
        } else {
          throw new SparkException(
            "Cannot add data as BlockGenerator has not been started or has been stopped")
        }
      }
    } else {
      throw new SparkException(
        "Cannot add data as BlockGenerator has not been started or has been stopped")
    }
  }

  /**
   * Push multiple data items into the buffer. After buffering the data, the
   * `BlockGeneratorListener.onAddData` callback will be called. Note that all the data items
   * are atomically added to the buffer, and are hence guaranteed to be present in a single block.
   * 将多个数据项推到缓冲区中,将回调BlockGeneratorListener.onAddData函数,请注意,所有的数据项被自动添加到缓冲区
   * 因此,保证是存在于一个块
   */
  def addMultipleDataWithCallback(dataIterator: Iterator[Any], metadata: Any): Unit = {
    if (state == Active) {
      // Unroll iterator into a temp buffer, and wait for pushing in the process
      //将迭代器为临时缓冲,等待过程中的推送
      val tempBuffer = new ArrayBuffer[Any]
      dataIterator.foreach { data =>
        //提供限制在接收器消费数据的速率
        waitToPush()
        tempBuffer += data
      }
      synchronized {
        if (state == Active) {
          currentBuffer ++= tempBuffer
          listener.onAddData(tempBuffer, metadata)
        } else {
          throw new SparkException(
            "Cannot add data as BlockGenerator has not been started or has been stopped")
        }
      }
    } else {
      throw new SparkException(
        "Cannot add data as BlockGenerator has not been started or has been stopped")
    }
  }

  def isActive(): Boolean = state == Active

  def isStopped(): Boolean = state == StoppedAll

  /**
   *  Change the buffer to which single records are added to.
   *  更改单个记录被添加到的缓冲区。
   */
  private def updateCurrentBuffer(time: Long): Unit = {
    try {
      var newBlock: Block = null
      synchronized {
        if (currentBuffer.nonEmpty) {
          val newBlockBuffer = currentBuffer
          currentBuffer = new ArrayBuffer[Any]
          /**
           * 生成新的StreamBlockId,生成规则"Input-"+StreamId+"-"+(调用时间-blockIntervalMs)
           * blockIntervalMs为生成block的间隔时长
           */
          val blockId = StreamBlockId(receiverId, time - blockIntervalMs)
          //调用onGenerateBlock方法
          listener.onGenerateBlock(blockId)
          //将当前currentBuffer与blockId封装为Block
          newBlock = new Block(blockId, newBlockBuffer)
        }
      }

      if (newBlock != null) {
        //将新建的Block放入blocksForPushing中
        blocksForPushing.put(newBlock) // put is blocking when queue is full
      }
    } catch {
      case ie: InterruptedException =>
        logInfo("Block updating timer thread was interrupted")
      case e: Exception =>
        reportError("Error in block updating thread", e)
    }
  }

  /**
   *  Keep pushing blocks to the BlockManager.
   *  继续推送块到块管理器
   */
  private def keepPushingBlocks() {
    logInfo("Started block pushing thread")

    def areBlocksBeingGenerated: Boolean = synchronized {
      state != StoppedGeneratingBlocks //没有停止
    }

    try {
      // While blocks are being generated, keep polling for to-be-pushed blocks and push them.
      //当BlockGenerator没有停止时,每隔100毫秒从blocksForPushing中取出一个Block,然后调用pushBlock
      while (areBlocksBeingGenerated) {
        Option(blocksForPushing.poll(10, TimeUnit.MILLISECONDS)) match {
          case Some(block) => pushBlock(block)//然后调用pushBlock
          case None        =>
        }
      }

      // At this point, state is StoppedGeneratingBlock. So drain the queue of to-be-pushed blocks.
      logInfo("Pushing out the last " + blocksForPushing.size() + " blocks")
      while (!blocksForPushing.isEmpty) {//一旦BlockGenerator停止了,将blocksForPushing中所有的Block取出
        val block = blocksForPushing.take()
        logDebug(s"Pushing block $block")
        
        pushBlock(block)
        logInfo("Blocks left to push " + blocksForPushing.size())
      }
      logInfo("Stopped block pushing thread")
    } catch {
      case ie: InterruptedException =>
        logInfo("Block pushing thread was interrupted")
      case e: Exception =>
        reportError("Error in block pushing thread", e)
    }
  }

  private def reportError(message: String, t: Throwable) {
    logError(message, t)
    listener.onError(message, t)
  }

  private def pushBlock(block: Block) {
    listener.onPushBlock(block.id, block.buffer)
    logInfo("Pushed block " + block.id)
  }
}
