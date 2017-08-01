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

package org.apache.spark.launcher

import java.io.File
import java.util.{HashMap => JHashMap, List => JList, Map => JMap}

import scala.collection.JavaConversions._

import org.apache.spark.deploy.Command

/**
 * This class is used by CommandUtils. It uses some package-private APIs in SparkLauncher, and since
 * Java doesn't have a feature similar to `private[spark]`, and we don't want that class to be
 * public, needs to live in the same package as the rest of the library.
  * CommandUtils使用此类,它在SparkLauncher中使用一些包私有API,由于Java没有类似于“private [spark]”的功能,
  * 我们不希望该类被公开,需要与其余的一样的生活在同一个包中 的图书馆。
 */
private[spark] class WorkerCommandBuilder(sparkHome: String, memoryMb: Int, command: Command)
    extends AbstractCommandBuilder {

  childEnv.putAll(command.environment)
  childEnv.put(CommandBuilderUtils.ENV_SPARK_HOME, sparkHome)

  override def buildCommand(env: JMap[String, String]): JList[String] = {
    val cmd = buildJavaCommand(command.classPathEntries.mkString(File.pathSeparator))
    cmd.add(s"-Xms${memoryMb}M")//初始堆大小
    cmd.add(s"-Xmx${memoryMb}M")//设置JVM最大可用内存为
    command.javaOpts.foreach(cmd.add)//
    addPermGenSizeOpt(cmd)
    addOptionString(cmd, getenv("SPARK_JAVA_OPTS"))
    cmd
  }

  def buildCommand(): JList[String] = buildCommand(new JHashMap[String, String]())

}
