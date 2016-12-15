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
package org.apache.spark.examples.streaming

import java.io.File
import java.nio.charset.Charset

import com.google.common.io.Files

import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.{Time, Seconds, StreamingContext}
import org.apache.spark.util.IntParam

/**
 * Counts words in text encoded with UTF8 received from the network every second.
 * 每一秒从网络接收的文本UTF8编码单词的计数
 * Usage: RecoverableNetworkWordCount <hostname> <port> <checkpoint-directory> <output-file>
 *   <hostname> and <port> describe the TCP server that Spark Streaming would connect to receive
 *   描述:Spark流将接收的TCP服务器数据
 *   data. 
 * <checkpoint-directory> directory to HDFS-compatible file system which checkpoint data
 *   												目录文件系统HDFS兼容检查数据
 * <output-file> file to which the word counts will be appended
 *								 将要添加的字计数的文件
 * <checkpoint-directory> and <output-file> must be absolute paths
 *																					必须是绝对路径
 * To run this on your local machine, you need to first run a Netcat server
 * 运行在本地机器上,你需要先运行一个netcat服务器
 *
 *      `$ nc -lk 9999`
 *
 * and run the example as
 *
 *      `$ ./bin/run-example org.apache.spark.examples.streaming.RecoverableNetworkWordCount \
 *              localhost 9999 ~/checkpoint/ ~/out`
 *
 * If the directory ~/checkpoint/ does not exist (e.g. running for the first time), it will create
 * 如果~/checkpoint/ 目录不存在(第一次运行),它将创建一个新StreamingContext(将打印“创建新上下文”到控制台)
 * a new StreamingContext (will print "Creating new context" to the console). Otherwise, if
 * checkpoint data exists in ~/checkpoint/, then it will create StreamingContext from
 * the checkpoint data.
 * 否则,如果检查点数据存在于~/checkpoint/，那么它将创造StreamingContext检查点数据
 *
 * Refer to the online documentation for more details.
 * 参考联机文档以进行更多细节 * 
 */
//可恢复的网络单词计数
object RecoverableNetworkWordCount {

  def createContext(ip: String, port: Int, outputPath: String, checkpointDirectory: String)
    : StreamingContext = {

    // If you do not see this printed, that means the StreamingContext has been loaded
    //如果你看不到这个打印,这意味着流上下文已从新的检查点加载
    // from the new checkpoint
    println("Creating new context")
    val outputFile = new File(outputPath)
    if (outputFile.exists()) outputFile.delete()
    val sparkConf = new SparkConf().setAppName("RecoverableNetworkWordCount")
    // Create the context with a 1 second batch size
    //创建上下文一个1秒批量大小
    val ssc = new StreamingContext(sparkConf, Seconds(1))
    ssc.checkpoint(checkpointDirectory)

    // Create a socket stream on target ip:port and count the
    // words in input stream of \n delimited text (eg. generated by 'nc')
    //在目标IP上创建一个套接字流:端口,并在“n”分隔的文本的输入流中计数单词
    val lines = ssc.socketTextStream(ip, port)
    val words = lines.flatMap(_.split(" "))
    val wordCounts = words.map(x => (x, 1)).reduceByKey(_ + _)
    wordCounts.foreachRDD((rdd: RDD[(String, Int)], time: Time) => {
      val counts = "Counts at time " + time + " " + rdd.collect().mkString("[", ", ", "]")
      println(counts)
      println("Appending to " + outputFile.getAbsolutePath)
      Files.append(counts + "\n", outputFile, Charset.defaultCharset())
    })
    ssc
  }

  def main(args: Array[String]) {
    if (args.length != 4) {
      System.err.println("You arguments were " + args.mkString("[", ", ", "]"))
      System.err.println(
        """
          |Usage: RecoverableNetworkWordCount <hostname> <port> <checkpoint-directory>
          |     <output-file>. <hostname> and <port> describe the TCP server that Spark
          |     Streaming would connect to receive data. <checkpoint-directory> directory to
          |     HDFS-compatible file system which checkpoint data <output-file> file to which the
          |     word counts will be appended
          |
          |In local mode, <master> should be 'local[n]' with n > 1
          |Both <checkpoint-directory> and <output-file> must be absolute paths
        """.stripMargin
      )
      System.exit(1)
    }
    val Array(ip, IntParam(port), checkpointDirectory, outputPath) = args
    val ssc = StreamingContext.getOrCreate(checkpointDirectory,
      () => {
        createContext(ip, port, outputPath, checkpointDirectory)
      })
    ssc.start()
    ssc.awaitTermination()
  }
}
// scalastyle:on println
