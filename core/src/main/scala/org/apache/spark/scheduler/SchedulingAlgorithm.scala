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

/**
 * An interface for sort algorithm
 * FIFO: FIFO algorithm between TaskSetManagers
 * FS: FS algorithm between Pools, and FIFO or FS within Pools
  * 排序算法的界面
  *FIFO：TaskSetManagers之间的FIFO算法
  *FS：池之间的FS算法，以及池内的FIFO或FS
 */
private[spark] trait SchedulingAlgorithm {
  def comparator(s1: Schedulable, s2: Schedulable): Boolean
}
/**
 * FIFOSchedulingAlgorith首先保证Job Id较小的先被执行调度,如果是同一个Job,那么Stage ID小的先被调度
 */
private[spark] class FIFOSchedulingAlgorithm extends SchedulingAlgorithm {
  //比较函数  
  override def comparator(s1: Schedulable, s2: Schedulable): Boolean = {
    val priority1 = s1.priority//实际上JobID
    val priority2 = s2.priority
    //如果参数大于零返回1.0,如果参数小于零返回-1,如果参数为0,则返回signum函数的参数为零
    var res = math.signum(priority1 - priority2)//首比较Job ID
    if (res == 0) {
      //如果Job ID相同,那么比较Stage ID
      val stageId1 = s1.stageId
      val stageId2 = s2.stageId
      res = math.signum(stageId1 - stageId2)
    }
    if (res < 0) {
      true
    } else {
      false
    }
  }
}

private[spark] class FairSchedulingAlgorithm extends SchedulingAlgorithm {
  override def comparator(s1: Schedulable, s2: Schedulable): Boolean = {
    val minShare1 = s1.minShare
    val minShare2 = s2.minShare
    val runningTasks1 = s1.runningTasks
    val runningTasks2 = s2.runningTasks
    val s1Needy = runningTasks1 < minShare1
    val s2Needy = runningTasks2 < minShare2
    val minShareRatio1 = runningTasks1.toDouble / math.max(minShare1, 1.0).toDouble
    val minShareRatio2 = runningTasks2.toDouble / math.max(minShare2, 1.0).toDouble
    val taskToWeightRatio1 = runningTasks1.toDouble / s1.weight.toDouble
    val taskToWeightRatio2 = runningTasks2.toDouble / s2.weight.toDouble
    var compare: Int = 0
    // 前者的runningTasks<minShare而后者相反的的话,返回true;
    // runningTasks为正在运行的tasks数目,minShare为最小共享cores数;
    // 前面两个if判断的意思是两个TaskSetManager中,如果其中一个正在运行的tasks数目小于最小共享cores数,则优先调度该TaskSetManager
    if (s1Needy && !s2Needy) {
      return true
    } else if (!s1Needy && s2Needy) {//前者的runningTasks>=minShare而后者相反的的话,返回true  
      return false
    } else if (s1Needy && s2Needy) {
       //如果两者的正在运行的tasks数目都比最小共享cores数小的话,再比较minShareRatio  
       //minShareRatio为正在运行的tasks数目与最小共享cores数的比率  
      compare = minShareRatio1.compareTo(minShareRatio2)
    } else {
      //最后比较taskToWeightRatio,即权重使用率,weight代表调度池对资源获取的权重,越大需要越多的资源 
      compare = taskToWeightRatio1.compareTo(taskToWeightRatio2)
    }

    if (compare < 0) {
      true
    } else if (compare > 0) {
      false
    } else {
      s1.name < s2.name
    }
  }
}

