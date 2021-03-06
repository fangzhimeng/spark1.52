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

import org.apache.spark._

/**
  * HDFS测试
  */
object HdfsTest {

  /**
    *  Usage: HdfsTest [file]
    *  使用HDFS测试
    *  */
  def main(args: Array[String]) {
    val hdfs="hdfs://name-node1:8020/user/liush/dfs_read_write_test/"
    /*if (args.length < 1) {
      System.err.println("Usage: HdfsTest <file>")
      System.exit(1)
    }*/

    val sparkConf = new SparkConf().setAppName("HdfsTest").setMaster("local")
    val sc = new SparkContext(sparkConf)
    // val files="D:\\spark\\spark-1.5.0-hadoop2.6\\CHANGES.txt"
    val file = sc.textFile(hdfs)

    val mapped = file.map(s => s.length).cache()
    for (iter <- 1 to 10) {
      val start = System.currentTimeMillis()
      for (x <- mapped) {
       // println("x:"+x)
        x + 2
      }
      val end = System.currentTimeMillis()
      println("Iteration " + iter + " took " + (end-start) + " ms")
    }
    sc.stop()
  }
}
// scalastyle:on println