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

package org.apache.spark.sql.test

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.{SQLConf, SQLContext}


/**
  * A special [[SQLContext]] prepared for testing.
  * 准备测试的特殊[[SQLContext]]。
  */
private[sql] class TestSQLContext(sc: SparkContext) extends SQLContext(sc) { self =>

  def this() {
    this(new SparkContext("local[2]", "test-sql-context",
      new SparkConf().set("spark.sql.testkey", "true")))
  }

  // Use fewer partitions to speed up testing 使用较少的分区来加快测试速度
  protected[sql] override def createSession(): SQLSession = new this.SQLSession()

  /**
    * A special [[SQLSession]] that uses fewer shuffle partitions than normal.
    * 一个特殊的[[SQLSession]]，使用较少的shuffle分区比正常。
    * */
  protected[sql] class SQLSession extends super.SQLSession {
    protected[sql] override lazy val conf: SQLConf = new SQLConf {
      override def numShufflePartitions: Int = this.getConf(SQLConf.SHUFFLE_PARTITIONS, 5)
    }
  }

  // Needed for Java tests需要Java测试
  def loadTestData(): Unit = {
    testData.loadTestData()
  }

  private object testData extends SQLTestData {
    protected override def _sqlContext: SQLContext = self
  }
}