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

package org.apache.spark.scheduler

import java.util.Properties

import scala.collection.Map
import scala.language.existentials

import org.apache.spark._
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.rdd.RDD
import org.apache.spark.util.CallSite

/**
 * Types of events that can be handled by the DAGScheduler. The DAGScheduler uses an event queue
 * architecture where any thread can post an event (e.g. a task finishing or a new job being
 * submitted) but there is a single "logic" thread that reads these events and takes decisions.
 * This greatly simplifies synchronization.
 * 这可以通过DAGScheduler处理事件的类型,dagscheduler使用事件队列架构在多线程发布一个事件,
 * 有一个单一的“逻辑”线程读取这些事件,这大大简化了同步
 */
private[scheduler] sealed trait DAGSchedulerEvent

private[scheduler] case class JobSubmitted(
    jobId: Int,
    finalRDD: RDD[_],
    func: (TaskContext, Iterator[_]) => _,
    partitions: Array[Int],//Partition要进行计算
    callSite: CallSite,
    listener: JobListener,//joblistener作业监听等等  
    properties: Properties = null)
  extends DAGSchedulerEvent

private[scheduler] case class StageCancelled(stageId: Int) extends DAGSchedulerEvent

private[scheduler] case class JobCancelled(jobId: Int) extends DAGSchedulerEvent

private[scheduler] case class JobGroupCancelled(groupId: String) extends DAGSchedulerEvent

private[scheduler] case object AllJobsCancelled extends DAGSchedulerEvent

private[scheduler]
case class BeginEvent(task: Task[_], taskInfo: TaskInfo) extends DAGSchedulerEvent

private[scheduler]
case class GettingResultEvent(taskInfo: TaskInfo) extends DAGSchedulerEvent

private[scheduler] case class CompletionEvent(
    task: Task[_],
    reason: TaskEndReason,
    result: Any,
    accumUpdates: Map[Long, Any],
    taskInfo: TaskInfo,
    taskMetrics: TaskMetrics)
  extends DAGSchedulerEvent

private[scheduler] case class ExecutorAdded(execId: String, host: String) extends DAGSchedulerEvent

private[scheduler] case class ExecutorLost(execId: String) extends DAGSchedulerEvent

private[scheduler]
case class TaskSetFailed(taskSet: TaskSet, reason: String, exception: Option[Throwable])
  extends DAGSchedulerEvent

private[scheduler] case object ResubmitFailedStages extends DAGSchedulerEvent
