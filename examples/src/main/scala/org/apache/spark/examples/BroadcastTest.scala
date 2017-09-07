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

// scalastyle:off println
package org.apache.spark.examples

import org.apache.spark.{SparkConf, SparkContext}

/**
  * Usage: BroadcastTest [slices] [numElem] [broadcastAlgo] [blockSize]
  * 使用:广播测试  [分片] [元素数] [广播算法] [块的大小]
  */
object BroadcastTest {
  def main(args: Array[String]) {

    val bcName = if (args.length > 2) args(2) else "Http"
    val blockSize = if (args.length > 3) args(3) else "4096" //4兆字节

    val sparkConf = new SparkConf().setAppName("Broadcast Test").setMaster("local")
      .set("spark.broadcast.factory", s"org.apache.spark.broadcast.${bcName}BroadcastFactory")
      .set("spark.broadcast.blockSize", blockSize)
    val sc = new SparkContext(sparkConf)

    val slices = if (args.length > 0) args(0).toInt else 2
    val num = if (args.length > 1) args(1).toInt else 1000000

    val arr1 = (0 until num).toArray

    for (i <- 0 until 3) {
      println("Iteration " + i)
      println("===========")
      val startTime = System.nanoTime  //系统计时器的当前值,以毫微秒为单位
      val barr1 = sc.broadcast(arr1)
      val observedSizes = sc.parallelize(1 to 10, slices).map(_ => barr1.value.size)
      // Collect the small RDD so we can print the observed sizes locally.
      //收集小RDD可以打印尺寸的本地观察
      observedSizes.collect().foreach(i => println(i))
      //1E6==1000000.0
      println("Iteration %d took %.0f milliseconds(毫秒)".format(i, (System.nanoTime - startTime) / 1E6))
    }

    sc.stop()
  }
}
// scalastyle:on println